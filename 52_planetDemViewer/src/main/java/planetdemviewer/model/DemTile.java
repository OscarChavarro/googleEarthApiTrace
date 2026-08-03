package planetdemviewer.model;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import planetdemviewer.palette.PaletteCatalog;

/** One complete DEM tile, including its one-sample halo for future terrain meshes. */
public final class DemTile {
    public static final int CORE_SIZE = 256;
    public static final int STORED_SIZE = 258;
    public static final int SAMPLE_COUNT = STORED_SIZE * STORED_SIZE;
    public static final int BYTE_COUNT = SAMPLE_COUNT * Short.BYTES;
    public static final short NO_DATA = Short.MIN_VALUE;

    private final short[] elevations;

    private DemTile(short[] elevations) {
        this.elevations = elevations;
    }

    public static DemTile read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length != BYTE_COUNT) {
            throw new IOException("Invalid DEM tile size " + bytes.length + " (expected " + BYTE_COUNT + "): " + file);
        }
        short[] samples = new short[SAMPLE_COUNT];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        return new DemTile(samples);
    }

    public short elevation(int storedRow, int storedColumn) {
        if (storedRow < 0 || storedRow >= STORED_SIZE || storedColumn < 0 || storedColumn >= STORED_SIZE) {
            throw new IndexOutOfBoundsException("DEM coordinate outside 258x258 tile");
        }
        return elevations[storedRow * STORED_SIZE + storedColumn];
    }

    /** Defensive access for mesh generators that need the complete 258x258 raster. */
    public short[] copyElevationsWithHalo() {
        return elevations.clone();
    }

    /** Colorizes only rows/columns 1..256; the halo stays solely in elevation memory. */
    public BufferedImage colorizeCore(PaletteCatalog palette) {
        PaletteCatalog.PaletteSnapshot snapshot = palette.snapshot();
        BufferedImage image = new BufferedImage(CORE_SIZE, CORE_SIZE, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[CORE_SIZE * CORE_SIZE];
        for (int row = 0; row < CORE_SIZE; row++) {
            int storedBase = (row + 1) * STORED_SIZE + 1;
            int imageBase = row * CORE_SIZE;
            for (int column = 0; column < CORE_SIZE; column++) {
                argb[imageBase + column] = snapshot.argbFor(elevations[storedBase + column]);
            }
        }
        image.setRGB(0, 0, CORE_SIZE, CORE_SIZE, argb, 0, CORE_SIZE);
        return image;
    }
}
