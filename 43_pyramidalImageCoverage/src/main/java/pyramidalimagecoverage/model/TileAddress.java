package pyramidalimagecoverage.model;

public record TileAddress(String quadKey, int depth, int column, int southRow) {
    public double lowerLeftLongitude() {
        return -180.0 + column * longitudeSpan();
    }

    public double lowerLeftLatitude() {
        return -90.0 + southRow * latitudeSpan();
    }

    public double longitudeSpan() {
        return 360.0 / matrixSide();
    }

    public double latitudeSpan() {
        return 180.0 / matrixSide();
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
}
