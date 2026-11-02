package planetdemviewer.terrain;

/** Edge of a tile that touches another rendered tile. */
public enum TerrainEdge {
    NORTH,
    SOUTH,
    EAST,
    WEST;

    /** The edge of the adjacent tile that lies on this one. */
    public TerrainEdge opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
        };
    }
}
