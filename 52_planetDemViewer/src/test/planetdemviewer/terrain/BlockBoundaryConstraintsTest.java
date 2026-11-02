package planetdemviewer.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import planetdemviewer.model.QuadtreeNode;

class BlockBoundaryConstraintsTest {
    private static final int SIDE = TerrainVertexTree.SIDE;
    private static final ProjectedDeltaCriterion CRITERION = new ProjectedDeltaCriterion(0.0, 0.0, 5.0, 1e-3);
    private static final TerrainMeshPlacement PLACEMENT = new TerrainMeshPlacement(0.0, 0.0, 0.0, 1.0, 1.0);

    private final QuadtreeNode root = new QuadtreeNode("0", null, new File("0.bin"));
    private final QuadtreeNode west = new QuadtreeNode("00", root, new File("00.bin"));
    private final QuadtreeNode east = new QuadtreeNode("01", root, new File("01.bin"));
    private final QuadtreeNode fineWest = new QuadtreeNode("002", west, new File("002.bin"));

    /** On flat terrain, every vertex that is not locked and has no dependency is removed. */
    private static AdaptiveTerrainMesh flatMesh() {
        AdaptiveTerrainMesh mesh = new AdaptiveTerrainMesh();
        mesh.initVertexPositionsArray(TerrainVertexTree.VERTEX_COUNT);
        double[] positions = mesh.getVertexPositions();
        boolean[] valid = new boolean[TerrainVertexTree.VERTEX_COUNT];
        for (int vertex = 0; vertex < valid.length; vertex++) {
            positions[vertex * 3] = (double) (vertex % SIDE) / (SIDE - 1);
            positions[vertex * 3 + 1] = (double) (vertex / SIDE) / (SIDE - 1);
            valid[vertex] = true;
        }
        mesh.prepareSimplification(valid, 0.0);
        return mesh;
    }

    private static void simplify(boolean dependenciesCrossBlocks, BlockBoundaryConstraints.Block... blocks) {
        BlockBoundaryConstraints.apply(List.of(blocks), dependenciesCrossBlocks);
        for (BlockBoundaryConstraints.Block block : blocks) {
            block.mesh().updateVertexSelection(CRITERION, PLACEMENT);
        }
        for (BlockBoundaryConstraints.Block block : blocks) {
            block.mesh().selectTriangles();
        }
    }

    /** The samples of the edge that the triangles of the block use. */
    private static int samplesOnEdge(AdaptiveTerrainMesh mesh, TerrainEdge edge) {
        Set<Integer> onEdge = new HashSet<>();
        for (int i = 0; i < SIDE; i++) {
            onEdge.add(TerrainVertexTree.edgeVertex(edge, i));
        }
        Set<Integer> used = new HashSet<>();
        for (int vertex : mesh.getTriangleIndexes()) {
            if (onEdge.contains(vertex)) {
                used.add(vertex);
            }
        }
        return used.size();
    }

    @Test
    void withoutCrossingDependenciesEveryEdgeKeepsAllItsSamples() {
        AdaptiveTerrainMesh a = flatMesh();
        simplify(false, new BlockBoundaryConstraints.Block(west, a));
        for (TerrainEdge edge : TerrainEdge.values()) {
            assertEquals(SIDE, samplesOnEdge(a, edge));
        }
    }

    @Test
    void tilesOfTheSameLevelSimplifyTheirCommonEdgeTogether() {
        AdaptiveTerrainMesh a = flatMesh();
        AdaptiveTerrainMesh b = flatMesh();
        TerrainEdge edge = TerrainSeamStitcher.touchingEdge(west, east);
        simplify(true, new BlockBoundaryConstraints.Block(west, a), new BlockBoundaryConstraints.Block(east, b));
        assertEquals(2, samplesOnEdge(a, edge));
        assertEquals(2, samplesOnEdge(b, edge.opposite()));
    }

    @Test
    void anEdgeAgainstAnotherLevelStaysComplete() {
        AdaptiveTerrainMesh fine = flatMesh();
        AdaptiveTerrainMesh coarse = flatMesh();
        TerrainEdge edge = TerrainSeamStitcher.touchingEdge(fineWest, east);
        simplify(true, new BlockBoundaryConstraints.Block(fineWest, fine),
            new BlockBoundaryConstraints.Block(east, coarse));
        assertEquals(SIDE, samplesOnEdge(fine, edge));
        assertEquals(SIDE, samplesOnEdge(coarse, edge.opposite()));
        assertTrue(samplesOnEdge(coarse, edge) < SIDE, "the edge with no neighbour is free");
    }

    @Test
    void turningTheOptionOffRestoresTheSamples() {
        AdaptiveTerrainMesh a = flatMesh();
        AdaptiveTerrainMesh b = flatMesh();
        TerrainEdge edge = TerrainSeamStitcher.touchingEdge(west, east);
        BlockBoundaryConstraints.Block first = new BlockBoundaryConstraints.Block(west, a);
        BlockBoundaryConstraints.Block second = new BlockBoundaryConstraints.Block(east, b);
        simplify(true, first, second);
        simplify(false, first, second);
        assertEquals(SIDE, samplesOnEdge(a, edge));
        assertEquals(SIDE, samplesOnEdge(b, edge.opposite()));
        simplify(true, first, second);
        assertEquals(2, samplesOnEdge(a, edge));
    }
}
