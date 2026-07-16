package frametexturenormalizer.render;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;

final class Jogl4FrameTextureNormalizerRendererTest {
    @Test
    void connectedSelectionCanStartFromWestCutterTile() {
        TileInstance westCutter = tile(10, null, null, 11, null, true);
        TileInstance neighbor = tile(11, null, null, null, 10, false);

        boolean changed = Jogl4FrameTextureNormalizerRenderer.selectConnectedTiles(
            List.of(westCutter, neighbor),
            westCutter.getTileId()
        );

        assertTrue(changed);
        assertTrue(westCutter.isSelected());
        assertTrue(neighbor.isSelected());
    }

    @Test
    void connectedSelectionIncludesWestCutterNeighbor() {
        TileInstance seed = tile(10, null, null, 11, null, false);
        TileInstance westCutterNeighbor = tile(11, null, null, null, 10, true);

        boolean changed = Jogl4FrameTextureNormalizerRenderer.selectConnectedTiles(
            List.of(seed, westCutterNeighbor),
            seed.getTileId()
        );

        assertTrue(changed);
        assertTrue(seed.isSelected());
        assertTrue(westCutterNeighbor.isSelected());
    }

    @Test
    void connectedSelectionPropagatesEquivalentTexturesAcrossFrames() {
        TileInstance seed = tile(10, null, null, 11, null, false, "/tmp/shared-a.png");
        TileInstance westCutterNeighbor = tile(11, null, null, null, 10, true, "/tmp/shared-b.png");
        TileInstance sameAsSeedInOtherFrame = tile(20, null, null, null, null, false, "/tmp/shared-a.png");
        TileInstance sameAsNeighborInOtherFrame = tile(21, null, null, null, null, true, "/tmp/shared-b.png");

        boolean changed = Jogl4FrameTextureNormalizerRenderer.selectConnectedTilesAcrossFrames(
            List.of(
                new FrameData(1, List.of(seed, westCutterNeighbor), null),
                new FrameData(2, List.of(sameAsSeedInOtherFrame, sameAsNeighborInOtherFrame), null)
            ),
            List.of(seed, westCutterNeighbor),
            seed.getTileId()
        );

        assertTrue(changed);
        assertTrue(seed.isSelected());
        assertTrue(westCutterNeighbor.isSelected());
        assertTrue(sameAsSeedInOtherFrame.isSelected());
        assertTrue(sameAsNeighborInOtherFrame.isSelected());
    }

    @Test
    void directSelectionPropagatesEquivalentTextureAcrossFrames() {
        TileInstance source = tile(10, null, null, null, null, false, "/tmp/shared.png");
        TileInstance sameTextureInOtherFrame = tile(20, null, null, null, null, true, "/tmp/shared.png");

        boolean changed = Jogl4FrameTextureNormalizerRenderer.setMatchingTextureSelection(
            List.of(
                new FrameData(1, List.of(source), null),
                new FrameData(2, List.of(sameTextureInOtherFrame), null)
            ),
            source,
            true
        );

        assertTrue(changed);
        assertTrue(source.isSelected());
        assertTrue(sameTextureInOtherFrame.isSelected());
    }

    private static TileInstance tile(
        int id,
        Integer south,
        Integer north,
        Integer east,
        Integer west,
        boolean westCuttingCell
    ) {
        return tile(id, south, north, east, west, westCuttingCell, "/tmp/" + id + ".png");
    }

    private static TileInstance tile(
        int id,
        Integer south,
        Integer north,
        Integer east,
        Integer west,
        boolean westCuttingCell,
        String textureFile
    ) {
        return new TileInstance(
            id,
            1,
            textureFile,
            south,
            north,
            east,
            west,
            null,
            null,
            null,
            null,
            false,
            List.of(),
            westCuttingCell,
            false
        );
    }
}
