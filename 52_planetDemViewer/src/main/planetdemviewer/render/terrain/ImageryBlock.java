package planetdemviewer.render.terrain;

import planetdemviewer.spatial.TileAddress;

/** The part of a tile that wants the color of one block of the imagery tree; a null block means no color. */
public record ImageryBlock(GridQuadrant quadrant, TileAddress address) {
}
