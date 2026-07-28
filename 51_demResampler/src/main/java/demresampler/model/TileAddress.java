package demresampler.model;

import java.nio.file.Path;

public record TileAddress(int level, int row, int column) {
    public TileAddress {
        if (level < 0 || level > 30) {
            throw new IllegalArgumentException("level must be in [0, 30]");
        }
        int side = 1 << level;
        if (row < 0 || row >= side || column < 0 || column >= side) {
            throw new IllegalArgumentException(
                "tile coordinates outside level " + level + ": row=" + row + ", column=" + column);
        }
    }

    public static TileAddress unpack(int level, long packed) {
        return new TileAddress(level, (int) (packed >>> 32), (int) packed);
    }

    public long packedCoordinates() {
        return pack(row, column);
    }

    public TileAddress parent() {
        if (level == 0) {
            throw new IllegalStateException("The root tile has no parent");
        }
        return new TileAddress(level - 1, row >>> 1, column >>> 1);
    }

    public TileAddress child(int quadrant) {
        if (quadrant < 0 || quadrant > 3) {
            throw new IllegalArgumentException("quadrant must be in [0, 3]");
        }
        boolean south = quadrant == 0 || quadrant == 1;
        boolean east = quadrant == 1 || quadrant == 2;
        return new TileAddress(level + 1, row * 2 + (south ? 1 : 0), column * 2 + (east ? 1 : 0));
    }

    public String quadkey() {
        StringBuilder result = new StringBuilder(level + 1);
        result.append('0');
        for (int bit = level - 1; bit >= 0; bit--) {
            boolean south = ((row >>> bit) & 1) != 0;
            boolean east = ((column >>> bit) & 1) != 0;
            result.append(quadrant(south, east));
        }
        return result.toString();
    }

    public Path path(Path pyramidRoot) {
        if (level == 0) {
            return pyramidRoot.resolve("0.bin");
        }
        String key = quadkey();
        Path directory = pyramidRoot;
        for (int i = 1; i < key.length(); i++) {
            directory = directory.resolve(String.valueOf(key.charAt(i)));
        }
        return directory.resolve(key + ".bin");
    }

    public double westLongitude() {
        return -180.0 + column * angularTileSize();
    }

    public double eastLongitude() {
        return westLongitude() + angularTileSize();
    }

    public double northLatitude() {
        return 180.0 - row * angularTileSize();
    }

    public double southLatitude() {
        return northLatitude() - angularTileSize();
    }

    public double angularTileSize() {
        return 360.0 / (1L << level);
    }

    public static long pack(int row, int column) {
        return ((long) row << 32) | (column & 0xffff_ffffL);
    }

    private static char quadrant(boolean south, boolean east) {
        if (south) {
            return east ? '1' : '0';
        }
        return east ? '2' : '3';
    }
}
