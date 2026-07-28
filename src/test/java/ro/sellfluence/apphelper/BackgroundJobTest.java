package ro.sellfluence.apphelper;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static ro.sellfluence.apphelper.BackgroundJob.RunStatus.ACCEPTED;
import static ro.sellfluence.apphelper.BackgroundJob.RunStatus.BUSY;

class BackgroundJobTest {

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
}
