package ro.sellfluence.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class ThreadDumpTest {
    @Test
    void capturesCompleteStacksAndLockOwnersWithoutDependingOnApplicationServices() throws Exception {
        Object monitor = new Object();
        var lock = new ReentrantLock();
        var ownerReady = new CountDownLatch(1);
        var releaseOwner = new CountDownLatch(1);
        var contenderStarted = new CountDownLatch(1);
        var owner = new Thread(() -> {
            synchronized (monitor) {
                lock.lock();
                try {
                    waitAtDepth(16, ownerReady, releaseOwner);
                } finally {
                    lock.unlock();
                }
            }
        }, "ThreadDumpTest-owner");
        var contender = new Thread(() -> {
            contenderStarted.countDown();
            synchronized (monitor) {
                // This thread can enter only after the capture has released the owner.
            }
        }, "ThreadDumpTest-contender");
        owner.setDaemon(true);
        contender.setDaemon(true);
        owner.start();
        try {
            assertTrue(ownerReady.await(3, TimeUnit.SECONDS));
            contender.start();
            assertTrue(contenderStarted.await(3, TimeUnit.SECONDS));
            awaitState(contender, Thread.State.BLOCKED);
            awaitState(owner, Thread.State.WAITING);

            Instant before = Instant.now();
            var snapshot = ThreadDump.capture();
            Instant after = Instant.now();
            Instant capturedAt = Instant.parse(snapshot.capturedAt());
            assertFalse(capturedAt.isBefore(before));
            assertFalse(capturedAt.isAfter(after));

            var waiting = thread(snapshot, owner);
            var blocked = thread(snapshot, contender);
            assertEquals("WAITING", waiting.state());
            assertEquals("BLOCKED", blocked.state());
            assertTrue(waiting.text().lines().filter(line -> line.contains("ThreadDumpTest.waitAtDepth")).count() >= 17,
                    "The dump must retain frames beyond ThreadInfo.toString()'s eight-frame limit");
            assertTrue(blocked.text().contains("owned by \"ThreadDumpTest-owner\" #" + owner.threadId()));
            assertTrue(blocked.text().contains("waiting to lock <java.lang.Object@"));
            if (ManagementFactory.getThreadMXBean().isObjectMonitorUsageSupported()) {
                assertTrue(waiting.text().contains("- locked <java.lang.Object@"));
            }
            if (ManagementFactory.getThreadMXBean().isSynchronizerUsageSupported()) {
                assertTrue(waiting.text().contains("Locked ownable synchronizers: 1"));
                assertTrue(waiting.text().contains("ReentrantLock$"));
            }
            assertFalse(snapshot.deadlockedThreadIds().contains(owner.threadId()));
            assertFalse(snapshot.deadlockedThreadIds().contains(contender.threadId()));
            assertTrue(snapshot.header().contains("virtual threads are not included"));
            assertTrue(snapshot.header().contains("Process ID: " + ProcessHandle.current().pid()));
            assertEquals(snapshot.threads().stream()
                    .sorted(Comparator.comparing(ThreadDump.ThreadSnapshot::name)
                            .thenComparingLong(ThreadDump.ThreadSnapshot::id))
                    .toList(), snapshot.threads());
        } finally {
            releaseOwner.countDown();
            owner.join(2_000);
            contender.join(2_000);
        }
        assertFalse(owner.isAlive());
        assertFalse(contender.isAlive());
    }

    private static ThreadDump.ThreadSnapshot thread(ThreadDump.Snapshot snapshot, Thread thread) {
        return snapshot.threads().stream().filter(candidate -> candidate.id() == thread.threadId())
                .findFirst().orElseThrow();
    }

    private static void waitAtDepth(int remaining, CountDownLatch ready, CountDownLatch release) {
        if (remaining > 0) {
            waitAtDepth(remaining - 1, ready, release);
            return;
        }
        ready.countDown();
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitState(Thread thread, Thread.State expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (thread.getState() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, thread.getState());
    }
}
