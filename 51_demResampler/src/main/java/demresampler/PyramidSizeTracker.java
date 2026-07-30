package demresampler;

import demresampler.io.RawTileIO;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Maintains a logical output inventory without walking the generated file tree.
 */
final class PyramidSizeTracker implements AutoCloseable {
    static final String REPORT_FILE = "pyramid-size.txt";

    private static final long REPORT_INTERVAL_SECONDS = 30;

    private final Path report;
    private final AtomicLong tileFiles = new AtomicLong();
    private final ScheduledExecutorService reporter;
    private volatile String stage = "initializing";
    private volatile boolean complete;

    PyramidSizeTracker(Path outputRoot) {
        report = outputRoot.resolve(REPORT_FILE);
        reporter = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pyramid-size-reporter");
            thread.setDaemon(true);
            return thread;
        });
        reporter.scheduleAtFixedRate(
            () -> print("Pyramid output"),
            REPORT_INTERVAL_SECONDS,
            REPORT_INTERVAL_SECONDS,
            TimeUnit.SECONDS);
    }

    void setStage(String value) {
        stage = value;
    }

    void recordTileFile() {
        tileFiles.incrementAndGet();
    }

    void recordTileFiles(long count) {
        tileFiles.addAndGet(count);
    }

    long tileFiles() {
        return tileFiles.get();
    }

    void checkpoint(String completedStage) {
        stage = completedStage;
        print("Pyramid checkpoint");
        writeReport();
    }

    void markComplete() {
        complete = true;
        stage = "complete";
        print("Pyramid inventory complete");
        writeReport();
    }

    private void print(String prefix) {
        long count = tileFiles();
        long logicalBytes = Math.multiplyExact(count, (long) RawTileIO.BYTE_SIZE);
        System.out.printf(
            "%s [%s]: %,d tile files, %,d logical bytes (%s)%n",
            prefix,
            stage,
            count,
            logicalBytes,
            humanBytes(logicalBytes));
    }

    private void writeReport() {
        long count = tileFiles();
        long logicalBytes = Math.multiplyExact(count, (long) RawTileIO.BYTE_SIZE);
        List<String> lines = List.of(
            "status=" + (complete ? "complete" : "running"),
            "stage=" + stage,
            "tileFiles=" + count,
            "bytesPerTile=" + RawTileIO.BYTE_SIZE,
            "logicalTileBytes=" + logicalBytes,
            "logicalTileSize=" + humanBytes(logicalBytes),
            "updatedAt=" + Instant.now()
        );
        Path temporary = report.resolveSibling(report.getFileName() + ".tmp");
        try {
            Files.write(temporary, lines);
            try {
                Files.move(
                    temporary,
                    report,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, report, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            System.err.println(
                "WARNING: could not update pyramid size report " + report + ": "
                    + exception.getMessage());
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    @Override
    public void close() {
        reporter.shutdownNow();
    }
}
