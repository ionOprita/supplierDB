package ro.sellfluence.apphelper;

import ro.sellfluence.app.EmagDBApp;
import ro.sellfluence.app.PopulateProductsTableFromSheets;
import ro.sellfluence.app.PopulateStornoAndReturns;
import ro.sellfluence.app.UpdateEmployeeSheetsFromDB;
import ro.sellfluence.db.EmagMirrorDB;
import ro.sellfluence.db.Task;
import ro.sellfluence.support.Logs;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.base.Throwables.getStackTraceAsString;
import static java.util.logging.Level.WARNING;


public class BackgroundJob {

    private static final Logger logger = Logs.getFileLogger("BackgroundJob", Level.INFO, 10, 1_000_000);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final EmagMirrorDB mirrorDB;
    private final String fetchTaskName = "Fetch from eMAG";
    private final String refetchTaskName = "Refetch some from eMAG";
    private final Set<String> fetchTaskNames = Set.of(fetchTaskName, refetchTaskName);

    public BackgroundJob(EmagMirrorDB db) {
        mirrorDB = db;
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

            // TODO: Add your repetitive work here
            // For example:
            // - Fetch new orders
            // - Update database
            // - Sync with external APIs

            Thread.sleep(5000); // Simulate work (remove this)

            logger.info("BackgroundJob: Work cycle completed");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "BackgroundJob interrupted", e);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "BackgroundJob encountered an error: "+getStackTraceAsString(e));
            throw new RuntimeException("Background job failed", e);
        }
    }

    private void selectJobToRun() {
        try {
            var tasks = mirrorDB.getAllTasks();
            var fetchTasks = new ArrayList<Task>();
            var consumeTasks = new ArrayList<Task>();
            for (Task task : tasks) {
                if (fetchTaskNames.contains(task.name())) {
                    fetchTasks.add(task);
                } else {
                    consumeTasks.add(task);
                }
            }
            Function<Task, LocalDateTime> comparator = task -> task.lastSuccessfulRun() != null ? task.lastSuccessfulRun() : LocalDateTime.MIN;
            var latestFetch = fetchTasks.stream()
                    .max(Comparator.comparing(comparator))
                    .orElse(null);
            var latestConsume = consumeTasks.stream()
                    .max(Comparator.comparing(comparator))
                    .orElse(null);
            var refetchTask = tasks.stream().filter(it -> it.name().equals(refetchTaskName)).findAny().orElse(null);
            var fetchTask = tasks.stream().filter(it -> it.name().equals(fetchTaskName)).findAny().orElse(null);
            if (latestFetch == null || latestFetch.lastSuccessfulRun() == null) {
                fetchFromEmag();
            } else if (latestConsume == null || latestConsume.lastSuccessfulRun() == null || latestFetch.lastSuccessfulRun().isAfter(latestConsume.lastSuccessfulRun())) {
                runConsumer(consumeTasks);
            } else if (refetchTask == null || refetchTask.terminated().isBefore(LocalDateTime.now().minusWeeks(1))) {
                refetchFromEmag();
            } else if (fetchTask == null || fetchTask.terminated().isBefore(LocalDateTime.now().minusHours(1))) {
                fetchFromEmag();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void runConsumer(List<Task> consumerInfo) throws SQLException {
        transferOrdersAndGMVToSheet(consumerInfo);
        transferStornosAndReturnsToSheet(consumerInfo);
        transferOrdersToEmployeeSheet(consumerInfo);
    }

    @FunctionalInterface
    interface Transferrer {
        void transfer() throws SQLException;
    }

    @FunctionalInterface
    interface Decider {
        boolean shallIRun(LocalDateTime lastRun) throws SQLException;
    }

    private void transferStornosAndReturnsToSheet(List<Task> consumerInfo) throws SQLException {
        String stornoSheetTaskName = "Transfer to storno and return sheets";
        transferFromDB(consumerInfo,
                stornoSheetTaskName,
                (lastRun) -> LocalDateTime.now().minusHours(3).isAfter(lastRun),
                () -> PopulateStornoAndReturns.updateSpreadsheets(mirrorDB),
                "Updating the Storno and Return sheet ended with an exception.");
    }

    private void transferOrdersAndGMVToSheet(List<Task> consumerInfo) throws SQLException {
        String orderSheetTaskName = "Transfer to order and GMV sheets";
        transferFromDB(consumerInfo,
                orderSheetTaskName,
                (lastRun) -> LocalDateTime.now().minusHours(1).isAfter(lastRun),
                () -> PopulateStornoAndReturns.updateSpreadsheets(mirrorDB),
                "Updating the Order and GMV sheet ended with an exception.");

    }

    private void transferOrdersToEmployeeSheet(List<Task> consumerInfo) throws SQLException {
        String employeeSheetTaskName = "Transfer to employee sheet";
        transferFromDB(consumerInfo,
                employeeSheetTaskName,
                (lastRun) -> {
                    var now = LocalDateTime.now();
                    var dayTime = now.toLocalTime();
                    boolean outOfOfficeHours = dayTime.isBefore(LocalTime.of(7, 0)) || dayTime.isAfter(LocalTime.of(18, 0));
                    return outOfOfficeHours && now.minusHours(1).isAfter(lastRun);
                },
                () -> UpdateEmployeeSheetsFromDB.updateSheets(mirrorDB),
                "Updating the Employee sheets ended with an exception.");

    }

    private void transferFromDB(List<Task> consumerInfo, String taskName, Decider decider, Transferrer transferMethod, String errorMessage) throws SQLException {
        var myInfo = consumerInfo.stream().filter(t -> t.name().equals(taskName)).findAny().orElse(null);
        if (myInfo == null || decider.shallIRun(myInfo.terminated())) {
            try {
                mirrorDB.startTask(taskName);
                transferMethod.transfer();
                mirrorDB.endTask(taskName, "");
            } catch (SQLException e) {
                mirrorDB.endTask(taskName, e);
                logger.log(WARNING, errorMessage, e);
            }
        }
    }

    private void fetchFromEmag() throws SQLException {
        mirrorDB.startTask(fetchTaskName);
        try {
            PopulateProductsTableFromSheets.updateProductTable(mirrorDB);
            EmagDBApp.fetchAndStoreToDB(mirrorDB);
            mirrorDB.endTask(fetchTaskName, "");
        } catch (Exception e) {
            mirrorDB.endTask(fetchTaskName, e);
        }
    }

    private void refetchFromEmag() throws SQLException {
        mirrorDB.startTask(fetchTaskName);
        try {
            PopulateProductsTableFromSheets.updateProductTable(mirrorDB);
            EmagDBApp.fetchAndStoreToDBProbabilistic(mirrorDB);
            mirrorDB.endTask(refetchTaskName, "");
        } catch (Exception e) {
            mirrorDB.endTask(refetchTaskName, e);
        }
    }

    public void shutdown() {
        running.set(false);
        logger.info("BackgroundJob: Shutdown requested");
    }
}