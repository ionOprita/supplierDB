package ro.sellfluence.support;

import java.util.concurrent.Callable;

public class Time {
    public static void time(String title, Runnable method) {
        System.out.printf("%s...%n", title);
        long t0 = System.currentTimeMillis();
        method.run();
        long t1 = System.currentTimeMillis();
        System.out.printf("%s took %.2f seconds to execute.%n", title, (t1 - t0) / 1000.0);
    }

    @FunctionalInterface
    public interface RunnableWithException<E extends Exception> {
        void run() throws E;
    }

    public static <E extends Exception> void timeE(String title, RunnableWithException<E> method) throws E {
        System.out.printf("%s...%n", title);
        long t0 = System.currentTimeMillis();
        method.run();
        long t1 = System.currentTimeMillis();
        System.out.printf("%s took %.2f seconds to execute.%n", title, (t1 - t0) / 1000.0);
    }
}