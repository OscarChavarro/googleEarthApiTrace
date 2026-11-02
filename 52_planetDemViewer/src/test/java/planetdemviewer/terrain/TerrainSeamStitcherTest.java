package planetdemviewer.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import planetdemviewer.model.QuadtreeNode;
import planetdemviewer.processing.DrawCommand;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

class TerrainSeamStitcherTest {
    @TempDir Path temporaryDirectory;

    @Test
    void identifiesAndCachesFineToCoarseEdgeRelationship() {
        QuadtreeNode root = node("0", null);
        QuadtreeNode southWest = node("00", root);
        QuadtreeNode fineNorthEast = node("002", southWest);
        QuadtreeNode coarseSouthEast = node("01", root);
        List<DrawCommand> commands = List.of(
            new DrawCommand(fineNorthEast, new Vector3Dd[0]),
            new DrawCommand(coarseSouthEast, new Vector3Dd[0]));

        TerrainSeamStitcher stitcher = new TerrainSeamStitcher();
        try {
            List<TerrainMeshPlan> first = stitcher.plan(commands, TerrainSubdivisionScheme.TRIANGLE_EDGE);
            TerrainMeshPlan finePlan = first.stream()
                .filter(plan -> plan.command().node() == fineNorthEast)
                .findFirst().orElseThrow();
            assertEquals(1, finePlan.coarseNeighbours().size());
            assertEquals(TerrainEdge.EAST, finePlan.coarseNeighbours().get(0).fineEdge());
            assertSame(coarseSouthEast, finePlan.coarseNeighbours().get(0).coarseNode());

            List<TerrainMeshPlan> cached = stitcher.plan(commands, TerrainSubdivisionScheme.TRIANGLE_EDGE);
            assertSame(first, cached);
            assertEquals(1, stitcher.relationCacheSize());

            QuadtreeNode coarseNorthWest = node("03", root);
            stitcher.plan(List.of(
                new DrawCommand(fineNorthEast, new Vector3Dd[0]),
                new DrawCommand(coarseSouthEast, new Vector3Dd[0]),
                new DrawCommand(coarseNorthWest, new Vector3Dd[0])), TerrainSubdivisionScheme.TRIANGLE_EDGE);
            assertEquals(2, stitcher.relationCacheSize());
            assertEquals(1, stitcher.pairCacheHits());
        }
        finally {
            stitcher.shutdown();
        }
    }

    @Test
    void eachSchemeGetsItsOwnMeshVariant() {
        QuadtreeNode root = node("0", null);
        List<DrawCommand> commands = List.of(new DrawCommand(root, new Vector3Dd[0]));
        TerrainSeamStitcher stitcher = new TerrainSeamStitcher();
        try {
            TerrainMeshPlan rows = stitcher.plan(commands, TerrainSubdivisionScheme.TRIANGLE_EDGE).get(0);
            TerrainMeshPlan diamonds = stitcher.plan(commands, TerrainSubdivisionScheme.DIAMOND_SQUARE).get(0);
            assertEquals(TerrainSubdivisionScheme.DIAMOND_SQUARE, diamonds.scheme());
            assertNotEquals(rows.variantKey(), diamonds.variantKey());
        }
        finally {
            stitcher.shutdown();
        }
    }

    private QuadtreeNode node(String id, QuadtreeNode parent) {
        return new QuadtreeNode(id, parent, temporaryDirectory.resolve(id + ".bin").toFile());
    }
}
