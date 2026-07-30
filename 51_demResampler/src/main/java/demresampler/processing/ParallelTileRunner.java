package demresampler.processing;

import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorConsumer;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorEvent;
import vsdk.toolkit.gui.feedback.parallel.ParallelProgressMonitorProducer;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ParallelTileRunner {
    private ParallelTileRunner() {
    }

    public static <C extends AutoCloseable> Set<Long> run(
        String stage,
        long[] items,
        int threadCount,
        ContextFactory<C> contextFactory,
        TileOperation<C> operation
    ) throws Exception {
        return run(stage, items, threadCount, contextFactory, operation, () -> {
        });
    }

    public static <C extends AutoCloseable> Set<Long> run(
        String stage,
        long[] items,
        int threadCount,
        ContextFactory<C> contextFactory,
        TileOperation<C> operation,
        Runnable generatedTileCallback
    ) throws Exception {
        return execute(
            stage,
            items,
            threadCount,
            contextFactory,
            operation,
            true,
            generatedTileCallback);
    }

    public static <C extends AutoCloseable> void runAll(
        String stage,
        long[] items,
        int threadCount,
        ContextFactory<C> contextFactory,
        TileAction<C> action
    ) throws Exception {
        execute(
            stage,
            items,
            threadCount,
            contextFactory,
            (context, packed) -> {
                action.process(context, packed);
                return false;
            },
            false,
            () -> {
            });
    }

    private static <C extends AutoCloseable> Set<Long> execute(
        String stage,
        long[] items,
        int threadCount,
        ContextFactory<C> contextFactory,
        TileOperation<C> operation,
        boolean collectGenerated,
        Runnable generatedTileCallback
    ) throws Exception {
        System.out.printf(
            "%s: preparing %,d candidate tiles for %d workers%n",
            stage,
            items.length,
            threadCount);
        Arrays.sort(items);
        System.out.printf("%s: processing %,d candidate tiles%n", stage, items.length);
        if (items.length == 0) {
            return Set.of();
        }

        ConcurrentLinkedQueue<ParallelProgressMonitorEvent> events = new ConcurrentLinkedQueue<>();
        ParallelProgressMonitorProducer progress = new ParallelProgressMonitorProducer(events);
        ParallelProgressMonitorConsumer consumer = new ParallelProgressMonitorConsumer(events);
        Thread progressThread = new Thread(consumer, stage + "-progress");
        progress.init(items.length);
        progressThread.start();

        Set<Long> generated = collectGenerated ? ConcurrentHashMap.newKeySet() : Set.of();
        AtomicInteger next = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName(stage + "-worker-" + thread.getId());
            return thread;
        });
        try {
            @SuppressWarnings("unchecked")
            Future<?>[] futures = new Future<?>[threadCount];
            for (int worker = 0; worker < threadCount; worker++) {
                futures[worker] = executor.submit(() -> {
                    try (C context = contextFactory.create()) {
                        while (failure.get() == null) {
                            int index = next.getAndIncrement();
                            if (index >= items.length) {
                                break;
                            }
                            long item = items[index];
                            try {
                                if (operation.process(context, item)
                                    && collectGenerated
                                    && generated.add(item)) {
                                    generatedTileCallback.run();
                                }
                            } finally {
                                // A completed work unit also includes operations that
                                // return immediately because the output already exists.
                                progress.update(0, 1, 1);
                            }
                        }
                    } catch (Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                    }
                });
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
            progress.finish();
            progressThread.join();
        }

        Throwable throwable = failure.get();
        if (throwable != null) {
            if (throwable instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(throwable);
        }
        if (collectGenerated) {
            System.out.printf("%s: %,d non-empty tiles%n", stage, generated.size());
        }
        return generated;
    }

    @FunctionalInterface
    public interface ContextFactory<C extends AutoCloseable> {
        C create() throws Exception;
    }

    @FunctionalInterface
    public interface TileOperation<C extends AutoCloseable> {
        boolean process(C context, long packedCoordinates) throws Exception;
    }

    @FunctionalInterface
    public interface TileAction<C extends AutoCloseable> {
        void process(C context, long packedCoordinates) throws Exception;
    }
}
