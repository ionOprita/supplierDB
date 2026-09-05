package ro.sellfluence.apphelper;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;
import ro.sellfluence.db.Task;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ro.sellfluence.apphelper.BackgroundJob.RunStatus.ACCEPTED;
import static ro.sellfluence.apphelper.BackgroundJob.RunStatus.BUSY;
import static ro.sellfluence.apphelper.BackgroundJob.RunStatus.SHUTTING_DOWN;

class BackgroundJobTest {

    private static final ZoneId BUCHAREST = ZoneId.of("Europe/Bucharest");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 27, 20, 0);

    @Test
    void acceptsDifferentLanesConcurrentlyAndBlocksTheSameLane() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var definitions = List.of(
                task("transfer-one", "transfers", () -> waitInTask(entered, release)),
                task("transfer-two", "transfers", () -> {
                }),
                task("ads-one", "ads:sellfusion", () -> waitInTask(entered, release))
        );
        var store = new FakeTaskStore();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        var job = new BackgroundJob(store, executor, clockAt(NOW), definitions);

        try {
            assertEquals(ACCEPTED, job.requestRun("transfer-one").status());
            assertEquals(ACCEPTED, job.requestRun("ads-one").status());
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            var blocked = job.requestRun("transfer-two");
            assertEquals(BUSY, blocked.status());
            assertEquals("transfer-one", blocked.blockingTaskName());
            assertEquals(
                    Map.of("transfers", "transfer-one", "ads:sellfusion", "ads-one"),
                    activeTasks(job)
            );
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void dispatcherLoadsHistoryOnceAndSubmitsOneTaskPerIdleLane() {
        var executor = new HoldingExecutor();
        var firstRuns = new AtomicInteger();
        var skippedRuns = new AtomicInteger();
        var adsRuns = new AtomicInteger();
        var store = new FakeTaskStore();
        var job = new BackgroundJob(store, executor, clockAt(NOW), List.of(
                task("first", "transfers", firstRuns::incrementAndGet),
                task("second", "transfers", skippedRuns::incrementAndGet),
                task("ads", "ads:sellfusion", adsRuns::incrementAndGet)
        ));

        job.performWork();

        assertEquals(1, store.historyReads.get());
        assertEquals(List.of("first", "second", "ads"), store.registeredTaskNames);
        assertEquals(2, executor.queuedCount());
        assertEquals(Map.of("transfers", "first", "ads:sellfusion", "ads"), activeTasks(job));

        executor.runAll();
        assertEquals(1, firstRuns.get());
        assertEquals(0, skippedRuns.get());
        assertEquals(1, adsRuns.get());
        assertTrue(activeTasks(job).isEmpty());
    }

    @Test
    void adsDetailRequiresANewerSuccessfulCampaignAttempt() {
        var campaign = scheduledTask("campaign", "ads:sellfusion", Duration.ofDays(1), Duration.ofHours(1), null);
        var detail = scheduledTask("detail", "ads:sellfusion", Duration.ofDays(1), Duration.ofHours(1), "campaign");

        var successfulStore = new FakeTaskStore();
        successfulStore.put(completedTask("campaign", NOW.minusHours(1), NOW.minusHours(1), ""));
        successfulStore.put(completedTask("detail", NOW.minusDays(2), NOW.minusDays(2), ""));
        var successfulExecutor = new HoldingExecutor();
        new BackgroundJob(successfulStore, successfulExecutor, clockAt(NOW), List.of(campaign, detail)).performWork();
        assertEquals(List.of("detail"), successfulExecutor.queuedTaskNames(successfulStore));

        var failedStore = new FakeTaskStore();
        failedStore.put(completedTask("campaign", NOW.minusHours(1), NOW.minusMinutes(30), "login failed"));
        failedStore.put(completedTask("detail", NOW.minusDays(2), NOW.minusDays(2), ""));
        var failedExecutor = new HoldingExecutor();
        new BackgroundJob(failedStore, failedExecutor, clockAt(NOW), List.of(campaign, detail)).performWork();
        assertEquals(0, failedExecutor.queuedCount());

        var staleCampaign = scheduledTask(
                "campaign", "ads:sellfusion", Duration.ofDays(100), Duration.ofHours(1), null
        );
        var equallyFreshStore = new FakeTaskStore();
        equallyFreshStore.put(completedTask("campaign", NOW.minusDays(2), NOW.minusDays(2), ""));
        equallyFreshStore.put(completedTask("detail", NOW.minusDays(2), NOW.minusDays(2), ""));
        var equallyFreshExecutor = new HoldingExecutor();
        new BackgroundJob(
                equallyFreshStore, equallyFreshExecutor, clockAt(NOW), List.of(staleCampaign, detail)
        ).performWork();
        assertEquals(0, equallyFreshExecutor.queuedCount());
    }

    @Test
    void adsTasksUseBucharestOutOfOfficeBoundariesAndAThirtyOneDayWindow() {
        assertEquals(0, adsSubmissionsAt(LocalDateTime.of(2026, 8, 27, 23, 59)));
        assertEquals(1, adsSubmissionsAt(LocalDateTime.of(2026, 8, 27, 0, 0)));
        assertEquals(1, adsSubmissionsAt(LocalDateTime.of(2026, 8, 27, 6, 59)));
        assertEquals(0, adsSubmissionsAt(LocalDateTime.of(2026, 8, 27, 7, 0)));

        var dateRange = new AtomicReference<List<LocalDate>>();
        var now = LocalDateTime.of(2026, 8, 27, 0, 0);
        var executor = new HoldingExecutor();
        var definition = BackgroundJob.adsTask(
                "ads", "ads:sellfusion", null, clockAt(now),
                (startDate, endDate) -> dateRange.set(List.of(startDate, endDate))
        );
        var job = new BackgroundJob(new FakeTaskStore(), executor, clockAt(now), List.of(definition));

        job.performWork();
        executor.runAll();

        assertEquals(List.of(LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 27)), dateRange.get());
    }

    @Test
    void retriesAFailedTaskOnlyAfterItsFailureRetryInterval() {
        var definition = scheduledTask("ads", "ads:sellfusion", Duration.ofDays(1), Duration.ofHours(1), null);
        var store = new FakeTaskStore();
        store.put(completedTask("ads", NOW.minusDays(7), NOW.minusMinutes(59), "timeout"));
        var tooEarly = new HoldingExecutor();
        new BackgroundJob(store, tooEarly, clockAt(NOW), List.of(definition)).performWork();
        assertEquals(0, tooEarly.queuedCount());

        var elapsed = new HoldingExecutor();
        new BackgroundJob(store, elapsed, clockAt(NOW.plusMinutes(1)), List.of(definition)).performWork();
        assertEquals(1, elapsed.queuedCount());
    }

    @Test
    void manualRunBypassesPauseAndDependencyButStillClaimsItsLane() {
        var executor = new HoldingExecutor();
        var detail = scheduledTask("detail", "ads:sellfusion", Duration.ofDays(1), Duration.ofHours(1), "campaign");
        var job = new BackgroundJob(new FakeTaskStore(), executor, clockAt(NOW), List.of(detail));
        assertEquals(BackgroundJob.PauseResult.UPDATED, job.setTaskPaused("detail", true));

        assertEquals(ACCEPTED, job.requestRun("detail").status());
        assertEquals("detail", activeTasks(job).get("ads:sellfusion"));
    }

    @Test
    void pausedPriorityTaskDoesNotBlockTheNextTaskInItsLane() {
        var executor = new HoldingExecutor();
        var job = new BackgroundJob(new FakeTaskStore(), executor, clockAt(NOW), List.of(
                task("paused", "transfers", () -> {
                }),
                task("next", "transfers", () -> {
                })
        ));
        job.setTaskPaused("paused", true);

        job.performWork();

        assertEquals("next", job.laneStatuses().getFirst().activeTaskName());
    }

    @Test
    void releasesAClaimWhenSubmissionIsRejected() {
        var job = new BackgroundJob(
                new FakeTaskStore(),
                _ -> {
                    throw new RejectedExecutionException("shut down");
                },
                clockAt(NOW),
                List.of(task("transfer", "transfers", () -> {
                }))
        );

        assertEquals(SHUTTING_DOWN, job.requestRun("transfer").status());
        assertNull(job.laneStatuses().getFirst().activeTaskName());
    }

    @Test
    void releasesAClaimWhenTheTaskFails() {
        var store = new FakeTaskStore();
        var job = new BackgroundJob(store, Runnable::run, clockAt(NOW), List.of(
                task("transfer", "transfers", () -> {
                    throw new IllegalStateException("failed");
                })
        ));

        assertEquals(ACCEPTED, job.requestRun("transfer").status());

        assertNull(job.laneStatuses().getFirst().activeTaskName());
        assertEquals(List.of("transfer"), store.failedTaskNames);
    }

    @Test
    void staleSubmissionCleanupCannotReleaseANewerClaimForTheSameLane() {
        var executor = new ReplaceThenRejectExecutor();
        var job = new BackgroundJob(new FakeTaskStore(), executor, clockAt(NOW), List.of(
                task("first", "transfers", () -> {
                }),
                task("second", "transfers", () -> {
                })
        ));
        executor.job = job;

        assertEquals(SHUTTING_DOWN, job.requestRun("first").status());

        assertEquals(ACCEPTED, executor.replacementResult.status());
        assertEquals("second", job.laneStatuses().getFirst().activeTaskName());
    }

    @Test
    void shutdownRejectsNewWorkAndStopsAutomaticHistoryReads() {
        var store = new FakeTaskStore();
        var executor = new HoldingExecutor();
        var job = new BackgroundJob(store, executor, clockAt(NOW), List.of(
                task("transfer", "transfers", () -> {
                })
        ));
        assertEquals(ACCEPTED, job.requestRun("transfer").status());

        job.shutdown();
        job.performWork();

        assertEquals(SHUTTING_DOWN, job.requestRun("transfer").status());
        assertNull(job.laneStatuses().getFirst().activeTaskName());
        assertEquals(0, store.historyReads.get());
        assertEquals(1, executor.queuedCount());
    }

    @Test
    void validatesProductionAdsAliases() {
        assertEquals(List.of("sellfusion"), BackgroundJob.validateProductionAliases(List.of("sellfusion")));
        assertThrows(
                IllegalArgumentException.class,
                () -> BackgroundJob.validateProductionAliases(List.of("../sellfusion"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> BackgroundJob.validateProductionAliases(List.of("sellfusion", "another"))
        );
    }

    @Test
    void waitsForTheFailureRetryIntervalAfterAnUnsuccessfulRun() {
        var failedAt = LocalDateTime.of(2026, 8, 12, 10, 0);
        var failedTask = completedTask("task", failedAt.minusDays(1), failedAt, "timeout");

        assertFalse(BackgroundJob.failureRetryDelayElapsed(
                failedTask, Duration.ofHours(1), failedAt.plusMinutes(59)
        ));
        assertTrue(BackgroundJob.failureRetryDelayElapsed(
                failedTask, Duration.ofHours(1), failedAt.plusHours(1)
        ));
    }

    private static int adsSubmissionsAt(LocalDateTime now) {
        var executor = new HoldingExecutor();
        var clock = clockAt(now);
        var definition = BackgroundJob.adsTask("ads", "ads:sellfusion", null, clock, (_, _) -> {
        });
        new BackgroundJob(new FakeTaskStore(), executor, clock, List.of(definition)).performWork();
        return executor.queuedCount();
    }

    private static BackgroundJob.TaskDefinition task(
            String name,
            String lane,
            BackgroundJob.CheckedAction action
    ) {
        return new BackgroundJob.TaskDefinition(name, lane, Duration.ZERO, _ -> true, action);
    }

    private static BackgroundJob.TaskDefinition scheduledTask(
            String name,
            String lane,
            Duration interval,
            Duration retry,
            String prerequisite
    ) {
        return new BackgroundJob.TaskDefinition(
                name, lane, interval, retry, _ -> true, prerequisite, () -> {
        }
        );
    }

    private static Task completedTask(
            String name,
            LocalDateTime lastSuccessfulRun,
            LocalDateTime terminated,
            String error
    ) {
        return new Task(
                name,
                terminated.minusMinutes(5),
                terminated,
                lastSuccessfulRun,
                Duration.ofMinutes(5),
                error.isBlank() ? 0 : 1,
                error
        );
    }

    private static Clock clockAt(LocalDateTime dateTime) {
        return Clock.fixed(dateTime.atZone(BUCHAREST).toInstant(), BUCHAREST);
    }

    private static Map<String, String> activeTasks(BackgroundJob job) {
        var result = new LinkedHashMap<String, String>();
        job.laneStatuses().stream()
                .filter(status -> status.activeTaskName() != null)
                .forEach(status -> result.put(status.lane(), status.activeTaskName()));
        return result;
    }

    private static void waitInTask(CountDownLatch entered, CountDownLatch release) throws InterruptedException {
        entered.countDown();
        release.await();
    }

    private static final class HoldingExecutor implements Executor {
        private final List<Runnable> commands = new ArrayList<>();

        @Override
        public synchronized void execute(Runnable command) {
            commands.add(command);
        }

        synchronized int queuedCount() {
            return commands.size();
        }

        List<String> queuedTaskNames(FakeTaskStore store) {
            runAll();
            return List.copyOf(store.startedTaskNames);
        }

        void runAll() {
            List<Runnable> queued;
            synchronized (this) {
                queued = List.copyOf(commands);
                commands.clear();
            }
            queued.forEach(Runnable::run);
        }
    }

    private static final class ReplaceThenRejectExecutor implements Executor {
        private BackgroundJob job;
        private BackgroundJob.RunResult replacementResult;
        private int submissionCount;

        @Override
        public void execute(Runnable command) {
            if (submissionCount++ == 0) {
                command.run();
                replacementResult = job.requestRun("second");
                throw new RejectedExecutionException("first submission reported a late rejection");
            }
            // Keep the replacement claimed and queued.
        }
    }

    private static final class FakeTaskStore implements BackgroundJob.TaskStore {
        private final Map<String, Task> history = new LinkedHashMap<>();
        private final AtomicInteger historyReads = new AtomicInteger();
        private final List<String> registeredTaskNames = new ArrayList<>();
        private final List<String> startedTaskNames = new ArrayList<>();
        private final List<String> failedTaskNames = new ArrayList<>();

        synchronized void put(Task task) {
            history.put(task.name(), task);
        }

        @Override
        public synchronized int registerTasks(List<String> taskNames) {
            registeredTaskNames.addAll(taskNames);
            return taskNames.size();
        }

        @Override
        public synchronized List<Task> getAllTasks() {
            historyReads.incrementAndGet();
            return List.copyOf(history.values());
        }

        @Override
        public synchronized int startTask(String name) {
            startedTaskNames.add(name);
            return 1;
        }

        @Override
        public int endTask(String name, String error) {
            return 1;
        }

        @Override
        public synchronized int endTask(String name, Throwable error) {
            failedTaskNames.add(name);
            return 1;
        }
    }
}
