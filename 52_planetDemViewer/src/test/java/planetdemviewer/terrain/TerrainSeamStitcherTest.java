package planetdemviewer.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            List<TerrainTilePlan> first = stitcher.plan(commands);
            TerrainTilePlan finePlan = first.stream()
                .filter(plan -> plan.command().node() == fineNorthEast)
                .findFirst().orElseThrow();
            assertEquals(1, finePlan.coarseNeighbours().size());
            assertEquals(TerrainEdge.EAST, finePlan.coarseNeighbours().get(0).fineEdge());
            assertSame(coarseSouthEast, finePlan.coarseNeighbours().get(0).coarseNode());

            List<TerrainTilePlan> cached = stitcher.plan(commands);
            assertSame(first, cached);
            assertEquals(1, stitcher.relationCacheSize());

            QuadtreeNode coarseNorthWest = node("03", root);
            stitcher.plan(List.of(
                new DrawCommand(fineNorthEast, new Vector3Dd[0]),
                new DrawCommand(coarseSouthEast, new Vector3Dd[0]),
                new DrawCommand(coarseNorthWest, new Vector3Dd[0])));
            assertEquals(2, stitcher.relationCacheSize());
            assertEquals(1, stitcher.pairCacheHits());
        }
        finally {
            stitcher.shutdown();
        }
    }

    private QuadtreeNode node(String id, QuadtreeNode parent) {
        return new QuadtreeNode(id, parent, temporaryDirectory.resolve(id + ".bin").toFile());
    }
}
