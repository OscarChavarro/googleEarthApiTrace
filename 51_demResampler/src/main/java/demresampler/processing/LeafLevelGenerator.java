package demresampler.processing;

import demresampler.gdal.GdalDataset;
import demresampler.io.RawTileIO;
import demresampler.manifest.LevelManifest;
import demresampler.model.TileAddress;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

public final class LeafLevelGenerator {
    private LeafLevelGenerator() {
    }

    public static Set<Long> generate(
        Path vrt,
        Path outputRoot,
        int level,
        int threads,
        double sourceNoData,
        LevelManifest manifest,
        Runnable generatedTileCallback
    ) throws Exception {
        long[] items = manifest.unprocessedCoordinates();
        ParallelTileRunner.run(
            "Generate missing leaf level " + level,
            items,
            threads,
            () -> GdalDataset.open(vrt),
            (dataset, packed) -> {
                boolean hasData = generateOneWithoutExistingCheck(
                    dataset,
                    outputRoot,
                    TileAddress.unpack(level, packed),
                    sourceNoData);
                manifest.markCoreProcessed(packed, hasData);
                return hasData;
            },
            generatedTileCallback);
        manifest.checkpoint();
        return manifest.presentCoordinateSet();
    }

    public static Set<Long> generate(
        Path vrt,
        Path outputRoot,
        int level,
        Set<Long> candidates,
        int threads,
        double sourceNoData
    ) throws Exception {
        return generate(
            vrt, outputRoot, level, candidates, threads, sourceNoData, () -> {
            });
    }

    public static Set<Long> generate(
        Path vrt,
        Path outputRoot,
        int level,
        Set<Long> candidates,
        int threads,
        double sourceNoData,
        Runnable generatedTileCallback
    ) throws Exception {
        long[] items = candidates.stream().mapToLong(Long::longValue).toArray();
        return ParallelTileRunner.run(
            "Leaf level " + level,
            items,
            threads,
            () -> GdalDataset.open(vrt),
            (dataset, packed) -> generateOne(
                dataset, outputRoot, TileAddress.unpack(level, packed), sourceNoData),
            generatedTileCallback
        );
    }

    private static boolean generateOne(
        GdalDataset dataset,
        Path outputRoot,
        TileAddress address,
        double sourceNoData
    ) throws Exception {
        if (RawTileIO.isCoreComplete(outputRoot, address)) {
            return true;
        }
        return generateOneWithoutExistingCheck(dataset, outputRoot, address, sourceNoData);
    }

    private static boolean generateOneWithoutExistingCheck(
        GdalDataset dataset,
        Path outputRoot,
        TileAddress address,
        double sourceNoData
    ) throws Exception {
        float[] source;
        try {
            source = dataset.read(
                address.westLongitude(),
                address.northLatitude(),
                address.eastLongitude(),
                address.southLatitude(),
                RawTileIO.CORE_SIDE,
                RawTileIO.CORE_SIDE);
        } catch (IOException exception) {
            throw new IOException("Could not resample " + address + " covering ["
                + address.westLongitude() + ", " + address.southLatitude() + ", "
                + address.eastLongitude() + ", " + address.northLatitude() + "]", exception);
        }
        short[] target = new short[RawTileIO.CORE_SAMPLE_COUNT];
        boolean hasData = false;
        for (int i = 0; i < source.length; i++) {
            float value = source[i];
            if (!isValid(value, sourceNoData)) {
                target[i] = RawTileIO.NODATA;
                continue;
            }
            int rounded = (int) Math.round(value);
            rounded = Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, rounded));
            target[i] = (short) rounded;
            hasData = true;
        }
        if (hasData) {
            RawTileIO.writeCore(outputRoot, address, target);
        }
        return hasData;
    }

    private static boolean isValid(float value, double sourceNoData) {
        if (!Float.isFinite(value)) {
            return false;
        }
        if (Double.isFinite(sourceNoData) && Math.abs(value - sourceNoData) < 1e-3) {
            return false;
        }
        // FABDEM elevations are nowhere near its -9999 sentinel. This also rejects
        // any interpolated sentinel contamination from older GDAL versions.
        return value > -9000.0f;
    }
}
