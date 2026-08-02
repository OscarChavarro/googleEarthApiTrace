package pyramidalimagecoverage.model;

public record TileBounds(int minimumColumn, int minimumSouthRow, int maximumColumn, int maximumSouthRow) {
    public int columnCount() {
        return maximumColumn - minimumColumn + 1;
    }

    public int rowCount() {
        return maximumSouthRow - minimumSouthRow + 1;
    }
}
