package ro.sellfluence.apphelper;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import ro.sellfluence.app.EmagDBApp;
import ro.sellfluence.app.PopulateDateComenziFromDB;
import ro.sellfluence.app.PopulateProductsTableFromSheets;
import ro.sellfluence.app.PopulateStornoAndReturns;
import ro.sellfluence.app.UpdateProductEmployeeSheetTabsFromSheets;
import ro.sellfluence.app.UpdateEmployeeSheetsFromDB;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.Task;
import ro.sellfluence.support.Logs;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Throwables.getStackTraceAsString;
import static java.util.logging.Level.WARNING;

@NullMarked
public class BackgroundJob {

    private static final Logger logger = Logs.getFileLogger("BackgroundJob", Level.INFO, 10, 1_000_000);
    private static final Duration hourly = Duration.ofHours(1);
    private static final Duration daily = Duration.ofDays(1);
    private static final Duration weekly = Duration.ofDays(7);
    private static final Decider always = (_) -> true;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicReference<@Nullable String> activeTaskName = new AtomicReference<>();
    private final Object taskControlLock = new Object();
    private final Set<String> pausedTaskNames = new HashSet<>();
    private final EmagMirrorDB mirrorDB;
    private final ScheduledExecutorService scheduler;

    public BackgroundJob(EmagMirrorDB db, ScheduledExecutorService scheduler) {
        mirrorDB = db;
        this.scheduler = scheduler;
    }

    public enum RunStatus {
        ACCEPTED,
        BUSY,
        UNKNOWN_TASK,
        SHUTTING_DOWN
    }

    /**
     * Result of a manual run request. {@code blockingTaskName} is set only when the status is {@link RunStatus#BUSY}.
     */
    public record RunResult(RunStatus status, @Nullable String blockingTaskName) {
    }

    /**
     * Name of the task which currently owns the single background-worker slot.
     * The task may still be waiting for the worker thread and therefore not yet be visible as running in the database.
     */
    public @Nullable String activeTaskName() {
        return activeTaskName.get();
    }

    public enum PauseResult {
        UPDATED,
        UNKNOWN_TASK
    }

    @FunctionalInterface
    private interface Transferrer {
        void transfer(EmagMirrorDB db) throws Exception;
    }

    @FunctionalInterface
    private interface Decider {
        boolean shallIRun(LocalDateTime lastRun);
    }

    /**
     * Describes one operation managed by the background-job scheduler.
     *
     * @param name                 unique task name used for database history and administrative controls
     * @param interval             minimum time between successful runs
     * @param failureRetryInterval minimum time after a failed run before another automatic attempt
     * @param decider              additional time-based condition that must allow the task to run
     * @param transferMethod       operation to execute with the application's database
     */
    private record TaskRunner(
            String name,
            Duration interval,
            Duration failureRetryInterval,
            Decider decider,
            Transferrer transferMethod
    ) {
        private TaskRunner(String name, Duration interval, Decider decider, Transferrer transferMethod) {
            this(name, interval, Duration.ZERO, decider, transferMethod);
        }
    }


    private final List<TaskRunner> fetchers = List.of(
            new TaskRunner("Populate products from sheets", hourly, always, PopulateProductsTableFromSheets::updateProductTable),
            new TaskRunner("Fetch new orders from eMAG and update GMV in DB", hourly, always, db -> {
                EmagDBApp.fetchNewOrders(db);
                db.updateGMVTable();
            }),
            new TaskRunner("Fetch not finalized orders from last 30 days eMAG and update GMV in DB", hourly, always, db -> {
                EmagDBApp.fetchOrdersNotFinalizedInDB(db, true);
                db.updateGMVTable();
            }),
            new TaskRunner("Fetch not finalized orders and update GMV in DB", daily, this::outOfOfficeHour, db -> {
                EmagDBApp.fetchOrdersNotFinalizedInDB(db, false);
                db.updateGMVTable();
            }),
            new TaskRunner("Fetch storno orders from eMAG and update GMV in DB", hourly, always, db -> {
                EmagDBApp.fetchStornoOrders(db);
                db.updateGMVTable();
            }),
            new TaskRunner("Fetch RMAs from eMAG and update GMV in DB", hourly, always, db -> {
                EmagDBApp.fetchRMAs(db);
                db.updateGMVTable();
            }),
            new TaskRunner("Refetch some from eMAG and update GMV in DB", weekly, always, db -> {
                EmagDBApp.fetchAndStoreToDBProbabilistic(db);
                db.updateGMVTable();
            })
    );

    private final List<TaskRunner> consumers = List.of(
            new TaskRunner(
                    "Update employee sheet tabs in product table",
                    hourly,
                    hourly,
                    always,
                    UpdateProductEmployeeSheetTabsFromSheets::updateEmployeeSheetTabs
            ),
            new TaskRunner("Transfer to storno and return sheets", hourly, always, PopulateStornoAndReturns::updateSpreadsheets),
            new TaskRunner("Transfer to order and GMV sheets for 2026", hourly, always, (new PopulateDateComenziFromDB(2026))::updateSpreadsheets),
            new TaskRunner("Transfer to employee sheet", hourly, this::outOfOfficeHour, UpdateEmployeeSheetsFromDB::updateSheets)
    );

    private boolean outOfOfficeHour(LocalDateTime time) {
        return time.getHour() < 7 || time.getHour() > 18;
    }

    /**
     * Performs repetitive background work.
     * This method will be called repeatedly by the scheduler.
     */
    public void performWork() {
        if (!running.get() || activeTaskName.get() != null) {
            return;
        }
        try {
            logger.info("BackgroundJob: Starting work cycle");
            selectJobToRun();
            logger.info("BackgroundJob: Work cycle completed");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "BackgroundJob encountered an error: " + getStackTraceAsString(e));
            throw new RuntimeException("Background job failed", e);
        }
    }

    private void selectJobToRun() {
        try {
            var taskInfos = mirrorDB.getAllTasks();
            executeRunners(fetchers, taskInfos, LocalDateTime.MAX);
            var latestFetchTime = findLatestFetchTime(taskInfos);
            executeRunners(consumers, taskInfos, latestFetchTime);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private LocalDateTime findLatestFetchTime(List<Task> taskInfos) {
        var latestFetchTime = LocalDateTime.MIN;
        for (TaskRunner taskRunner : fetchers) {
            var taskInfo = findTask(taskInfos, taskRunner.name);
            var taskTime = getLastRunTime(taskInfo);
            if (taskTime.isAfter(latestFetchTime)) {
                latestFetchTime = taskTime;
            }
        }
        return latestFetchTime;
    }


    /**
     * Execute the runners in the list according to the schedule.
     *
     * @param taskRunners   list of tasks to run.
     * @param taskInfos     information about all tasks.
     * @param referenceTime absolute time barrier. A task shall not execute if it already ran after this time.
     * @throws SQLException if a database access error occurs.
     */
    private boolean executeRunners(List<TaskRunner> taskRunners, List<Task> taskInfos, LocalDateTime referenceTime) throws SQLException {
        for (var taskRunner : taskRunners) {
            var taskName = taskRunner.name();
            var taskInfo = findTask(taskInfos, taskName);
            LocalDateTime lastRun;
            lastRun = getLastRunTime(taskInfo);
            var now = LocalDateTime.now();
            if (
                    lastRun.isBefore(referenceTime)   // Was not run after dependency
                            && lastRun.plus(taskRunner.interval).isBefore(now)    // Waited for enough time
                            && failureRetryDelayElapsed(taskInfo, taskRunner.failureRetryInterval, now)
                            && taskRunner.decider.shallIRun(now)  // There is no other impediment
                            && !isTaskPaused(taskName)
            ) {
                if (claimScheduledTask(taskName)) {
                    executeClaimedRunner(taskRunner);
                    return true;
                }
                return false; // Execute only one task from this priority group at a time.
            }
        }
        return false;
    }

    static boolean failureRetryDelayElapsed(@Nullable Task taskInfo, Duration retryInterval, LocalDateTime now) {
        if (taskInfo == null || taskInfo.error() == null || taskInfo.error().isBlank() || taskInfo.terminated() == null) {
            return true;
        }
        return !taskInfo.terminated().plus(retryInterval).isAfter(now);
    }

    /**
     * Pause or resume automatic scheduling for one configured task. Manual runs remain available while paused.
     * Pausing an already-running task affects only its next scheduled run.
     */
    public PauseResult setTaskPaused(String taskName, boolean paused) {
        if (findRunner(taskName) == null) {
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

    private boolean claimScheduledTask(String taskName) {
        synchronized (taskControlLock) {
            return !pausedTaskNames.contains(taskName) && activeTaskName.compareAndSet(null, taskName);
        }
    }

    /**
     * Queue a task for immediate execution, bypassing its normal schedule.
     * Only tasks from the configured runner lists can be started, and the task is reserved before it is queued so
     * simultaneous requests cannot start more than one task.
     */
    public RunResult requestRun(String taskName) {
        if (!running.get()) {
            logger.info(() -> "Manual run rejected for \"" + taskName + "\": scheduler is shutting down.");
            return new RunResult(RunStatus.SHUTTING_DOWN, null);
        }

        var taskRunner = findRunner(taskName);
        if (taskRunner == null) {
            logger.info(() -> "Manual run rejected for unknown task \"" + taskName + "\".");
            return new RunResult(RunStatus.UNKNOWN_TASK, null);
        }
        var blockingTaskName = activeTaskName.compareAndExchange(null, taskName);
        if (blockingTaskName != null) {
            logger.info(() -> "Manual run rejected for \"" + taskName + "\": \"" + blockingTaskName
                    + "\" is already running or starting.");
            return new RunResult(RunStatus.BUSY, blockingTaskName);
        }

        try {
            logger.info(() -> "Manual run for \"" + taskName + "\" reserved the background-worker slot.");
            scheduler.execute(() -> executeClaimedRunner(taskRunner));
            return new RunResult(RunStatus.ACCEPTED, null);
        } catch (RejectedExecutionException e) {
            activeTaskName.compareAndSet(taskName, null);
            logger.info(() -> "Manual run rejected for \"" + taskName + "\": scheduler did not accept the task.");
            return new RunResult(RunStatus.SHUTTING_DOWN, null);
        } catch (RuntimeException e) {
            activeTaskName.compareAndSet(taskName, null);
            logger.log(WARNING, "Manual run could not be submitted for \"" + taskName + "\".", e);
            return new RunResult(RunStatus.SHUTTING_DOWN, null);
        }
    }

    private void executeClaimedRunner(TaskRunner taskRunner) {
        var taskName = taskRunner.name();
        logger.info(() -> "Task \"" + taskName + "\" is starting.");
        try {
            mirrorDB.startTask(taskName);
            taskRunner.transferMethod.transfer(mirrorDB);
            mirrorDB.endTask(taskName, "");
            logger.info(() -> "Task \"" + taskName + "\" completed successfully.");
        } catch (Exception e) {
            try {
                mirrorDB.endTask(taskName, e);
            } catch (SQLException databaseException) {
                e.addSuppressed(databaseException);
            }
            logger.log(WARNING, taskName + " ended with an error.", e);
        } finally {
            activeTaskName.compareAndSet(taskName, null);
        }
    }

    private @Nullable TaskRunner findRunner(String taskName) {
        for (var taskRunner : fetchers) {
            if (taskRunner.name().equals(taskName)) {
                return taskRunner;
            }
        }
        for (var taskRunner : consumers) {
            if (taskRunner.name().equals(taskName)) {
                return taskRunner;
            }
        }
        return null;
    }


    /**
     * Returns the task information of the task matching the name or null.
     *
     * @param taskInfos list of task information.
     * @param taskName  searched name.
     * @return task information or null.
     */
    private static @Nullable Task findTask(List<Task> taskInfos, String taskName) {
        return taskInfos.stream().filter(it -> it.name().equals(taskName)).findAny().orElse(null);
    }

    /**
     * Return a tasks time of last successful run or <code>LocalDateTime.MIN</code> if the task never ran.
     *
     * @param taskInfo or null.
     * @return last run time.
     */
    private static LocalDateTime getLastRunTime(@Nullable Task taskInfo) {
        LocalDateTime last = taskInfo == null ? LocalDateTime.MIN : taskInfo.lastSuccessfulRun();
        return last != null ? last : LocalDateTime.MIN;
    }

    /**
     * Mark the background job for not running any more.
     */
    public void shutdown() {
        running.set(false);
        logger.info("BackgroundJob: Shutdown requested");
    }
}
