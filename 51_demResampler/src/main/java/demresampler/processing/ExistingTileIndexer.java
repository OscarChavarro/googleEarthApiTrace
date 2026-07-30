package demresampler.processing;

import demresampler.io.RawTileIO;
import demresampler.manifest.LevelManifest;
import demresampler.model.TileAddress;

import java.nio.file.Path;

public final class ExistingTileIndexer {
    public static final int METADATA_WORKERS = 4;

    private ExistingTileIndexer() {
    }

    public static void index(
        Path outputRoot,
        LevelManifest manifest,
        Runnable discoveredTileCallback
    ) throws Exception {
        long[] unchecked = manifest.unprocessedCoordinates();
        ParallelTileRunner.runAll(
            "Index existing level " + manifest.level(),
            unchecked,
            METADATA_WORKERS,
            EmptyContext::new,
            (ignored, packed) -> {
                TileAddress address = TileAddress.unpack(manifest.level(), packed);
                if (RawTileIO.isCoreComplete(outputRoot, address)) {
                    manifest.markCoreProcessed(packed, true);
                    discoveredTileCallback.run();
                }
            });
        manifest.checkpoint();
        System.out.printf(
            "Index existing level %d: %,d complete tile files found%n",
            manifest.level(),
            manifest.presentCount());
    }

    private static final class EmptyContext implements AutoCloseable {
        @Override
        public void close() {
        }
    }
}
