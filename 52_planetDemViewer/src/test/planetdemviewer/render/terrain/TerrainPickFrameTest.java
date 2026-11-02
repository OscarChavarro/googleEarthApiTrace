package planetdemviewer.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import planetdemviewer.model.QuadtreeNode;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.spatial.Ray3;
import planetdemviewer.spatial.TileAddress;
import planetdemviewer.spatial.WorldPosition;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.geometry.surface.TriangleMesh;

class TerrainPickFrameTest {
    private static final TileAddress TILE = TileAddress.fabdem("0");
    private static final TileAddress WEST_IMAGE = TileAddress.fabdem("00");
    private static final Ray3 DOWN_ON_THE_WEST = new Ray3(new WorldPosition(-1, 0, 5), new WorldPosition(0, 0, -1));
    private static final Ray3 DOWN_ON_THE_EAST = new Ray3(new WorldPosition(1, 0, 5), new WorldPosition(0, 0, -1));

    private final TerrainPickFrame frame = new TerrainPickFrame();
    private TerrainDrawItem item;
    private TriangleMesh mesh;

    /** A triangle around (-1, 0), and then another one around (1, 0). */
    @BeforeEach
    void createTwoTriangles() {
        QuadtreeNode node = new QuadtreeNode("0", null, Path.of("0.bin").toFile());
        item = new TerrainDrawItem(new DrawCommand(node, new Vector3Dd[0]), "a", null,
            Matrix4x4d.identityMatrix(), 0.0, 0.0, 0.0, 1.0, 1.0, true, null);
        mesh = new TriangleMesh();
        mesh.initVertexPositionsArray(6);
        mesh.initVertexNormalsArray();
        mesh.initTriangleArrays(2);
        System.arraycopy(new double[] {
            -2, -1, 0, 0, -1, 0, -1, 1, 0,
            0, -1, 0, 2, -1, 0, 1, 1, 0}, 0, mesh.getVertexPositions(), 0, 18);
        System.arraycopy(new int[] {0, 1, 2, 3, 4, 5}, 0, mesh.getTriangleIndexes(), 0, 6);
    }

    @Test
    void aHitNamesTheImageryOfTheBlockThatDrawsItsTriangle() {
        frame.meshDrawn(item, mesh, List.of(
            TerrainTextureBlock.mapped("west", TILE, WEST_IMAGE, 0, 3),
            TerrainTextureBlock.mapped("east", TILE, TILE, 3, 3)));
        assertEquals(WEST_IMAGE, frame.intersect(DOWN_ON_THE_WEST).imageryTile());
        assertEquals(TILE, frame.intersect(DOWN_ON_THE_EAST).imageryTile());
    }

    @Test
    void aBlockWithoutTextureHasNoImageryTile() {
        frame.meshDrawn(item, mesh, List.of(
            TerrainTextureBlock.mapped("west", TILE, WEST_IMAGE, 0, 3),
            TerrainTextureBlock.unmapped(null, null, 3, 3)));
        assertNull(frame.intersect(DOWN_ON_THE_EAST).imageryTile());
    }
}
