package planetdemviewer.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import planetdemviewer.io.imagery.ImageryBlockLink;
import planetdemviewer.io.imagery.ImageryBlockLinks;
import planetdemviewer.model.QuadtreeNode;
import planetdemviewer.spatial.TileAddress;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;

class LinkedImageryBlocksTest {
    private static final ImageryBlockLink IMAGE_AND_CHILDREN = new ImageryBlockLink(true, true);
    private static final TerrainMeshPlacement PLACEMENT = new TerrainMeshPlacement(0.0, 0.0, 0.0, 1.0, 1.0);

    private static Camera cameraOver(double x, double y, double height) {
        Camera camera = new Camera();
        camera.setNearPlaneDistance(height * 0.01);
        camera.setPosition(new Vector3Dd(x, y, height));
        camera.setFocusedPositionDirect(new Vector3Dd(x, y, 0.0));
        camera.setUpDirect(new Vector3Dd(0.0, 1.0, 0.0));
        camera.updateVectors();
        return camera;
    }

    private static List<ImageryBlock> select(QuadtreeNode tile, ImageryBlockLinks links, Camera camera) {
        return new LinkedImageryBlocks().select(tile, links, camera, PLACEMENT, 0.0, 0.0);
    }

    private static QuadtreeNode leaf(String id) {
        return new QuadtreeNode(id, null, Path.of(id + ".bin").toFile());
    }

    @Test
    void aMissingImageFallsBackToTheNearestAncestorWithOne() {
        Map<String, ImageryBlockLink> tree = Map.of(
            "0", IMAGE_AND_CHILDREN,
            "01", new ImageryBlockLink(false, true),
            "012", new ImageryBlockLink(false, false));
        ImageryBlockLinks links = block -> tree.getOrDefault(block.quadkey(), ImageryBlockLink.NONE);
        assertEquals(TileAddress.fabdem("0"), LinkedImageryBlocks.nearestLinkedImage(TileAddress.fabdem("012"), links));
    }

    @Test
    void anUnknownBlockDoesNotHideTheAncestorsAndNothingKnownIsNoImage() {
        ImageryBlockLinks unknownBelowRoot = block -> block.level() == 0 ? IMAGE_AND_CHILDREN : null;
        assertEquals(TileAddress.fabdem("0"),
            LinkedImageryBlocks.nearestLinkedImage(TileAddress.fabdem("01"), unknownBelowRoot));
        assertNull(LinkedImageryBlocks.nearestLinkedImage(TileAddress.fabdem("01"), block -> null));
    }

    @Test
    void aTileThatCanStillSplitKeepsOneBlock() {
        QuadtreeNode parent = leaf("0");
        QuadtreeNode child = new QuadtreeNode("00", parent, Path.of("00.bin").toFile());
        parent.completeDiscovery(new QuadtreeNode[] {child, null, null, null});
        assertEquals(List.of(new ImageryBlock(GridQuadrant.WHOLE_TILE, TileAddress.fabdem("0"))),
            select(parent, block -> IMAGE_AND_CHILDREN, cameraOver(0.0, 0.0, 0.5)));
    }

    @Test
    void aFarLeafKeepsOneBlockAndANearOneSplitsWhereTheImageryGoesOn() {
        ImageryBlockLinks links = block -> IMAGE_AND_CHILDREN;
        assertEquals(1, select(leaf("0"), links, cameraOver(0.0, 0.0, 100.0)).size());

        List<ImageryBlock> blocks = select(leaf("0"), links, cameraOver(0.25, 0.25, 0.05));
        int deepest = blocks.stream().mapToInt(block -> block.address().level()).max().orElseThrow();
        assertEquals(true, deepest >= 2, "Deepest level: " + deepest);
        for (ImageryBlock block : blocks) {
            assertEquals(GridQuadrant.WHOLE_TILE.side() >> block.address().level(), block.quadrant().side());
        }
        assertEquals(blocks.size(), new HashSet<>(blocks).size());
    }

    @Test
    void aBlockWithoutChildrenStopsTheRefinement() {
        assertEquals(List.of(new ImageryBlock(GridQuadrant.WHOLE_TILE, TileAddress.fabdem("0"))),
            select(leaf("0"), block -> new ImageryBlockLink(true, false), cameraOver(0.0, 0.0, 0.01)));
    }

    @Test
    void refinementNeverGoesBelowTheSmallestSquareOfTheGrid() {
        Camera camera = cameraOver(0.1234, 0.1234, 1e-3);
        Set<Integer> sides = new HashSet<>();
        for (ImageryBlock block : select(leaf("0"), block -> IMAGE_AND_CHILDREN, camera)) {
            sides.add(block.quadrant().side());
        }
        assertEquals(LinkedImageryBlocks.SMALLEST_QUADRANT_SIDE, sides.stream().min(Integer::compare).orElseThrow());
    }

    @Test
    void theDigitsOfAQuadrantFollowTheQuadkeyConvention() {
        GridQuadrant tile = GridQuadrant.WHOLE_TILE;
        assertEquals(new GridQuadrant(128, 0, 128), tile.child(0), "south-west");
        assertEquals(new GridQuadrant(128, 128, 128), tile.child(1), "south-east");
        assertEquals(new GridQuadrant(0, 128, 128), tile.child(2), "north-east");
        assertEquals(new GridQuadrant(0, 0, 128), tile.child(3), "north-west");
    }

    @Test
    void anAncestorImageMapsTheTileIntoItsPart() {
        // Tile 012 is the north-east quadrant of the south-east quadrant of the root.
        TerrainTextureBlock<String> block = TerrainTextureBlock.mapped("t",
            TileAddress.fabdem("012"), TileAddress.fabdem("0"), 3, 6);
        assertEquals(0.25f, block.textureScale());
        assertEquals(0.75f, block.offsetU());
        assertEquals(0.25f, block.offsetV());
        assertEquals(3, block.firstIndex());
        assertEquals(6, block.indexCount());
    }

    @Test
    void aDeeperImageMapsItsPartOfTheTileToTheWholeImage() {
        // The image of 012 covers u, v in [0.75, 1] x [0.25, 0.5] of tile 0.
        TerrainTextureBlock<String> block = TerrainTextureBlock.mapped("t",
            TileAddress.fabdem("0"), TileAddress.fabdem("012"), 0, 3);
        assertEquals(4.0f, block.textureScale());
        assertEquals(0.75f * 4.0f * -1.0f, block.offsetU());
        assertEquals(0.25f * 4.0f * -1.0f, block.offsetV());
    }
}
