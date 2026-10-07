package com.ledgerlab.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** Runs tasks on separate threads released at the same instant to maximize contention. */
public final class Concurrently {

    private Concurrently() {}

    public sealed interface Outcome<T> {
        record Success<T>(T value) implements Outcome<T> {}

        record Failure<T>(Throwable error) implements Outcome<T> {}
    }

    public static <T> List<Outcome<T>> run(int threads, IntFunction<Callable<T>> taskFactory) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<T> task = taskFactory.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome.Success<>(task.call());
                    } catch (Throwable t) {
                        return new Outcome.Failure<>(t);
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    public static <T> List<T> successes(List<Outcome<T>> outcomes) {
        return outcomes.stream()
                .filter(o -> o instanceof Outcome.Success<T>)
                .map(o -> ((Outcome.Success<T>) o).value())
                .toList();
    }

    public static <T> List<Throwable> failures(List<Outcome<T>> outcomes) {
        return outcomes.stream()
                .filter(o -> o instanceof Outcome.Failure<T>)
                .map(o -> ((Outcome.Failure<T>) o).error())
                .toList();
    }
}
