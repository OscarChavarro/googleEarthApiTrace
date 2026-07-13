package pyramidalimageexporter.processing.toplevels;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.TopLevelTilesCatalog;

final class TopLevelMatrixRebuilderTest {
    @Test
    void generatedTileIdsAreAbsoluteAndDoNotCollideWithRoot() {
        TopLevelTilesCatalog.TexCoord texCoord = new TopLevelTilesCatalog.TexCoord();
        texCoord.setU0(0.0);
        texCoord.setV0(0.0);
        texCoord.setU1(1.0 / 32.0);
        texCoord.setV1(1.0 / 32.0);

        TopLevelTilesCatalog.FrameAppearance appearance = new TopLevelTilesCatalog.FrameAppearance();
        appearance.setImagePath("/tmp/root.png");
        appearance.setTexCoord(texCoord);

        TopLevelTilesCatalog.TopLevelTile strip = new TopLevelTilesCatalog.TopLevelTile();
        strip.setAppearances(List.of(appearance));
        TopLevelTilesCatalog catalog = new TopLevelTilesCatalog();
        catalog.setByStripId(Map.of("strip", strip));

        List<MatrixLayer> layers = new TopLevelMatrixRebuilder().importLayers(catalog);

        assertEquals(List.of("0"), layers.get(0).getTiles().stream().map(tile -> tile.getId()).toList());
        assertEquals(
            List.of("03", "02", "00", "01"),
            layers.get(1).getTiles().stream().map(tile -> tile.getId()).toList()
        );
    }
}
