package demresampler.processing;

import demresampler.io.RawTileIO;
import demresampler.manifest.LevelManifest;
import demresampler.model.TileAddress;
import vsdk.toolkit.gui.feedback.ProgressMonitor;
import vsdk.toolkit.gui.feedback.ProgressMonitorConsoleLongFormat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class ParentLevelGenerator {
    private ParentLevelGenerator() {
    }

    public static Set<Long> generate(
        Path outputRoot,
        int parentLevel,
        int threads,
        LevelManifest manifest,
        Runnable generatedTileCallback
    ) throws Exception {
        long[] items = manifest.unprocessedCoordinates();
        ParallelTileRunner.run(
            "Generate missing parent level " + parentLevel,
            items,
            threads,
            EmptyContext::new,
            (ignored, packed) -> {
                boolean hasData = generateOneWithoutExistingCheck(
                    outputRoot,
                    TileAddress.unpack(parentLevel, packed));
                manifest.markCoreProcessed(packed, hasData);
                return hasData;
            },
            generatedTileCallback);
        manifest.checkpoint();
        return manifest.presentCoordinateSet();
    }

    public static Set<Long> generate(
        Path outputRoot,
        int parentLevel,
        Set<Long> childCoordinates,
        int threads
    ) throws Exception {
        return generate(outputRoot, parentLevel, childCoordinates, threads, () -> {
        });
    }

    public static Set<Long> generate(
        Path outputRoot,
        int parentLevel,
        Set<Long> childCoordinates,
        int threads,
        Runnable generatedTileCallback
    ) throws Exception {
        Set<Long> parents = parentCoordinates(parentLevel, childCoordinates);
        long[] items = parents.stream().mapToLong(Long::longValue).toArray();
        return ParallelTileRunner.run(
            "Parent level " + parentLevel,
            items,
            threads,
            EmptyContext::new,
            (ignored, packed) -> generateOne(
                outputRoot, TileAddress.unpack(parentLevel, packed)),
            generatedTileCallback
        );
    }

    public static Set<Long> parentCoordinates(
        int parentLevel,
        Set<Long> childCoordinates
    ) {
        System.out.printf(
            "Enumerating level %d parents from %,d child tiles:%n",
            parentLevel,
            childCoordinates.size());
        ProgressMonitor progress = new ProgressMonitorConsoleLongFormat();
        progress.begin();
        Set<Long> parents = new HashSet<>(Math.max(16, childCoordinates.size() / 2));
        int completed = 0;
        try {
            for (long packed : childCoordinates) {
                int childRow = (int) (packed >>> 32);
                int childColumn = (int) packed;
                parents.add(TileAddress.pack(childRow >>> 1, childColumn >>> 1));
                completed++;
                progress.update(0, childCoordinates.size(), completed);
            }
        } finally {
            progress.end();
        }
        return parents;
    }

    public static short[] downsample(short[][] children) {
        if (children.length != 4) {
            throw new IllegalArgumentException("Exactly four quadrant slots are required");
        }
        short[] parent = new short[RawTileIO.CORE_SAMPLE_COUNT];
        for (int y = 0; y < RawTileIO.CORE_SIDE; y++) {
            boolean south = y >= RawTileIO.CORE_SIDE / 2;
            int childY = (y % (RawTileIO.CORE_SIDE / 2)) * 2;
            for (int x = 0; x < RawTileIO.CORE_SIDE; x++) {
                boolean east = x >= RawTileIO.CORE_SIDE / 2;
                int quadrant = south ? (east ? 1 : 0) : (east ? 2 : 3);
                short[] child = children[quadrant];
                int outputIndex = y * RawTileIO.CORE_SIDE + x;
                if (child == null) {
                    parent[outputIndex] = RawTileIO.NODATA;
                    continue;
                }
                int childX = (x % (RawTileIO.CORE_SIDE / 2)) * 2;
                parent[outputIndex] = meanValid2x2(child, childX, childY);
            }
        }
        return parent;
    }

    private static boolean generateOne(Path outputRoot, TileAddress parent)
        throws Exception {
        if (RawTileIO.isCoreComplete(outputRoot, parent)) {
            return true;
        }
        return generateOneWithoutExistingCheck(outputRoot, parent);
    }

    private static boolean generateOneWithoutExistingCheck(
        Path outputRoot,
        TileAddress parent
    ) throws Exception {
        short[][] children = new short[4][];
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            TileAddress child = parent.child(quadrant);
            if (Files.isRegularFile(child.path(outputRoot))) {
                children[quadrant] = RawTileIO.readCore(outputRoot, child);
            }
        }
        short[] samples = downsample(children);
        boolean hasData = false;
        for (short sample : samples) {
            if (sample != RawTileIO.NODATA) {
                hasData = true;
                break;
            }
        }
        if (hasData) {
            RawTileIO.writeCore(outputRoot, parent, samples);
        }
        return hasData;
    }

    private static short meanValid2x2(short[] child, int x, int y) {
        long sum = 0;
        int count = 0;
        for (int dy = 0; dy < 2; dy++) {
            int offset = (y + dy) * RawTileIO.CORE_SIDE + x;
            for (int dx = 0; dx < 2; dx++) {
                short value = child[offset + dx];
                if (value != RawTileIO.NODATA) {
                    sum += value;
                    count++;
                }
            }
        }
        return count == 0 ? RawTileIO.NODATA : (short) Math.round(sum / (double) count);
    }

    private static final class EmptyContext implements AutoCloseable {
        @Override
        public void close() {
        }
    }
}
