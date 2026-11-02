package planetdemviewer.model;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import planetdemviewer.palette.PaletteCatalog;
import vsdk.toolkit.media.RGBAImageUncompressed;

/**
 * Complete DEM tile including halo. Source samples remain available in their
 * native datum; canonical elevations are WGS84 ellipsoidal metres when an
 * ElevationNormalizer was supplied at load time.
 */
public final class DemTile {
    public static final int CORE_SIZE = 256;
    public static final int STORED_SIZE = 258;
    public static final int SAMPLE_COUNT = STORED_SIZE * STORED_SIZE;
    public static final int BYTE_COUNT = SAMPLE_COUNT * Short.BYTES;
    public static final short NO_DATA = Short.MIN_VALUE;

    private final short[] sourceElevations;
    private final float[] canonicalElevations;
    private final ElevationRange coreElevationRange;
    private final CanonicalElevationRange canonicalCoreElevationRange;

    private DemTile(short[] sourceElevations, float[] canonicalElevations) {
        this.sourceElevations = sourceElevations;
        this.canonicalElevations = canonicalElevations;
        this.coreElevationRange = calculateCoreElevationRange(sourceElevations);
        this.canonicalCoreElevationRange = calculateCanonicalCoreElevationRange(sourceElevations, canonicalElevations);
    }

    public static DemTile read(Path file) throws IOException {
        return read(file, null);
    }

    public static DemTile read(Path file, ElevationNormalizer normalizer) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length != BYTE_COUNT) {
            throw new IOException("Invalid DEM tile size " + bytes.length + " (expected " + BYTE_COUNT + "): " + file);
        }
        short[] source = new short[SAMPLE_COUNT];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(source);
        float[] canonical = new float[SAMPLE_COUNT];
        for (int row = 0; row < STORED_SIZE; row++) {
            for (int column = 0; column < STORED_SIZE; column++) {
                int index = row * STORED_SIZE + column;
                short sample = source[index];
                canonical[index] = sample == NO_DATA ? Float.NaN
                    : normalizer == null ? sample : normalizer.toCanonicalMetres(row, column, sample);
            }
        }
        return new DemTile(source, canonical);
    }

    /** Original source-datum sample, retained for provenance and diagnostics. */
    public short elevation(int storedRow, int storedColumn) {
        return sourceElevations[index(storedRow, storedColumn)];
    }

    /** Canonical WGS84 ellipsoidal elevation in metres; NoData is NaN. */
    public double elevationMetres(int storedRow, int storedColumn) {
        return canonicalElevations[index(storedRow, storedColumn)];
    }

    /** Defensive access for source-datum inspection. */
    public short[] copyElevationsWithHalo() {
        return sourceElevations.clone();
    }

    /** Defensive access for canonical WGS84 ellipsoidal processing. */
    public float[] copyCanonicalElevationsWithHalo() {
        return canonicalElevations.clone();
    }

    /** Source-datum min/max of the rendered core, ignoring NoData. */
    public ElevationRange coreElevationRange() {
        return coreElevationRange;
    }

    /** WGS84 ellipsoidal min/max of the rendered core, ignoring NoData. */
    public CanonicalElevationRange canonicalCoreElevationRange() {
        return canonicalCoreElevationRange;
    }

    /** RAM retained by raw source samples and canonical normalized values. */
    public long retainedBytes() {
        return (long) sourceElevations.length * Short.BYTES
            + (long) canonicalElevations.length * Float.BYTES;
    }

    private static int index(int row, int column) {
        if (row < 0 || row >= STORED_SIZE || column < 0 || column >= STORED_SIZE) {
            throw new IndexOutOfBoundsException("DEM coordinate outside 258x258 tile");
        }
        return row * STORED_SIZE + column;
    }

    private static ElevationRange calculateCoreElevationRange(short[] source) {
        short min = Short.MAX_VALUE;
        short max = Short.MIN_VALUE;
        for (int row = 1; row <= CORE_SIZE; row++) {
            for (int column = 1; column <= CORE_SIZE; column++) {
                short elevation = source[row * STORED_SIZE + column];
                if (elevation == NO_DATA) {
                    continue;
                }
                min = (short) Math.min(min, elevation);
                max = (short) Math.max(max, elevation);
            }
        }
        return min > max ? new ElevationRange((short) 0, (short) 0) : new ElevationRange(min, max);
    }

    private static CanonicalElevationRange calculateCanonicalCoreElevationRange(short[] source, float[] canonical) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int row = 1; row <= CORE_SIZE; row++) {
            for (int column = 1; column <= CORE_SIZE; column++) {
                int index = row * STORED_SIZE + column;
                if (source[index] == NO_DATA || !Float.isFinite(canonical[index])) {
                    continue;
                }
                min = Math.min(min, canonical[index]);
                max = Math.max(max, canonical[index]);
            }
        }
        return !Double.isFinite(min) ? new CanonicalElevationRange(0.0, 0.0)
            : new CanonicalElevationRange(min, max);
    }

    public record ElevationRange(short minimumMetres, short maximumMetres) {
    }

    public record CanonicalElevationRange(double minimumMetres, double maximumMetres) {
    }

    /** Colorizes only core samples; palette uses canonical WGS84 values rounded to metres. */
    public RGBAImageUncompressed colorizeCore(PaletteCatalog palette) {
        PaletteCatalog.PaletteSnapshot snapshot = palette.snapshot();
        byte[] rgba = new byte[CORE_SIZE * CORE_SIZE * 4];
        for (int row = 0; row < CORE_SIZE; row++) {
            int storedBase = (row + 1) * STORED_SIZE + 1;
            // Row zero is the north edge, and a Vitral image keeps its bottom row first in memory.
            int imageBase = (CORE_SIZE - 1 - row) * CORE_SIZE * 4;
            for (int column = 0; column < CORE_SIZE; column++) {
                int storedIndex = storedBase + column;
                int argb = sourceElevations[storedIndex] == NO_DATA
                    ? snapshot.argbFor(NO_DATA)
                    : snapshot.argbFor(toPaletteMetres(canonicalElevations[storedIndex]));
                int imageIndex = imageBase + column * 4;
                rgba[imageIndex] = (byte) (argb >>> 16);
                rgba[imageIndex + 1] = (byte) (argb >>> 8);
                rgba[imageIndex + 2] = (byte) argb;
                rgba[imageIndex + 3] = (byte) (argb >>> 24);
            }
        }
        RGBAImageUncompressed image = new RGBAImageUncompressed();
        image.setRawImage(CORE_SIZE, CORE_SIZE, rgba);
        return image;
    }

    private static short toPaletteMetres(float elevation) {
        int rounded = Math.round(elevation);
        return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, rounded));
    }

    @FunctionalInterface
    public interface ElevationNormalizer {
        float toCanonicalMetres(int storedRow, int storedColumn, short sourceMetres);
    }
}
