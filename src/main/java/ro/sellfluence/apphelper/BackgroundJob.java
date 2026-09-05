package ro.sellfluence.apphelper;

import org.jspecify.annotations.Nullable;
import ro.sellfluence.app.PopulateDateComenziFromDB;
import ro.sellfluence.app.PopulateProductsTableFromSheets;
import ro.sellfluence.app.PopulateStornoAndReturns;
import ro.sellfluence.app.UpdateEmployeeSheetsFromDB;
import ro.sellfluence.app.UpdateProductEmployeeSheetTabsFromSheets;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.Task;
import ro.sellfluence.support.Logs;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Throwables.getStackTraceAsString;
import static java.util.logging.Level.WARNING;

/**
 * Schedules database-transfer tasks in independent serial lanes.
 *
 * <p>At most one task owns a lane at a time, while tasks in different lanes may execute concurrently. A lane is
 * claimed before work is submitted to the executor, so queued and already-running work are both represented by
 * {@link #laneStatuses()}.</p>
 */
public class BackgroundJob {

    public static final String emagApiLane = "emagApiLane";
    public static final String googleApiLane = "googleApiLane";
    public static final String adsLane = "emagAdsLane";

    private static final Logger logger = Logs.getFileLogger("BackgroundJob", Level.INFO, 10, 1_000_000);
    private static final Duration executeHourly = Duration.ofHours(1);
    private static final Duration executeDaily = Duration.ofDays(1);
    private static final Duration executeWeekly = Duration.ofDays(7);
    private static final Predicate<LocalDateTime> runAlways = _ -> true;
    private static final Predicate<LocalDateTime> runOnlyInTheMorning = time -> time.getHour() < 7;
    private static final Predicate<LocalDateTime> runOnlyInTheAfternoon = time -> time.getHour() > 12 && time.getHour() < 18;
    private static final Predicate<LocalDateTime> runOnlyOutOfOfficeHours = time -> time.getHour() < 7 || time.getHour() > 18;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object taskControlLock = new Object();
    private final Set<String> pausedTaskNames = new HashSet<>();
    private final Map<String, LaneClaim> activeClaims = new HashMap<>();
    private final TaskStore taskStore;
    private final Executor executor;
    private final @Nullable ExecutorService ownedWorkers;
    private final Clock clock;
    private final List<TaskDefinition> taskDefinitions;
    private final Map<String, TaskDefinition> taskDefinitionsByName;
    private final Map<String, List<TaskDefinition>> taskDefinitionsByLane;

    /**
     * Create the production background-job scheduler with an owned worker pool sized to its configured lanes.
     *
     * @param db         application database
     * @param clock      scheduling clock; its zone must match the database session zone used for task timestamps
     * @param adsAliases all Ads-dashboard account aliases discovered from OTP-enabled credentials
     */
    public BackgroundJob(EmagMirrorDB db, Clock clock, List<String> adsAliases) {
        this(
                new DBTaskStore(Objects.requireNonNull(db, "db")),
                clock,
                productionTaskDefinitions(db, clock, adsAliases)
        );
    }

    /**
     * Construct with an owned worker pool, also allowing lifecycle tests without a database.
     */
    BackgroundJob(TaskStore taskStore, Clock clock, List<TaskDefinition> taskDefinitions) {
        this(taskStore, clock, taskDefinitions, null);
    }

    /**
     * Injectable construction seam for deterministic scheduler tests. The caller retains ownership of the executor.
     */
    BackgroundJob(TaskStore taskStore, Executor executor, Clock clock, List<TaskDefinition> taskDefinitions) {
        this(taskStore, clock, taskDefinitions, Objects.requireNonNull(executor, "executor"));
    }

    private BackgroundJob(TaskStore taskStore, Clock clock, List<TaskDefinition> taskDefinitions,
                          @Nullable Executor executor) {
        this.taskStore = Objects.requireNonNull(taskStore, "taskStore");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.taskDefinitions = List.copyOf(taskDefinitions);

        var byName = new LinkedHashMap<String, TaskDefinition>();
        var byLane = new LinkedHashMap<String, List<TaskDefinition>>();
        for (var taskDefinition : this.taskDefinitions) {
            if (byName.putIfAbsent(taskDefinition.name(), taskDefinition) != null) {
                throw new IllegalArgumentException("Duplicate background task name: " + taskDefinition.name());
            }
            byLane.computeIfAbsent(taskDefinition.lane(), _ -> new ArrayList<>()).add(taskDefinition);
        }
        this.taskDefinitionsByName = Collections.unmodifiableMap(byName);
        var immutableByLane = new LinkedHashMap<String, List<TaskDefinition>>();
        byLane.forEach((lane, definitions) -> immutableByLane.put(lane, List.copyOf(definitions)));
        this.taskDefinitionsByLane = Collections.unmodifiableMap(immutableByLane);

        registerConfiguredTasks();

        if (executor == null) {
            var workerNumber = new AtomicInteger();
            this.ownedWorkers = Executors.newFixedThreadPool(Math.max(1, taskDefinitionsByLane.size()), r -> {
                Thread thread = new Thread(r, "BackgroundJob-Worker-" + workerNumber.incrementAndGet());
                thread.setDaemon(true); // Don't prevent JVM shutdown
                return thread;
            });
            this.executor = ownedWorkers;
        } else {
            this.ownedWorkers = null;
            this.executor = executor;
        }
    }

    public enum RunStatus {
        ACCEPTED,
        BUSY,
        UNKNOWN_TASK,
        SHUTTING_DOWN
    }

    /**
     * Result of a manual run request.
     */
    public record RunResult(RunStatus status, @Nullable String blockingTaskName) {
    }

    /**
     * Current state and configured task names for one serial lane.
     */
    public record LaneStatus(String lane, @Nullable String activeTaskName, List<String> taskNames) {
        public LaneStatus {
            taskNames = List.copyOf(taskNames);
        }
    }

    /**
     * Return every configured lane in stable configuration order.
     */
    public List<LaneStatus> laneStatuses() {
        synchronized (taskControlLock) {
            return taskDefinitionsByLane.entrySet().stream()
                    .map(entry -> {
                        var claim = activeClaims.get(entry.getKey());
                        return new LaneStatus(
                                entry.getKey(),
                                claim == null ? null : claim.taskName,
                                entry.getValue().stream().map(TaskDefinition::name).toList()
                        );
                    })
                    .toList();
        }
    }
    
    public enum PauseResult {
        UPDATED,
        UNKNOWN_TASK
    }

    @FunctionalInterface
    interface CheckedAction {
        void run() throws Exception;
    }

    /**
     * Persistence seam kept package-private so scheduler behaviour can be tested without a database.
     */
    interface TaskStore {
        int registerTasks(List<String> taskNames) throws SQLException;

        List<Task> getAllTasks() throws SQLException;

        int startTask(String name) throws SQLException;

        int endTask(String name, String error) throws SQLException;

        int endTask(String name, Throwable error) throws SQLException;
    }

    /**
     * Immutable scheduling definition. Definition order is the priority order within a lane.
     */
    record TaskDefinition(
            String name,
            String lane,
            Duration interval,
            Duration failureRetryInterval,
            Predicate<LocalDateTime> timePredicate,
            @Nullable String prerequisiteTaskName,
            CheckedAction action
    ) {
        TaskDefinition {
            if (name.isBlank()) {
                throw new IllegalArgumentException("Task name must not be blank");
            }
            if (lane.isBlank()) {
                throw new IllegalArgumentException("Task lane must not be blank");
            }
            if (interval.isNegative() || failureRetryInterval.isNegative()) {
                throw new IllegalArgumentException("Task intervals must not be negative");
            }
            Objects.requireNonNull(timePredicate, "timePredicate");
            Objects.requireNonNull(action, "action");
        }

        TaskDefinition(
                String name,
                String lane,
                Duration interval,
                Predicate<LocalDateTime> timePredicate,
                CheckedAction action
        ) {
            this(name, lane, interval, Duration.ZERO, timePredicate, null, action);
        }
    }

    private static List<TaskDefinition> productionTaskDefinitions(
            EmagMirrorDB db,
            Clock clock,
            List<String> adsAliases
    ) {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(clock, "clock");

        var definitions = new ArrayList<TaskDefinition>();
        definitions.add(new TaskDefinition(
                "Populate products from sheets", googleApiLane, executeHourly, runAlways,
                () -> PopulateProductsTableFromSheets.updateProductTable(db)
        ));
        definitions.add(new TaskDefinition(
                "Fetch new orders from eMAG and update GMV in DB", emagApiLane, executeHourly, runAlways,
                () -> {
                    FetchEmagAPI.fetchNewOrders(db);
                    db.updateGMVTable();
                }
        ));
        definitions.add(new TaskDefinition(
                "Fetch not finalized orders from last 30 days eMAG and update GMV in DB",
                emagApiLane, executeHourly, runAlways,
                () -> {
                    FetchEmagAPI.fetchOrdersNotFinalizedInDB(db, true);
                    db.updateGMVTable();
                }
        ));
        definitions.add(new TaskDefinition(
                "Fetch not finalized orders and update GMV in DB", emagApiLane, executeDaily,
                runOnlyOutOfOfficeHours,
                () -> {
                    FetchEmagAPI.fetchOrdersNotFinalizedInDB(db, false);
                    db.updateGMVTable();
                }
        ));
        definitions.add(new TaskDefinition(
                "Fetch storno orders from eMAG and update GMV in DB", emagApiLane, executeHourly, runAlways,
                () -> {
                    FetchEmagAPI.fetchStornoOrders(db);
                    db.updateGMVTable();
                }
        ));
        definitions.add(new TaskDefinition(
                "Fetch RMAs from eMAG and update GMV in DB", emagApiLane, executeHourly, runAlways,
                () -> {
                    FetchEmagAPI.fetchRMAs(db);
                    db.updateGMVTable();
                }
        ));
        definitions.add(new TaskDefinition(
                "Refetch some from eMAG and update GMV in DB", emagApiLane, executeWeekly, runAlways,
                () -> {
                    FetchEmagAPI.fetchAndStoreToDBProbabilistic(db);
                    db.updateGMVTable();
                }
        ));
        definitions.add(new TaskDefinition(
                "Update employee sheet tabs in product table",
                googleApiLane,
                executeHourly,
                executeHourly,
                runAlways,
                null,
                () -> UpdateProductEmployeeSheetTabsFromSheets.updateEmployeeSheetTabs(db)
        ));
        definitions.add(new TaskDefinition(
                "Transfer to storno and return sheets", googleApiLane, executeHourly, runAlways,
                () -> PopulateStornoAndReturns.updateSpreadsheets(db)
        ));
        definitions.add(new TaskDefinition(
                "Transfer to order and GMV sheets for 2026", googleApiLane, executeHourly, runAlways,
                () -> new PopulateDateComenziFromDB(2026).updateSpreadsheets(db)
        ));
        definitions.add(new TaskDefinition(
                "Transfer to employee sheet", googleApiLane, executeHourly, runOnlyOutOfOfficeHours,
                () -> UpdateEmployeeSheetsFromDB.updateSheets(db)
        ));

        definitions.addAll(adsTaskDefinitions(db, clock, adsAliases));
        return List.copyOf(definitions);
    }

    static List<TaskDefinition> adsTaskDefinitions(EmagMirrorDB db, Clock clock, List<String> aliases) {
        var definitions = new ArrayList<TaskDefinition>();
        for (var alias : aliases.stream().distinct().toList()) {
            addAdsTasks(definitions, db, clock, alias);
        }
        return List.copyOf(definitions);
    }

    private static void addAdsTasks(List<TaskDefinition> definitions, EmagMirrorDB db, Clock clock, String alias) {
        final var lane = adsLane + ":" + alias;
        var campaignsTaskName = adsCampaignsTaskName(alias);
        definitions.add(adsTask(
                campaignsTaskName,
                lane,
                null,
                clock,
                (startDate, endDate) -> FetchAds.fetchAdsAndCampaigns(alias, db, startDate, endDate)
        ));
        definitions.add(adsTask(
                "Fetch Ads keywords for " + alias,
                lane,
                campaignsTaskName,
                clock,
                (startDate, endDate) -> FetchAds.fetchKeywords(alias, db, startDate, endDate)
        ));
        definitions.add(adsTask(
                "Fetch Ads search phrases for " + alias,
                lane,
                campaignsTaskName,
                clock,
                (startDate, endDate) -> FetchAds.fetchSearchPhrases(alias, db, startDate, endDate)
        ));
        definitions.add(adsTask(
                "Fetch Ads targeted products for " + alias,
                lane,
                campaignsTaskName,
                clock,
                (startDate, endDate) -> FetchAds.fetchTargetedProducts(alias, db, startDate, endDate)
        ));
        definitions.add(new TaskDefinition(
                "Delete cached files for " + alias,
                lane,
                executeDaily,
                executeHourly,
                runOnlyInTheAfternoon,
                null,
                () -> FetchAds.deleteAdsCache(alias)
        ));
    }

    static String adsCampaignsTaskName(String alias) {
        return "Fetch Ads campaigns and ad sets for " + alias;
    }

    @FunctionalInterface
    interface DateRangeAction {
        void run(LocalDate startDate, LocalDate endDate) throws Exception;
    }

    static TaskDefinition adsTask(
            String name,
            String lane,
            @Nullable String prerequisiteTaskName,
            Clock clock,
            DateRangeAction action
    ) {
        return new TaskDefinition(
                name,
                lane,
                executeDaily,
                executeHourly,
                runOnlyInTheMorning,
                prerequisiteTaskName,
                () -> {
                    var endDate = LocalDate.now(clock);
                    action.run(endDate.minusDays(31), endDate);
                }
        );
    }

    private void registerConfiguredTasks() {
        try {
            taskStore.registerTasks(taskDefinitions.stream().map(TaskDefinition::name).toList());
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to register configured background tasks", e);
        }
    }

    /**
     * Load the history once and submit at most one eligible task for every idle lane.
     */
    public void performWork() {
        if (!running.get()) {
            return;
        }

        try {
            logger.info("BackgroundJob: Starting work cycle");
            var taskInfos = taskStore.getAllTasks();
            var tasksByName = new HashMap<String, Task>();
            taskInfos.forEach(task -> tasksByName.put(task.name(), task));
            var now = LocalDateTime.now(clock);

            for (var laneEntry : taskDefinitionsByLane.entrySet()) {
                submitFirstEligibleTask(laneEntry.getKey(), laneEntry.getValue(), tasksByName, now);
            }
            logger.info("BackgroundJob: Work cycle completed");
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "BackgroundJob encountered an error: " + getStackTraceAsString(e));
            throw new RuntimeException("Background job failed", e);
        }
    }

    private void submitFirstEligibleTask(
            String lane,
            List<TaskDefinition> definitions,
            Map<String, Task> tasksByName,
            LocalDateTime now
    ) {
        if (isLaneClaimed(lane)) {
            return;
        }
        for (var definition : definitions) {
            if (isTaskPaused(definition.name()) || !isEligible(definition, tasksByName, now)) {
                continue;
            }
            var claim = tryClaim(definition, true);
            if (claim != null) {
                submitClaimedRunner(definition, claim, false);
                return;
            }
            if (!running.get() || isLaneClaimed(lane)) {
                return;
            }
        }
    }

    private boolean isEligible(TaskDefinition definition, Map<String, Task> tasksByName, LocalDateTime now) {
        var taskInfo = tasksByName.get(definition.name());
        if (isRunning(taskInfo)) {
            return false;
        }
        var lastSuccessfulRun = getLastSuccessfulRun(taskInfo);
        if (lastSuccessfulRun.plus(definition.interval()).isAfter(now)) {
            return false;
        }
        if (!failureRetryDelayElapsed(taskInfo, definition.failureRetryInterval(), now)) {
            return false;
        }
        if (!definition.timePredicate().test(now)) {
            return false;
        }
        return prerequisiteSatisfied(definition, taskInfo, tasksByName);
    }

    private static boolean prerequisiteSatisfied(
            TaskDefinition definition,
            @Nullable Task taskInfo,
            Map<String, Task> tasksByName
    ) {
        var prerequisiteName = definition.prerequisiteTaskName();
        if (prerequisiteName == null) {
            return true;
        }

        var prerequisite = tasksByName.get(prerequisiteName);
        if (prerequisite == null
                || prerequisite.terminated() == null
                || prerequisite.lastSuccessfulRun() == null
                || !wasSuccessful(prerequisite)) {
            return false;
        }
        return prerequisite.lastSuccessfulRun().isAfter(getLastSuccessfulRun(taskInfo));
    }

    private static boolean wasSuccessful(Task task) {
        return task.error() == null || task.error().isBlank();
    }

    private static boolean isRunning(@Nullable Task task) {
        return task != null && task.started() != null && task.terminated() == null;
    }

    static boolean failureRetryDelayElapsed(@Nullable Task taskInfo, Duration retryInterval, LocalDateTime now) {
        if (taskInfo == null || taskInfo.error() == null || taskInfo.error().isBlank() || taskInfo.terminated() == null) {
            return true;
        }
        return !taskInfo.terminated().plus(retryInterval).isAfter(now);
    }

    /**
     * Pause or resume automatic scheduling. Manual runs remain available while paused.
     */
    public PauseResult setTaskPaused(String taskName, boolean paused) {
        if (!taskDefinitionsByName.containsKey(taskName)) {
            return PauseResult.UNKNOWN_TASK;
        }
        synchronized (taskControlLock) {
            if (paused) {
                pausedTaskNames.add(taskName);
            } else {
                pausedTaskNames.remove(taskName);
            }
        }
        return PauseResult.UPDATED;
    }

    public Set<String> pausedTaskNames() {
        synchronized (taskControlLock) {
            return Set.copyOf(pausedTaskNames);
        }
    }

    private boolean isTaskPaused(String taskName) {
        synchronized (taskControlLock) {
            return pausedTaskNames.contains(taskName);
        }
    }

    /**
     * Manual runs bypass timing, pause, and dependency checks but must claim the task's lane.
     */
    public RunResult requestRun(String taskName) {
        if (!running.get()) {
            logger.info(() -> "Manual run rejected for \"" + taskName + "\": scheduler is shutting down.");
            return new RunResult(RunStatus.SHUTTING_DOWN, null);
        }

        var definition = taskDefinitionsByName.get(taskName);
        if (definition == null) {
            logger.info(() -> "Manual run rejected for unknown task \"" + taskName + "\".");
            return new RunResult(RunStatus.UNKNOWN_TASK, null);
        }

        var claimAttempt = tryClaimForManualRun(definition);
        if (claimAttempt.shuttingDown()) {
            return new RunResult(RunStatus.SHUTTING_DOWN, null);
        }
        var blockingTaskName = claimAttempt.blockingTaskName();
        if (blockingTaskName != null) {
            logger.info(() -> "Manual run rejected for \"" + taskName + "\": \"" + blockingTaskName
                    + "\" is already running or starting in lane \"" + definition.lane() + "\".");
            return new RunResult(RunStatus.BUSY, blockingTaskName);
        }
        var claim = Objects.requireNonNull(claimAttempt.claim());

        logger.info(() -> "Manual run for \"" + taskName + "\" reserved lane \"" + definition.lane() + "\".");
        if (submitClaimedRunner(definition, claim, true)) {
            return new RunResult(RunStatus.ACCEPTED, null);
        }
        return new RunResult(RunStatus.SHUTTING_DOWN, null);
    }

    private ManualClaimAttempt tryClaimForManualRun(TaskDefinition definition) {
        synchronized (taskControlLock) {
            if (!running.get()) {
                return new ManualClaimAttempt(null, null, true);
            }
            var existingClaim = activeClaims.get(definition.lane());
            if (existingClaim != null) {
                return new ManualClaimAttempt(null, existingClaim.taskName, false);
            }
            var claim = new LaneClaim(definition.lane(), definition.name());
            activeClaims.put(definition.lane(), claim);
            return new ManualClaimAttempt(claim, null, false);
        }
    }

    private @Nullable LaneClaim tryClaim(TaskDefinition definition, boolean automatic) {
        synchronized (taskControlLock) {
            if (!running.get()
                    || activeClaims.containsKey(definition.lane())
                    || automatic && pausedTaskNames.contains(definition.name())) {
                return null;
            }
            var claim = new LaneClaim(definition.lane(), definition.name());
            activeClaims.put(definition.lane(), claim);
            return claim;
        }
    }

    private boolean submitClaimedRunner(TaskDefinition definition, LaneClaim claim, boolean manual) {
        try {
            executor.execute(() -> executeClaimedRunner(definition, claim));
            return true;
        } catch (RejectedExecutionException e) {
            releaseClaim(claim);
            logger.info(() -> (manual ? "Manual run" : "Automatic run") + " rejected for \""
                    + definition.name() + "\": executor did not accept the task.");
            return false;
        } catch (RuntimeException e) {
            releaseClaim(claim);
            logger.log(WARNING, (manual ? "Manual run" : "Automatic run")
                    + " could not be submitted for \"" + definition.name() + "\".", e);
            return false;
        }
    }

    private void executeClaimedRunner(TaskDefinition definition, LaneClaim claim) {
        var taskName = definition.name();
        logger.info(() -> "Task \"" + taskName + "\" is starting in lane \"" + definition.lane() + "\".");
        try {
            taskStore.startTask(taskName);
            definition.action().run();
            taskStore.endTask(taskName, "");
            logger.info(() -> "Task \"" + taskName + "\" completed successfully.");
        } catch (Exception e) {
            try {
                taskStore.endTask(taskName, e);
            } catch (SQLException databaseException) {
                e.addSuppressed(databaseException);
            }
            logger.log(WARNING, taskName + " ended with an error.", e);
        } finally {
            releaseClaim(claim);
        }
    }

    private boolean isLaneClaimed(String lane) {
        synchronized (taskControlLock) {
            return activeClaims.containsKey(lane);
        }
    }

    private void releaseClaim(LaneClaim claim) {
        synchronized (taskControlLock) {
            activeClaims.remove(claim.lane, claim);
        }
    }

    private static LocalDateTime getLastSuccessfulRun(@Nullable Task taskInfo) {
        var lastSuccessfulRun = taskInfo == null ? null : taskInfo.lastSuccessfulRun();
        return lastSuccessfulRun == null ? LocalDateTime.MIN : lastSuccessfulRun;
    }

    /**
     * Prevent new submissions and release scheduler claims. An owned worker pool gets up to ten seconds to finish
     * before its tasks are interrupted. Injected executors remain the caller's responsibility.
     */
    public void shutdown() {
        synchronized (taskControlLock) {
            running.set(false);
            activeClaims.clear();
        }
        logger.info("BackgroundJob: Shutdown requested");
        if (ownedWorkers != null) {
            ownedWorkers.shutdown();
            try {
                if (!ownedWorkers.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.warning("Forcing shutdown of background-job workers.");
                    ownedWorkers.shutdownNow();
                }
            } catch (InterruptedException e) {
                ownedWorkers.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private record LaneClaim(String lane, String taskName) {
    }

    private record ManualClaimAttempt(
            @Nullable LaneClaim claim,
            @Nullable String blockingTaskName,
            boolean shuttingDown
    ) {
    }

    private record DBTaskStore(EmagMirrorDB db) implements TaskStore {
        @Override
        public int registerTasks(List<String> taskNames) throws SQLException {
            return db.registerTasks(taskNames);
        }

        @Override
        public List<Task> getAllTasks() throws SQLException {
            return db.getAllTasks();
        }

        @Override
        public int startTask(String name) throws SQLException {
            return db.startTask(name);
        }

        @Override
        public int endTask(String name, String error) throws SQLException {
            return db.endTask(name, error);
        }

        @Override
        public int endTask(String name, Throwable error) throws SQLException {
            return db.endTask(name, error);
        }
    }
}
