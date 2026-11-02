package planetdemviewer.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import planetdemviewer.model.DemTile;
import planetdemviewer.model.QuadtreeNode;

class QuadrantTerrainMeshTest {
    @TempDir
    Path temporaryDirectory;
    private static final QuadtreeNode NODE = new QuadtreeNode("0", null, Path.of("0.bin").toFile());
    /** A tile split into its quadrants, and one of them further, down to the smallest square. */
    private static final List<GridQuadrant> MIXED = mixed();

    private static List<GridQuadrant> mixed() {
        List<GridQuadrant> result = new ArrayList<>();
        GridQuadrant current = GridQuadrant.WHOLE_TILE;
        while (current.side() > LinkedImageryBlocks.SMALLEST_QUADRANT_SIDE) {
            for (int digit = 0; digit < 3; digit++) {
                result.add(current.child(digit));
            }
            current = current.child(3);
        }
        result.add(current);
        return result;
    }

    private DemTile tile() throws Exception {
        Random random = new Random(7);
        ByteBuffer samples = ByteBuffer.allocate(DemTile.BYTE_COUNT).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < DemTile.SAMPLE_COUNT; i++) {
            samples.putShort((short) (1000 + random.nextInt(50)));
        }
        return DemTile.read(Files.write(temporaryDirectory.resolve("tile.bin"), samples.array()));
    }

    private static Set<List<Integer>> triangleSet(int[] indices, int first, int count) {
        Set<List<Integer>> result = new HashSet<>();
        for (int i = first; i < first + count; i += 3) {
            result.add(List.of(indices[i], indices[i + 1], indices[i + 2]));
        }
        return result;
    }

    /** Twice the area of the triangles, in cells of the grid, in which no triangle may overlap another. */
    private static long doubleArea(int[] indices, int first, int count) {
        long area = 0L;
        int side = TerrainVertexTree.SIDE;
        for (int i = first; i < first + count; i += 3) {
            int r0 = indices[i] / side;
            int c0 = indices[i] % side;
            int r1 = indices[i + 1] / side;
            int c1 = indices[i + 1] % side;
            int r2 = indices[i + 2] / side;
            int c2 = indices[i + 2] % side;
            area += Math.abs((long) (r1 - r0) * (c2 - c0) - (long) (r2 - r0) * (c1 - c0));
        }
        return area;
    }

    private static boolean inside(GridQuadrant quadrant, int vertex) {
        int row = vertex / TerrainVertexTree.SIDE;
        int column = vertex % TerrainVertexTree.SIDE;
        return row >= quadrant.row() && row <= quadrant.row() + quadrant.side()
            && column >= quadrant.column() && column <= quadrant.column() + quadrant.side();
    }

    private QuadrantTerrainMesh diamondMesh() throws Exception {
        return new BasicTerrainMeshGenerator(TerrainSubdivisionScheme.DIAMOND_SQUARE.traversal())
            .generateWithQuadrants(NODE, tile(), List.of());
    }

    @Test
    void quadrantsGroupExactlyTheTrianglesOfTheWholeTile() throws Exception {
        QuadrantTerrainMesh mesh = diamondMesh();
        Set<List<Integer>> all = triangleSet(mesh.getTriangleIndexes(), 0, mesh.getTriangleIndexes().length);
        assertEquals(131_072, all.size());

        mesh.useQuadrants(MIXED);
        mesh.applyQuadrants();

        int[] indices = mesh.getTriangleIndexes();
        assertEquals(131_072 * 3, indices.length);
        assertEquals(all, triangleSet(indices, 0, indices.length));
        int next = 0;
        for (int q = 0; q < MIXED.size(); q++) {
            assertEquals(next, mesh.firstIndex(q));
            next += mesh.indexCount(q);
            for (int i = mesh.firstIndex(q); i < next; i++) {
                assertTrue(inside(MIXED.get(q), indices[i]), "A triangle crosses quadrant " + MIXED.get(q));
            }
            assertEquals(2L * MIXED.get(q).side() * MIXED.get(q).side(),
                doubleArea(indices, mesh.firstIndex(q), mesh.indexCount(q)));
        }
        assertEquals(indices.length, next);
    }

    @Test
    void theSmallestSquareHasEightTriangles() throws Exception {
        QuadrantTerrainMesh mesh = diamondMesh();
        mesh.useQuadrants(List.of(new GridQuadrant(10, 20, 2)));
        mesh.applyQuadrants();
        assertEquals(8 * 3, mesh.indexCount(0));
    }

    @Test
    void theWholeTileGoesBackToTheGeneratedTrianglesWithOneUploadEachWay() throws Exception {
        QuadrantTerrainMesh mesh = diamondMesh();
        int[] generated = mesh.getTriangleIndexes();
        assertNull(mesh.takeTrianglesToUpload(), "The backend already has them");
        mesh.useQuadrants(MIXED);
        mesh.applyQuadrants();
        assertEquals(generated.length, mesh.takeTrianglesToUpload().length);
        assertNull(mesh.takeTrianglesToUpload());
        mesh.useQuadrants(List.of(GridQuadrant.WHOLE_TILE));
        mesh.applyQuadrants();
        assertEquals(generated, mesh.takeTrianglesToUpload());
    }

    @Test
    void onlyTheVisibleSquaresRemain() throws Exception {
        QuadrantTerrainMesh mesh = diamondMesh();
        mesh.useQuadrants(List.of(GridQuadrant.WHOLE_TILE.child(1)));
        mesh.applyQuadrants();
        assertEquals(131_072 / 4 * 3, mesh.getTriangleIndexes().length);
        assertEquals(131_072, mesh.fullTriangleCount());
    }

    @Test
    void simplifiedTrianglesStillPartitionEverySquareWithoutOverlaps() throws Exception {
        // A view from far above, with a threshold that disables what nothing requires.
        ProjectedDeltaCriterion criterion = new ProjectedDeltaCriterion(0.3, -0.9, 0.2, 10.0);
        AdaptiveTerrainMesh mesh = new BasicTerrainMeshGenerator(TerrainSubdivisionScheme.LIND1996.traversal())
            .generateAdaptive(NODE, tile(), List.of(), null);
        TerrainMeshPlacement placement = new TerrainMeshPlacement(0.0, 0.0, 0.0, 1.0, 1.0);
        mesh.simplify(criterion, placement);
        int coarse = mesh.getNumTriangles();

        mesh.useQuadrants(MIXED);
        mesh.simplify(criterion, placement);

        int[] indices = mesh.getTriangleIndexes();
        assertTrue(mesh.getNumTriangles() > coarse);
        assertTrue(mesh.getNumTriangles() < mesh.fullTriangleCount(), "It must stay simplified: " + mesh.getNumTriangles());
        for (int q = 0; q < MIXED.size(); q++) {
            for (int i = mesh.firstIndex(q); i < mesh.firstIndex(q) + mesh.indexCount(q); i++) {
                assertTrue(inside(MIXED.get(q), indices[i]), "A triangle crosses quadrant " + MIXED.get(q));
            }
            assertEquals(2L * MIXED.get(q).side() * MIXED.get(q).side(),
                doubleArea(indices, mesh.firstIndex(q), mesh.indexCount(q)), "The area of " + MIXED.get(q));
        }

        mesh.useQuadrants(List.of(GridQuadrant.WHOLE_TILE));
        mesh.simplify(criterion, placement);
        assertEquals(coarse, mesh.getNumTriangles(), "Vertices no square requires must leave again");
    }
}
