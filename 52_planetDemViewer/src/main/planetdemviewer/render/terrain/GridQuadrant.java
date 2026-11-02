package planetdemviewer.render.terrain;

import planetdemviewer.model.DemTile;

/**
 * A square of a tile's 2^8 x 2^8 grid of intervals, whose north-west corner is at a row and
 * column in the rendered coordinates of {@link DiamondSquareTraversal}. The digits of the
 * quadkey convention are 0 = south-west, 1 = south-east, 2 = north-east and 3 = north-west,
 * and row 0 is the north.
 */
public record GridQuadrant(int row, int column, int side) {
    public static final GridQuadrant WHOLE_TILE = new GridQuadrant(0, 0, DemTile.CORE_SIZE);

    public GridQuadrant child(int digit) {
        int half = side / 2;
        return switch (digit) {
            case 0 -> new GridQuadrant(row + half, column, half);
            case 1 -> new GridQuadrant(row + half, column + half, half);
            case 2 -> new GridQuadrant(row, column + half, half);
            case 3 -> new GridQuadrant(row, column, half);
            default -> throw new IllegalArgumentException("Invalid quadrant digit: " + digit);
        };
    }

    public boolean isWholeTile() {
        return side == WHOLE_TILE.side;
    }
}
