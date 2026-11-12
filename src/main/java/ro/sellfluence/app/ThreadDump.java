package ro.sellfluence.app;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Captures the current JVM's platform threads without consulting application services. */
public final class ThreadDump {
    private ThreadDump() {
    }

    public record Snapshot(String capturedAt, String header, List<ThreadSnapshot> threads,
                           List<Long> deadlockedThreadIds) {
    }

    public record ThreadSnapshot(long id, String name, String state, String text) {
    }

    public static Snapshot capture() {
        String capturedAt = Instant.now().toString();
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        boolean monitors = bean.isObjectMonitorUsageSupported();
        boolean synchronizers = bean.isSynchronizerUsageSupported();
        var deadlocks = findDeadlocks(bean, synchronizers);
        List<ThreadSnapshot> threads = Arrays.stream(bean.dumpAllThreads(monitors, synchronizers))
                .filter(info -> info != null)
                .sorted(Comparator.comparing(ThreadInfo::getThreadName).thenComparingLong(ThreadInfo::getThreadId))
                .map(info -> new ThreadSnapshot(info.getThreadId(), info.getThreadName(),
                        info.getThreadState().name(), formatThread(info, synchronizers)))
                .toList();
        var runtime = ManagementFactory.getRuntimeMXBean();
        String header = """
                Thread dump captured at: %s
                JVM: %s %s (%s)
                Process ID: %d
                Platform threads: %d (virtual threads are not included)
                Monitor lock details: %s
                Ownable synchronizer details: %s
                %s
                """.formatted(capturedAt, runtime.getVmName(), runtime.getVmVersion(), runtime.getVmVendor(),
                ProcessHandle.current().pid(), threads.size(), monitors ? "included" : "not supported by this JVM",
                synchronizers ? "included" : "not supported by this JVM", deadlocks.description());
        return new Snapshot(capturedAt, header, threads, deadlocks.ids());
    }

    private record Deadlocks(List<Long> ids, String description) {
    }

    private static Deadlocks findDeadlocks(ThreadMXBean bean, boolean synchronizers) {
        try {
            long[] ids = synchronizers ? bean.findDeadlockedThreads() : bean.findMonitorDeadlockedThreads();
            List<Long> sortedIds = ids == null ? List.of() : Arrays.stream(ids).sorted().boxed().toList();
            String scope = synchronizers ? "Deadlocks" : "Deadlocks (monitor locks only)";
            return new Deadlocks(sortedIds, scope + ": " + (sortedIds.isEmpty() ? "none detected" : sortedIds));
        } catch (UnsupportedOperationException e) {
            return new Deadlocks(List.of(), "Deadlock detection: not supported by this JVM");
        }
    }

    private static String formatThread(ThreadInfo info, boolean synchronizers) {
        var text = new StringBuilder();
        text.append('"').append(escapeName(info.getThreadName())).append("\" #").append(info.getThreadId());
        if (info.isDaemon()) {
            text.append(" daemon");
        }
        text.append(" prio=").append(info.getPriority());
        if (info.isSuspended()) {
            text.append(" (suspended)");
        }
        if (info.isInNative()) {
            text.append(" (in native)");
        }
        text.append("\n   java.lang.Thread.State: ").append(info.getThreadState()).append('\n');
        if (info.getLockInfo() != null) {
            String action = info.getThreadState() == Thread.State.BLOCKED ? "waiting to lock" : "waiting on";
            text.append("\t- ").append(action).append(" <").append(info.getLockInfo()).append('>');
            if (info.getLockOwnerId() != -1) {
                text.append(" owned by \"").append(escapeName(info.getLockOwnerName()))
                        .append("\" #").append(info.getLockOwnerId());
            }
            text.append('\n');
        }
        // ThreadInfo.toString() truncates stacks; retain every frame for diagnostics.
        StackTraceElement[] stack = info.getStackTrace();
        var monitors = info.getLockedMonitors();
        for (int depth = 0; depth < stack.length; depth++) {
            text.append("\tat ").append(stack[depth]).append('\n');
            for (var monitor : monitors) {
                if (monitor.getLockedStackDepth() == depth) {
                    text.append("\t- locked <").append(monitor).append(">\n");
                }
            }
        }
        for (var monitor : monitors) {
            if (monitor.getLockedStackDepth() < 0) {
                text.append("\t- locked <").append(monitor).append("> (stack depth unavailable)\n");
            }
        }
        if (synchronizers) {
            LockInfo[] locks = info.getLockedSynchronizers();
            text.append("\n   Locked ownable synchronizers: ").append(locks.length).append('\n');
            for (var lock : locks) {
                text.append("\t- <").append(lock).append(">\n");
            }
        }
        return text.toString();
    }

    private static String escapeName(String name) {
        return name == null ? "unknown" : name.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
