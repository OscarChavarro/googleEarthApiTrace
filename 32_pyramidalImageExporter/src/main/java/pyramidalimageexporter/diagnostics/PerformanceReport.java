package pyramidalimageexporter.diagnostics;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

public final class PerformanceReport {
    public static final Path REPORT_PATH = Path.of("/media/ramdisk/pyramidalImageExporterPerformanceReport.log");

    private static final Map<String, Metric> METRICS = new ConcurrentHashMap<>();
    private static volatile long startNanos;
    private static volatile Instant startInstant;
    private static volatile String commandLine = "";

    private PerformanceReport() {
    }

    public static void start(String[] args) {
        METRICS.clear();
        startNanos = System.nanoTime();
        startInstant = Instant.now();
        commandLine = args == null ? "" : String.join(" ", Arrays.asList(args));
    }

    public static <T> T time(String name, Supplier<T> supplier) {
        long before = System.nanoTime();
        try {
            return supplier.get();
        }
        finally {
            addNanos(name, System.nanoTime() - before);
        }
    }

    public static void time(String name, Runnable runnable) {
        long before = System.nanoTime();
        try {
            runnable.run();
        }
        finally {
            addNanos(name, System.nanoTime() - before);
        }
    }

    public static void addNanos(String name, long nanos) {
        if (name == null || name.isBlank()) {
            return;
        }
        Metric metric = METRICS.computeIfAbsent(name, ignored -> new Metric());
        metric.nanos.add(Math.max(0L, nanos));
        metric.calls.increment();
    }

    public static void increment(String name) {
        incrementBy(name, 1L);
    }

    public static void incrementBy(String name, long amount) {
        if (name == null || name.isBlank() || amount == 0L) {
            return;
        }
        METRICS.computeIfAbsent(name, ignored -> new Metric()).units.add(amount);
    }

    public static long finishAndWrite(Throwable failure) {
        long elapsedNanos = Math.max(0L, System.nanoTime() - startNanos);
        write(elapsedNanos, failure);
        return elapsedNanos;
    }

    public static String formatDuration(long nanos) {
        long millis = nanos / 1_000_000L;
        long minutes = millis / 60_000L;
        long seconds = (millis % 60_000L) / 1_000L;
        long remainderMillis = millis % 1_000L;
        return minutes + "m " + seconds + "." + String.format("%03d", remainderMillis) + "s";
    }

    private static void write(long elapsedNanos, Throwable failure) {
        try {
            Path parent = REPORT_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(
                REPORT_PATH,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            ))) {
                out.println("============================================================");
                out.println("pyramidalImageExporter performance report");
                out.println("start: " + startInstant);
                out.println("elapsed: " + formatDuration(elapsedNanos) + " (" + elapsedNanos + " ns)");
                out.println("args: " + commandLine);
                out.println("status: " + (failure == null ? "OK" : "FAILED"));
                if (failure != null) {
                    out.println("failure: " + failure);
                    StringWriter stack = new StringWriter();
                    failure.printStackTrace(new PrintWriter(stack));
                    out.println(stack);
                }
                out.println();
                out.println("metric\tcalls\ttime_ms\tunits\tavg_ms_per_call");
                METRICS.entrySet().stream()
                    .sorted(Comparator
                        .<Map.Entry<String, Metric>>comparingLong(entry -> entry.getValue().nanos.sum())
                        .reversed()
                        .thenComparing(Map.Entry::getKey))
                    .forEach(entry -> {
                        long calls = entry.getValue().calls.sum();
                        long nanos = entry.getValue().nanos.sum();
                        double avgMs = calls == 0L ? 0.0 : nanos / 1_000_000.0 / calls;
                        out.printf(
                            "%s\t%d\t%.3f\t%d\t%.3f%n",
                            entry.getKey(),
                            calls,
                            nanos / 1_000_000.0,
                            entry.getValue().units.sum(),
                            avgMs
                        );
                    });
                out.println();
            }
        }
        catch (IOException | RuntimeException ex) {
            System.out.println(
                "pyramidalImageExporter: could not write performance report to "
                    + REPORT_PATH + ": " + ex.getMessage()
            );
        }
    }

    private static final class Metric {
        private final LongAdder calls = new LongAdder();
        private final LongAdder nanos = new LongAdder();
        private final LongAdder units = new LongAdder();
    }
}
