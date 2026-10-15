package ro.sellfluence.apphelper;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.Task;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    void ownedWorkersRunAllLanesConcurrentlyAndTerminateOnShutdown() throws Exception {
        var entered = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var completed = new AtomicInteger();
        var workerThreads = new ConcurrentLinkedQueue<Thread>();
        BackgroundJob.CheckedAction action = () -> {
            workerThreads.add(Thread.currentThread());
            waitInTask(entered, release);
            completed.incrementAndGet();
        };
        var job = new BackgroundJob(new FakeTaskStore(), clockAt(NOW), List.of(
                task("orders", "emag", action),
                task("more-orders", "emag", action),
                task("sheets", "google", action),
                task("campaigns", "ads", action)
        ));

        try {
            job.performWork();

            assertTrue(entered.await(2, TimeUnit.SECONDS), "Every lane should have a worker available");
            assertEquals(BUSY, job.requestRun("more-orders").status());
        } finally {
            release.countDown();
            job.shutdown();
        }

        assertEquals(3, completed.get(), "Shutdown should wait for accepted tasks to finish");
        assertEquals(SHUTTING_DOWN, job.requestRun("orders").status());
        for (var thread : workerThreads) {
            thread.join(2_000);
            assertFalse(thread.isAlive(), "Owned workers should terminate on shutdown");
        }
    }

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
            job.shutdown();
            boolean callerExecutorWasShutDown = executor.isShutdown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            assertFalse(callerExecutorWasShutDown, "Injected executors should remain owned by their caller");
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
    void retriesAFailedTaskFromItsStartRatherThanItsCompletion() {
        var definition = scheduledTask("ads", "ads:sellfusion", Duration.ofDays(1), Duration.ofHours(1), null);
        var store = new FakeTaskStore();
        store.put(completedAttempt("ads", NOW.minusMinutes(59), NOW.minusMinutes(54), "timeout"));
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
    void registersEveryDiscoveredDashboardAccountOnceWithOffersFirstAndAdsPrerequisites() {
        var definitions = BackgroundJob.dashboardTaskDefinitions(null, clockAt(NOW),
                List.of("sellfusion", "another-account", "third.account", "sellfusion"));

        assertEquals(18, definitions.size());
        assertEquals(18, definitions.stream().map(BackgroundJob.TaskDefinition::name).distinct().count());
        for (var alias : List.of("sellfusion", "another-account", "third.account")) {
            var laneTasks = definitions.stream()
                    .filter(task -> task.lane().equals(BackgroundJob.emagDashboardLane + ":" + alias))
                    .toList();
            assertEquals(6, laneTasks.size());
            assertEquals("Fetch offers for " + alias, laneTasks.getFirst().name());
            assertNull(laneTasks.getFirst().prerequisiteTaskName());
            assertEquals(Duration.ofHours(1), laneTasks.getFirst().interval());
            assertEquals(Duration.ofHours(1), laneTasks.getFirst().failureRetryInterval());
            assertEquals(BackgroundJob.adsCampaignsTaskName(alias), laneTasks.get(1).name());
            assertNull(laneTasks.get(1).prerequisiteTaskName());
            for (var detail : laneTasks.subList(2, 5)) {
                assertEquals(laneTasks.get(1).name(), detail.prerequisiteTaskName());
            }
        }
        assertTrue(BackgroundJob.dashboardTaskDefinitions(null, clockAt(NOW), List.of()).isEmpty());
    }

    @Test
    void offersAreEligibleAllDayButWaitAnHourFromStartAfterSuccessOrFailure() {
        var definition = BackgroundJob.offersTask("sellfusion", () -> {});
        for (var hour : List.of(0, 7, 14, 23)) {
            var now = NOW.withHour(hour);
            var firstRun = new HoldingExecutor();
            new BackgroundJob(new FakeTaskStore(), firstRun, clockAt(now), List.of(definition)).performWork();
            assertEquals(1, firstRun.queuedCount(), "Offers should be eligible at hour " + hour);

            for (var error : List.of("", "login failed")) {
                var store = new FakeTaskStore();
                var lastAttempt = now.minusMinutes(59);
                store.put(completedAttempt(definition.name(), lastAttempt, lastAttempt.plusMinutes(5), error));
                var tooEarly = new HoldingExecutor();
                new BackgroundJob(store, tooEarly, clockAt(now), List.of(definition)).performWork();
                assertEquals(0, tooEarly.queuedCount());

                var due = new HoldingExecutor();
                new BackgroundJob(store, due, clockAt(now.plusMinutes(1)), List.of(definition)).performWork();
                assertEquals(1, due.queuedCount());
            }
        }
    }

    @Test
    void reviewsWaitADayFromStartAndRetryFailuresAfterAnHourWithinTheEveningWindow() {
        var definition = BackgroundJob.reviewsTask(() -> {});
        assertEquals("Fetch product reviews from eMAG", definition.name());
        assertEquals(BackgroundJob.emagReviewsLane, definition.lane());
        assertEquals(Duration.ofDays(1), definition.interval());
        assertEquals(Duration.ofHours(1), definition.failureRetryInterval());
        assertNull(definition.prerequisiteTaskName());

        for (var time : List.of(NOW.withHour(19).withMinute(0), NOW.withHour(23).withMinute(59))) {
            var executor = new HoldingExecutor();
            new BackgroundJob(new FakeTaskStore(), executor, clockAt(time), List.of(definition)).performWork();
            assertEquals(1, executor.queuedCount(), "Reviews should be eligible at hour " + time.getHour());
        }
        for (var time : List.of(NOW.withHour(0), NOW.withHour(6).withMinute(59),
                NOW.withHour(18).withMinute(59))) {
            var executor = new HoldingExecutor();
            new BackgroundJob(new FakeTaskStore(), executor, clockAt(time), List.of(definition)).performWork();
            assertEquals(0, executor.queuedCount(), "Reviews should be outside the window at " + time);
        }

        var now = NOW.withHour(19);
        var successfulStore = new FakeTaskStore();
        successfulStore.put(completedTask(definition.name(), now.minusMinutes(1), now.minusMinutes(1), ""));
        var tooEarly = new HoldingExecutor();
        new BackgroundJob(successfulStore, tooEarly, clockAt(now), List.of(definition)).performWork();
        assertEquals(0, tooEarly.queuedCount());

        // Finishing after midnight does not change the interval's start-time anchor.
        var followingEvening = now.plusDays(1);
        var overnight = completedAttempt(definition.name(), now.withHour(23).withMinute(30),
                followingEvening.withHour(0).withMinute(30), "");
        assertEligibleAt(definition, overnight, followingEvening, false);
        assertEligibleAt(definition, overnight, followingEvening.withHour(23).withMinute(30), true);

        var failedStore = new FakeTaskStore();
        failedStore.put(completedAttempt(definition.name(), now.minusMinutes(59), now.minusMinutes(54), "HTTP error"));
        var retryTooEarly = new HoldingExecutor();
        new BackgroundJob(failedStore, retryTooEarly, clockAt(now), List.of(definition)).performWork();
        assertEquals(0, retryTooEarly.queuedCount());
        var retryDue = new HoldingExecutor();
        new BackgroundJob(failedStore, retryDue, clockAt(now.plusMinutes(1)), List.of(definition)).performWork();
        assertEquals(1, retryDue.queuedCount());
    }

    @Test
    void reviewTransferWaitsADayFromStartAndRetriesWithinTheMorningWindow() {
        var definition = BackgroundJob.transferReviewsTask(() -> {});
        assertEquals("Transfer product reviews to Google Sheets", definition.name());
        assertEquals(BackgroundJob.googleApiLane, definition.lane());
        assertEquals(Duration.ofDays(1), definition.interval());
        assertEquals(Duration.ofHours(1), definition.failureRetryInterval());
        assertNull(definition.prerequisiteTaskName());

        for (var time : List.of(NOW.withHour(0), NOW.withHour(6).withMinute(59))) {
            var executor = new HoldingExecutor();
            new BackgroundJob(new FakeTaskStore(), executor, clockAt(time), List.of(definition)).performWork();
            assertEquals(1, executor.queuedCount(), "Transfer should be eligible at " + time);
        }
        for (var time : List.of(NOW.withHour(7), NOW.withHour(23).withMinute(59))) {
            var executor = new HoldingExecutor();
            new BackgroundJob(new FakeTaskStore(), executor, clockAt(time), List.of(definition)).performWork();
            assertEquals(0, executor.queuedCount(), "Transfer should be outside the window at " + time);
        }

        var morning = NOW.withHour(0);
        var store = new FakeTaskStore();
        store.put(completedAttempt(definition.name(), morning.minusDays(1).withHour(6).withMinute(59),
                morning.minusDays(1).withHour(7).withMinute(30), ""));
        var nextDay = new HoldingExecutor();
        new BackgroundJob(store, nextDay, clockAt(morning), List.of(definition)).performWork();
        assertEquals(0, nextDay.queuedCount(), "Midnight does not make a daily task eligible");
        var dueAt = new HoldingExecutor();
        new BackgroundJob(store, dueAt, clockAt(morning.withHour(6).withMinute(59)), List.of(definition)).performWork();
        assertEquals(1, dueAt.queuedCount(), "The next attempt is due a day after the prior start");

        store.put(completedAttempt(definition.name(), morning, morning.plusHours(1), ""));
        var sameDay = new HoldingExecutor();
        new BackgroundJob(store, sameDay, clockAt(morning.plusHours(6)), List.of(definition)).performWork();
        assertEquals(0, sameDay.queuedCount());

        store.put(completedAttempt(definition.name(), morning, morning.plusMinutes(5), "HTTP error"));
        var tooEarly = new HoldingExecutor();
        new BackgroundJob(store, tooEarly, clockAt(morning.plusMinutes(59)), List.of(definition)).performWork();
        assertEquals(0, tooEarly.queuedCount());
        var due = new HoldingExecutor();
        new BackgroundJob(store, due, clockAt(morning.plusHours(1)), List.of(definition)).performWork();
        assertEquals(1, due.queuedCount());
    }

    @Test
    void reviewTransferHasPriorityInGoogleLaneAndManualRunBypassesItsWindow() {
        var transfer = BackgroundJob.transferReviewsTask(() -> {});
        var hourly = task("hourly sheets", BackgroundJob.googleApiLane, () -> {});
        var job = new BackgroundJob(new FakeTaskStore(), new HoldingExecutor(), clockAt(NOW.withHour(0)),
                List.of(transfer, hourly));

        job.performWork();
        assertEquals(transfer.name(), activeTasks(job).get(BackgroundJob.googleApiLane));
        assertEquals(BUSY, job.requestRun(hourly.name()).status());

        var manualStore = new FakeTaskStore();
        manualStore.put(completedTask(transfer.name(), NOW.withHour(1), NOW.withHour(1), ""));
        var manual = new BackgroundJob(manualStore, new HoldingExecutor(), clockAt(NOW.withHour(12)),
                List.of(transfer));
        manual.setTaskPaused(transfer.name(), true);
        assertEquals(ACCEPTED, manual.requestRun(transfer.name()).status());
    }

    @Test
    void reviewsHaveAnIndependentLaneAndHonorPauseAndManualRunControls() {
        var reviewTask = BackgroundJob.reviewsTask(() -> {});
        var job = new BackgroundJob(new FakeTaskStore(), new HoldingExecutor(), clockAt(NOW.withHour(3)), List.of(
                reviewTask,
                task("orders", BackgroundJob.emagApiLane, () -> {}),
                task("sheets", BackgroundJob.googleApiLane, () -> {}),
                BackgroundJob.offersTask("sellfusion", () -> {})
        ));
        assertEquals(BackgroundJob.PauseResult.UPDATED, job.setTaskPaused(reviewTask.name(), true));

        job.performWork();
        assertEquals(3, activeTasks(job).size());
        assertFalse(activeTasks(job).containsKey(BackgroundJob.emagReviewsLane));
        assertEquals(ACCEPTED, job.requestRun(reviewTask.name()).status());
        assertEquals(4, activeTasks(job).size());
        assertEquals(reviewTask.name(), activeTasks(job).get(BackgroundJob.emagReviewsLane));
        assertEquals(BUSY, job.requestRun(reviewTask.name()).status());
    }

    @Test
    void dueOffersTakePriorityOverAdsAndShareTheirLane() {
        var now = NOW.withHour(3);
        var definitions = BackgroundJob.dashboardTaskDefinitions(null, clockAt(now), List.of("sellfusion", "second"));
        var job = new BackgroundJob(new FakeTaskStore(), new HoldingExecutor(), clockAt(now), definitions);

        job.performWork();

        assertEquals(Map.of(
                BackgroundJob.emagDashboardLane + ":sellfusion", "Fetch offers for sellfusion",
                BackgroundJob.emagDashboardLane + ":second", "Fetch offers for second"
        ), activeTasks(job));
        var blocked = job.requestRun(BackgroundJob.adsCampaignsTaskName("sellfusion"));
        assertEquals(BUSY, blocked.status());
        assertEquals("Fetch offers for sellfusion", blocked.blockingTaskName());
    }

    @Test
    void adsCanRunBetweenOffersFetchesAndPreventAnotherLoginForTheSameAccount() {
        var now = NOW.withHour(3);
        var definitions = BackgroundJob.dashboardTaskDefinitions(null, clockAt(now), List.of("sellfusion", "second"));
        var store = new FakeTaskStore();
        for (var alias : List.of("sellfusion", "second")) {
            store.put(completedTask("Fetch offers for " + alias, now.minusMinutes(10), now.minusMinutes(10), ""));
        }
        var job = new BackgroundJob(store, new HoldingExecutor(), clockAt(now), definitions);

        assertEquals(ACCEPTED, job.requestRun(BackgroundJob.adsCampaignsTaskName("sellfusion")).status());
        var blocked = job.requestRun("Fetch offers for sellfusion");
        assertEquals(BUSY, blocked.status());
        assertEquals(BackgroundJob.adsCampaignsTaskName("sellfusion"), blocked.blockingTaskName());
        assertEquals(ACCEPTED, job.requestRun("Fetch offers for second").status());

        var automatic = new BackgroundJob(store, new HoldingExecutor(), clockAt(now), definitions);
        automatic.performWork();
        assertEquals(BackgroundJob.adsCampaignsTaskName("sellfusion"),
                activeTasks(automatic).get(BackgroundJob.emagDashboardLane + ":sellfusion"));
    }

    @Test
    void failedOffersDoNotBlockAdsDuringTheRetryDelay() {
        var now = NOW.withHour(3);
        var store = new FakeTaskStore();
        store.put(completedTask("Fetch offers for sellfusion", now.minusDays(1), now.minusMinutes(10), "timeout"));
        var job = new BackgroundJob(store, new HoldingExecutor(), clockAt(now),
                BackgroundJob.dashboardTaskDefinitions(null, clockAt(now), List.of("sellfusion")));

        job.performWork();

        assertEquals(BackgroundJob.adsCampaignsTaskName("sellfusion"),
                activeTasks(job).get(BackgroundJob.emagDashboardLane + ":sellfusion"));
    }

    @Test
    void oneAdsAccountFailureDoesNotPreventAnotherAccountsTasks() {
        var store = new FakeTaskStore();
        var executor = new HoldingExecutor();
        var secondRuns = new AtomicInteger();
        var job = new BackgroundJob(store, executor, clockAt(NOW), List.of(
                task("first", "ads:first", () -> { throw new IllegalStateException("First account failed"); }),
                task("second", "ads:second", secondRuns::incrementAndGet)
        ));

        job.performWork();
        executor.runAll();

        assertEquals(1, secondRuns.get());
        assertTrue(activeTasks(job).isEmpty());
    }

    @Test
    void equalSuccessAndFailureIntervalsUseTheSameStartTimeBoundary() {
        for (var interval : List.of(Duration.ofHours(1), Duration.ofDays(1), Duration.ofDays(7))) {
            var definition = scheduledTask("task", "lane", interval, interval, null);
            for (var error : List.of("", "timeout")) {
                var history = completedAttempt("task", NOW, NOW.plusMinutes(5), error);
                var due = NOW.plus(interval);
                assertEligibleAt(definition, history, due.minusNanos(1), false);
                assertEligibleAt(definition, history, due, true);
            }
        }
    }

    @Test
    void latestOutcomeSelectsTheIntervalAndEachRetryResetsTheStartTime() {
        var definition = scheduledTask("task", "lane", Duration.ofDays(1), Duration.ofHours(1), null);
        var start = NOW.withHour(6);
        var success = completedAttempt("task", start, start.plusMinutes(5), "");
        assertEligibleAt(definition, success, start.plusHours(1), false);
        assertEligibleAt(definition, success, start.plusDays(1).minusNanos(1), false);
        assertEligibleAt(definition, success, start.plusDays(1), true);

        var failed = completedAttempt("task", start, start.plusMinutes(5), "timeout");
        assertEligibleAt(definition, failed, start.plusHours(1).minusNanos(1), false);
        assertEligibleAt(definition, failed, start.plusHours(1), true);

        var retry = completedAttempt("task", start.plusHours(1), start.plusHours(1).plusMinutes(5), "timeout again");
        assertEligibleAt(definition, retry, start.plusHours(2).minusNanos(1), false);
        assertEligibleAt(definition, retry, start.plusHours(2), true);

        var recovered = completedAttempt("task", start.plusHours(2), start.plusHours(2).plusMinutes(5), "");
        assertEligibleAt(definition, recovered, start.plusHours(3), false);
        assertEligibleAt(definition, recovered, start.plusHours(2).plusDays(1), true);
    }

    @Test
    void omittedFailureIntervalDefaultsToTheNormalInterval() {
        for (var interval : List.of(Duration.ofHours(1), Duration.ofDays(1), Duration.ofDays(7))) {
            var definition = new BackgroundJob.TaskDefinition("task", "lane", interval, _ -> true, () -> {});
            assertEquals(interval, definition.failureRetryInterval());
            var history = completedAttempt("task", NOW, NOW.plusMinutes(5), "timeout");
            assertEligibleAt(definition, history, NOW.plus(interval).minusNanos(1), false);
            assertEligibleAt(definition, history, NOW.plus(interval), true);
        }
    }

    @Test
    void predicateDefersADueRetryUntilTheNextAllowedWindow() {
        var definition = new BackgroundJob.TaskDefinition("task", "lane", Duration.ofDays(1), Duration.ofHours(1),
                time -> time.getHour() < 6 || (time.getHour() == 6 && time.getMinute() < 30) || time.getHour() >= 18,
                null, () -> {});
        var start = NOW.withHour(6);
        var failed = completedAttempt("task", start, start.plusMinutes(5), "timeout");
        assertEligibleAt(definition, failed, start.plusMinutes(29), false);
        assertEligibleAt(definition, failed, start.plusHours(1), false);
        assertEligibleAt(definition, failed, start.withHour(18).minusNanos(1), false);
        assertEligibleAt(definition, failed, start.withHour(18), true);
    }

    @Test
    void attemptsLongerThanTheirIntervalBecomeEligibleOnlyAfterCompletion() {
        var definition = scheduledTask("task", "lane", Duration.ofHours(1), Duration.ofHours(1), null);
        var running = new Task("task", NOW, null, null, null, null, null, 0, null);
        assertEligibleAt(definition, running, NOW.plusHours(2), false);
        for (var error : List.of("", "timeout")) {
            var completed = completedAttempt("task", NOW, NOW.plusHours(2), error);
            assertEligibleAt(definition, completed, NOW.plusHours(2), true);
        }
    }

    @Test
    void missingStartHistoryAllowsSchedulingEvenWithPreviousSuccessOrFailure() {
        var definition = scheduledTask("task", "lane", Duration.ofDays(1), Duration.ofHours(1), null);
        for (var error : List.of("", "timeout")) {
            var history = new Task("task", null, NOW, NOW, NOW.minusMinutes(5),
                    Duration.ofMinutes(5), null, error.isEmpty() ? 0 : 1, error);
            assertEligibleAt(definition, history, NOW, true);
        }
    }

    private static void assertEligibleAt(BackgroundJob.TaskDefinition definition, Task history,
                                         LocalDateTime now, boolean eligible) {
        var store = new FakeTaskStore();
        store.put(history);
        var executor = new HoldingExecutor();
        new BackgroundJob(store, executor, clockAt(now), List.of(definition)).performWork();
        assertEquals(eligible ? 1 : 0, executor.queuedCount(),
                "Eligibility at " + now + " for " + history);
    }

    private static Task completedAttempt(String name, LocalDateTime started, LocalDateTime terminated, String error) {
        var successful = error.isEmpty();
        return new Task(name, started, terminated,
                successful ? terminated : started.minusDays(7),
                successful ? started : started.minusDays(7).minusMinutes(5),
                Duration.between(started, terminated), null, successful ? 0 : 1, error);
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
        return completedTask(name, lastSuccessfulRun, lastSuccessfulRun, terminated, error);
    }

    private static Task completedTask(
            String name,
            LocalDateTime lastSuccessfulRun,
            LocalDateTime lastSuccessfulStart,
            LocalDateTime terminated,
            String error
    ) {
        return new Task(
                name,
                terminated.minusMinutes(5),
                terminated,
                lastSuccessfulRun,
                lastSuccessfulStart,
                Duration.ofMinutes(5),
                null,
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
