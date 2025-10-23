package ro.sellfluence.apphelper;

import org.jspecify.annotations.NonNull;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Throwables.getStackTraceAsString;
import static java.util.logging.Level.WARNING;


public class BackgroundJob {

    private static final Logger logger = Logs.getFileLogger("BackgroundJob", Level.INFO, 10, 1_000_000);
    private static final Duration hourly = Duration.ofHours(1);
    private static final Duration weekly = Duration.ofDays(7);
    private static final Decider always = (_) -> true;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final EmagMirrorDB mirrorDB;
    private final String fetchTaskName = "Fetch from eMAG";
    private final String refetchTaskName = "Refetch some from eMAG";

    public BackgroundJob(EmagMirrorDB db) {
        mirrorDB = db;
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
            new TaskRunner(fetchTaskName, hourly, always, db -> {
                PopulateProductsTableFromSheets.updateProductTable(db);
                EmagDBApp.fetchAndStoreToDB(db);
            }),
            new TaskRunner(refetchTaskName, weekly, always, EmagDBApp::fetchAndStoreToDBProbabilistic)
    );

    private final List<TaskRunner> consumers = List.of(
            new TaskRunner("Transfer to storno and return sheets", Duration.ofHours(1), always, PopulateStornoAndReturns::updateSpreadsheets),
            new TaskRunner("Transfer to order and GMV sheets", Duration.ofHours(1), always, PopulateDateComenziFromDB::updateSpreadsheets),
            new TaskRunner("Transfer to employee sheet", Duration.ofHours(1), this::outOfOfficeHour, UpdateEmployeeSheetsFromDB::updateSheets)
    );

    private boolean outOfOfficeHour(LocalDateTime time) {
        return time.getHour() < 7 || time.getHour() > 18;
    }

    /**
     * Performs repetitive background work.
     * This method will be called repeatedly by the scheduler.
     */
    public void performWork() {
        if (!running.get()) {
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
            executeRunners(fetchers, taskInfos, LocalDateTime.MIN);
            var latestFetchTime = findLatestFetchTime(taskInfos);
            executeRunners(consumers, taskInfos, latestFetchTime);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private @NonNull LocalDateTime findLatestFetchTime(List<Task> taskInfos) {
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
            ) {

                try {
                    mirrorDB.startTask(taskName);
                    taskRunner.transferMethod.transfer(mirrorDB);
                    mirrorDB.endTask(taskName, "");
                } catch (Exception e) {
                    mirrorDB.endTask(taskName, e);
                    logger.log(WARNING, taskName + " ended with an error.", e);
                }
            }
        }
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
    private static LocalDateTime getLastRunTime(Task taskInfo) {
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