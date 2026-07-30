package demresampler.manifest;

import demresampler.model.TileAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LevelManifestTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsProcessedPresentAndHaloBits() throws Exception {
        long first = TileAddress.pack(1, 2);
        long second = TileAddress.pack(1, 3);
        Set<Long> coordinates = Set.of(first, second);

        try (LevelManifest manifest =
                 new LevelManifest(temporaryDirectory, 3, coordinates)) {
            assertEquals(0, manifest.presentCount());
            manifest.markCoreProcessed(first, true);
            manifest.markCoreProcessed(second, false);
            manifest.markHaloComplete(first);
        }

        try (LevelManifest manifest =
                 new LevelManifest(temporaryDirectory, 3, coordinates)) {
            assertEquals(1, manifest.presentCount());
            assertArrayEquals(new long[0], manifest.unprocessedCoordinates());
            assertArrayEquals(new long[0], manifest.incompleteHaloCoordinates());
            assertEquals(Set.of(first), manifest.presentCoordinateSet());
        }
    }

    @Test
    void discardsTruncatedStateAndWritesAValidReplacement() throws Exception {
        long coordinate = TileAddress.pack(1, 2);
        Set<Long> coordinates = Set.of(coordinate);
        try (LevelManifest ignored =
                 new LevelManifest(temporaryDirectory, 3, coordinates)) {
            // Create the initial coordinate and state files.
        }
        Files.write(
            temporaryDirectory.resolve("level-3-state.bin"),
            new byte[] {1, 2, 3});

        try (LevelManifest manifest =
                 new LevelManifest(temporaryDirectory, 3, coordinates)) {
            assertArrayEquals(new long[] {coordinate}, manifest.unprocessedCoordinates());
        }

        try (LevelManifest manifest =
                 new LevelManifest(temporaryDirectory, 3, coordinates)) {
            assertArrayEquals(new long[] {coordinate}, manifest.unprocessedCoordinates());
        }
    }

    @Test
    void coordinateChangeResetsLevelState() throws Exception {
        long first = TileAddress.pack(1, 2);
        try (LevelManifest manifest =
                 new LevelManifest(temporaryDirectory, 3, Set.of(first))) {
            manifest.markCoreProcessed(first, true);
        }

        long second = TileAddress.pack(1, 3);
        try (LevelManifest manifest =
                 new LevelManifest(temporaryDirectory, 3, Set.of(second))) {
            assertEquals(0, manifest.presentCount());
            assertArrayEquals(new long[] {second}, manifest.unprocessedCoordinates());
        }
    }
}
