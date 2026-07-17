package ro.sellfluence.apphelper;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import ro.sellfluence.app.EmagDBApp;
import ro.sellfluence.app.PopulateDateComenziFromDB;
import ro.sellfluence.app.PopulateProductsTableFromSheets;
import ro.sellfluence.app.PopulateStornoAndReturns;
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

    public enum RunResult {
        ACCEPTED,
        BUSY,
        UNKNOWN_TASK,
        SHUTTING_DOWN
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

    private record TaskRunner(
            String name,
            Duration interval, Decider decider, Transferrer transferMethod
    ) {
    }


    private final List<TaskRunner> fetchers = List.of(
            new TaskRunner("Fetch from eMAG", hourly, always, db -> {
                PopulateProductsTableFromSheets.updateProductTable(db);
                EmagDBApp.fetchAndStoreToDB(db);
            }),
            new TaskRunner("Refetch some from eMAG", weekly, always, EmagDBApp::fetchAndStoreToDBProbabilistic)
    );

    private final List<TaskRunner> consumers = List.of(
            new TaskRunner("Update GMV in database", hourly, always, EmagMirrorDB::updateGMVTable),
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
     * @param taskRunners list of tasks to run.
     * @param taskInfos information about all tasks.
     * @param referenceTime absolute time barrier. A task shall not execute if it already ran after this time.
     * @throws SQLException if a database access error occurs.
     */
    private void executeRunners(List<TaskRunner> taskRunners, List<Task> taskInfos, LocalDateTime referenceTime) throws SQLException {
        for (var taskRunner : taskRunners) {
            var taskName = taskRunner.name();
            var taskInfo = findTask(taskInfos, taskName);
            LocalDateTime lastRun;
            lastRun = getLastRunTime(taskInfo);
            var now = LocalDateTime.now();
            if (
                    lastRun.isBefore(referenceTime)   // Was not run after dependency
                            && lastRun.plus(taskRunner.interval).isBefore(now)    // Waited for enough time
                            && taskRunner.decider.shallIRun(now)  // There is no other impediment
                            && !isTaskPaused(taskName)
            ) {
                if (claimScheduledTask(taskName)) {
                    executeClaimedRunner(taskRunner);
                }
                return; // Execute only one task at a time.
            }
        }
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
            return RunResult.SHUTTING_DOWN;
        }

        var taskRunner = findRunner(taskName);
        if (taskRunner == null) {
            return RunResult.UNKNOWN_TASK;
        }
        if (!activeTaskName.compareAndSet(null, taskName)) {
            return RunResult.BUSY;
        }

        try {
            scheduler.execute(() -> executeClaimedRunner(taskRunner));
            return RunResult.ACCEPTED;
        } catch (RejectedExecutionException e) {
            activeTaskName.compareAndSet(taskName, null);
            return RunResult.SHUTTING_DOWN;
        }
    }

    private void executeClaimedRunner(TaskRunner taskRunner) {
        var taskName = taskRunner.name();
        try {
            mirrorDB.startTask(taskName);
            taskRunner.transferMethod.transfer(mirrorDB);
            mirrorDB.endTask(taskName, "");
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
