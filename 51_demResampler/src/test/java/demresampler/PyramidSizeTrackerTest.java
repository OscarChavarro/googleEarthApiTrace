package demresampler;

import demresampler.io.RawTileIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PyramidSizeTrackerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesRunningAndCompleteLogicalInventories() throws Exception {
        Path report = temporaryDirectory.resolve(PyramidSizeTracker.REPORT_FILE);

        try (PyramidSizeTracker tracker = new PyramidSizeTracker(temporaryDirectory)) {
            tracker.recordTileFile();
            tracker.recordTileFile();
            tracker.checkpoint("Leaf level 12 cores complete");

            List<String> running = Files.readAllLines(report);
            assertTrue(running.contains("status=running"));
            assertTrue(running.contains("tileFiles=2"));
            assertTrue(running.contains(
                "logicalTileBytes=" + (2L * RawTileIO.BYTE_SIZE)));

            tracker.markComplete();
            List<String> complete = Files.readAllLines(report);
            assertTrue(complete.contains("status=complete"));
            assertTrue(complete.contains("stage=complete"));
        }
    }
}
