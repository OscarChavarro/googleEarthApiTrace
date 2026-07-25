package pyramidalimagecoverage.model;

public record TileAddress(String quadKey, int depth, int column, int southRow) {
    public double lowerLeftLongitude() {
        return -180.0 + column * longitudeSpan();
    }

    public double lowerLeftLatitude() {
        return Math.max(-90.0, nominalLowerLatitude());
    }

    public double longitudeSpan() {
        return 360.0 / matrixSide();
    }

    public double latitudeSpan() {
        return Math.max(0.0, upperRightLatitude() - lowerLeftLatitude());
    }

    public double centerLongitude() {
        return lowerLeftLongitude() + longitudeSpan() / 2.0;
    }

    public double centerLatitude() {
        return lowerLeftLatitude() + latitudeSpan() / 2.0;
    }

    public boolean hasGeographicCoverage() {
        return nominalLowerLatitude() < 90.0 && nominalUpperLatitude() > -90.0;
    }

    private double upperRightLatitude() {
        return Math.min(90.0, nominalUpperLatitude());
    }

    private double nominalLowerLatitude() {
        return -180.0 + southRow * longitudeSpan();
    }

    private double nominalUpperLatitude() {
        return nominalLowerLatitude() + longitudeSpan();
    }

    private int matrixSide() {
        return 1 << depth;
    }

    public static TileAddress fromQuadKey(String quadKey) {
        if (quadKey == null || !quadKey.matches("0[0-3]*")) {
            throw new IllegalArgumentException("Invalid quadkey: " + quadKey);
        }
        int column = 0;
        int southRow = 0;
        for (int i = 1; i < quadKey.length(); i++) {
            column <<= 1;
            southRow <<= 1;
            switch (quadKey.charAt(i)) {
                case '0' -> { }
                case '1' -> column++;
                case '2' -> { column++; southRow++; }
                case '3' -> southRow++;
                default -> throw new IllegalArgumentException("Invalid quadkey: " + quadKey);
            }
        }
        return new TileAddress(quadKey, quadKey.length() - 1, column, southRow);
    }

    public static TileAddress fromCoordinates(int depth, int column, int southRow) {
        if (depth < 0 || depth > PyramidCatalog.MAX_ADDRESSABLE_DEPTH) {
            throw new IllegalArgumentException("Invalid depth: " + depth);
        }
        int matrixSide = 1 << depth;
        if (column < 0 || column >= matrixSide || southRow < 0 || southRow >= matrixSide) {
            throw new IllegalArgumentException(
                "Tile coordinates outside depth " + depth + ": " + column + ", " + southRow
            );
        }
        StringBuilder quadKey = new StringBuilder(depth + 1).append('0');
        for (int bit = depth - 1; bit >= 0; bit--) {
            int east = (column >>> bit) & 1;
            int north = (southRow >>> bit) & 1;
            quadKey.append(switch (north * 2 + east) {
                case 0 -> '0';
                case 1 -> '1';
                case 2 -> '3';
                case 3 -> '2';
                default -> throw new AssertionError();
            });
        }
        return new TileAddress(quadKey.toString(), depth, column, southRow);
    }
}
