package ro.sellfluence.apphelper;

import org.junit.jupiter.api.Test;
import ro.sellfluence.db.Task;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ro.sellfluence.apphelper.BackgroundJob.RunStatus.ACCEPTED;
import static ro.sellfluence.apphelper.BackgroundJob.RunStatus.BUSY;
import static ro.sellfluence.apphelper.BackgroundJob.RunStatus.SHUTTING_DOWN;

class BackgroundJobTest {

    @Test
    void waitsForTheFailureRetryIntervalAfterAnUnsuccessfulRun() {
        var failedAt = LocalDateTime.of(2026, 8, 12, 10, 0);
        var failedTask = new Task(
                "Update employee sheet tabs from sheets",
                failedAt.minusMinutes(5),
                failedAt,
                failedAt.minusDays(1),
                Duration.ofMinutes(5),
                1,
                "timeout"
        );

        assertFalse(BackgroundJob.failureRetryDelayElapsed(
                failedTask,
                Duration.ofHours(1),
                failedAt.plusMinutes(59)
        ));
        assertTrue(BackgroundJob.failureRetryDelayElapsed(
                failedTask,
                Duration.ofHours(1),
                failedAt.plusHours(1)
        ));
    }

    @Test
    void recognizesTheSeparateEmployeeSheetTabUpdateTask() {
        var scheduler = new HoldingScheduler();
        try {
            var backgroundJob = new BackgroundJob(null, scheduler);

            var result = backgroundJob.requestRun("Update employee sheet tabs from sheets");

            assertEquals(ACCEPTED, result.status());
            assertEquals("Update employee sheet tabs from sheets", backgroundJob.activeTaskName());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void reportsTheTaskBlockingAnotherManualRun() {
        var scheduler = new HoldingScheduler();
        try {
            // The scheduler holds the task in its "starting" state, so the database is never accessed.
            var backgroundJob = new BackgroundJob(null, scheduler);

            var accepted = backgroundJob.requestRun("Fetch from eMAG");
            var busy = backgroundJob.requestRun("Update GMV in database");

            assertEquals(ACCEPTED, accepted.status());
            assertEquals(BUSY, busy.status());
            assertEquals("Fetch from eMAG", busy.blockingTaskName());
            assertEquals("Fetch from eMAG", backgroundJob.activeTaskName());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void releasesTheStartingStateWhenTheSchedulerRejectsTheTask() {
        var scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.shutdown();
        var backgroundJob = new BackgroundJob(null, scheduler);

        var result = backgroundJob.requestRun("Update GMV in database");

        assertEquals(SHUTTING_DOWN, result.status());
        assertNull(backgroundJob.activeTaskName());
    }

    @Test
    void releasesTheStartingStateWhenSubmissionFailsUnexpectedly() {
        var scheduler = new FailingScheduler();
        try {
            var backgroundJob = new BackgroundJob(null, scheduler);

            var result = backgroundJob.requestRun("Update GMV in database");

            assertEquals(SHUTTING_DOWN, result.status());
            assertNull(backgroundJob.activeTaskName());
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static final class HoldingScheduler extends ScheduledThreadPoolExecutor {

        private HoldingScheduler() {
            super(1);
        }

        @Override
        public void execute(Runnable command) {
            // Keep the task reserved without executing it.
        }
    }

    private static final class FailingScheduler extends ScheduledThreadPoolExecutor {

        private FailingScheduler() {
            super(1);
        }

        @Override
        public void execute(Runnable command) {
            throw new IllegalStateException("Unexpected scheduler failure");
        }
    }
}
