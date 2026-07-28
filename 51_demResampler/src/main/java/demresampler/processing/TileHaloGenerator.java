package demresampler.processing;

import demresampler.io.RawTileIO;
import demresampler.model.TileAddress;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

public final class TileHaloGenerator {
    private TileHaloGenerator() {
    }

    public static Set<Long> generate(
        Path outputRoot,
        int level,
        Set<Long> coordinates,
        int threads
    ) throws Exception {
        long[] items = coordinates.stream().mapToLong(Long::longValue).toArray();
        Set<Long> published = ParallelTileRunner.run(
            "Halo level " + level,
            items,
            threads,
            EmptyContext::new,
            (ignored, packed) -> publishOne(
                outputRoot, TileAddress.unpack(level, packed))
        );
        return published;
    }

    private static boolean publishOne(Path outputRoot, TileAddress address) throws Exception {
        short[] core = RawTileIO.readCore(outputRoot, address);
        short[] stored = new short[RawTileIO.STORED_SAMPLE_COUNT];
        Arrays.fill(stored, RawTileIO.NODATA);
        for (int y = 0; y < RawTileIO.CORE_SIDE; y++) {
            System.arraycopy(
                core,
                y * RawTileIO.CORE_SIDE,
                stored,
                (y + 1) * RawTileIO.STORED_SIDE + 1,
                RawTileIO.CORE_SIDE);
        }

        copyHorizontalBorder(
            outputRoot, verticalNeighbor(address, -1), RawTileIO.Border.SOUTH, stored, 0);
        copyHorizontalBorder(
            outputRoot,
            verticalNeighbor(address, 1),
            RawTileIO.Border.NORTH,
            stored,
            (RawTileIO.STORED_SIDE - 1) * RawTileIO.STORED_SIDE);
        copyVerticalBorder(
            outputRoot, horizontalNeighbor(address, -1), RawTileIO.Border.EAST, stored, 0);
        copyVerticalBorder(
            outputRoot,
            horizontalNeighbor(address, 1),
            RawTileIO.Border.WEST,
            stored,
            RawTileIO.STORED_SIDE - 1);

        copyCorner(
            outputRoot, diagonalNeighbor(address, -1, -1),
            RawTileIO.Border.SOUTH, RawTileIO.CORE_SIDE - 1, stored, 0);
        copyCorner(
            outputRoot, diagonalNeighbor(address, -1, 1),
            RawTileIO.Border.SOUTH, 0, stored, RawTileIO.STORED_SIDE - 1);
        copyCorner(
            outputRoot, diagonalNeighbor(address, 1, -1),
            RawTileIO.Border.NORTH, RawTileIO.CORE_SIDE - 1,
            stored, (RawTileIO.STORED_SIDE - 1) * RawTileIO.STORED_SIDE);
        copyCorner(
            outputRoot, diagonalNeighbor(address, 1, 1),
            RawTileIO.Border.NORTH, 0, stored, RawTileIO.STORED_SAMPLE_COUNT - 1);

        RawTileIO.write(outputRoot, address, stored);
        RawTileIO.deleteLegacyEdges(outputRoot, address);
        return true;
    }

    private static void copyHorizontalBorder(
        Path outputRoot,
        TileAddress neighbor,
        RawTileIO.Border sourceBorder,
        short[] target,
        int targetOffset
    ) throws Exception {
        if (!hasCore(outputRoot, neighbor)) {
            return;
        }
        short[] source = RawTileIO.readCoreBorder(outputRoot, neighbor, sourceBorder);
        System.arraycopy(source, 0, target, targetOffset + 1, RawTileIO.CORE_SIDE);
    }

    private static void copyVerticalBorder(
        Path outputRoot,
        TileAddress neighbor,
        RawTileIO.Border sourceBorder,
        short[] target,
        int targetColumn
    ) throws Exception {
        if (!hasCore(outputRoot, neighbor)) {
            return;
        }
        short[] source = RawTileIO.readCoreBorder(outputRoot, neighbor, sourceBorder);
        for (int y = 0; y < RawTileIO.CORE_SIDE; y++) {
            target[(y + 1) * RawTileIO.STORED_SIDE + targetColumn] = source[y];
        }
    }

    private static void copyCorner(
        Path outputRoot,
        TileAddress neighbor,
        RawTileIO.Border sourceBorder,
        int sourceIndex,
        short[] target,
        int targetIndex
    ) throws Exception {
        if (!hasCore(outputRoot, neighbor)) {
            return;
        }
        target[targetIndex] =
            RawTileIO.readCoreBorder(outputRoot, neighbor, sourceBorder)[sourceIndex];
    }

    private static boolean hasCore(Path outputRoot, TileAddress address) throws Exception {
        return address != null && RawTileIO.isCoreComplete(outputRoot, address);
    }

    private static TileAddress verticalNeighbor(TileAddress address, int deltaRow) {
        int row = address.row() + deltaRow;
        int side = 1 << address.level();
        return row < 0 || row >= side
            ? null
            : new TileAddress(address.level(), row, address.column());
    }

    private static TileAddress horizontalNeighbor(TileAddress address, int deltaColumn) {
        int side = 1 << address.level();
        int column = Math.floorMod(address.column() + deltaColumn, side);
        return new TileAddress(address.level(), address.row(), column);
    }

    private static TileAddress diagonalNeighbor(
        TileAddress address,
        int deltaRow,
        int deltaColumn
    ) {
        TileAddress vertical = verticalNeighbor(address, deltaRow);
        return vertical == null ? null : horizontalNeighbor(vertical, deltaColumn);
    }

    private static final class EmptyContext implements AutoCloseable {
        @Override
        public void close() {
        }
    }
}
