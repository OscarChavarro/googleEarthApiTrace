package demresampler.processing;

import demresampler.io.RawTileIO;
import demresampler.manifest.LevelManifest;
import demresampler.model.TileAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ExistingTileIndexerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void indexesExistingFilesAndLeavesMissingCandidatesUnprocessed() throws Exception {
        Path output = temporaryDirectory.resolve("output");
        Path manifests = temporaryDirectory.resolve("manifests");
        TileAddress existing = new TileAddress(3, 1, 2);
        TileAddress missing = new TileAddress(3, 1, 3);
        RawTileIO.write(output, existing, new short[RawTileIO.STORED_SAMPLE_COUNT]);
        AtomicInteger discovered = new AtomicInteger();

        try (LevelManifest manifest = new LevelManifest(
            manifests,
            3,
            Set.of(existing.packedCoordinates(), missing.packedCoordinates()))) {
            ExistingTileIndexer.index(output, manifest, discovered::incrementAndGet);

            assertEquals(1, discovered.get());
            assertEquals(Set.of(existing.packedCoordinates()), manifest.presentCoordinateSet());
            assertArrayEquals(
                new long[] {missing.packedCoordinates()},
                manifest.unprocessedCoordinates());
        }
    }
}
