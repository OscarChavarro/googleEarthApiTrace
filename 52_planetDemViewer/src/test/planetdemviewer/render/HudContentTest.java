package planetdemviewer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import planetdemviewer.config.Configuration;
import planetdemviewer.gui.GuiController;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.terrain.TerrainSubdivisionScheme;

class HudContentTest {
    private static final long MB = 1024L * 1024L;

    private final PlanetViewerModel model = new PlanetViewerModel();
    private final GuiController gui = new GuiController(
        Configuration.GUI_DIRECTORY, Configuration.DEFAULT_GUI_LANGUAGE, () -> { }, model);
    private final ViewportLayout viewports = new ViewportLayout(model.getViewingCamera());
    private final HudContent hud = new HudContent(model, viewports);

    private static final TerrainSimplificationStatistics.Frame SIMPLIFICATION =
        new TerrainSimplificationStatistics.Frame(2, 262144L, 900L, 33L, 1);

    private List<String> lines(boolean uniformLod) {
        return hud.statusLines(new HudFrameStats(7, 3 * MB, 11, 5 * MB, 0L, 0, uniformLod, SIMPLIFICATION));
    }

    @Test
    void writesOneLineForEachHudSection() {
        assertEquals(5, lines(false).size());
    }

    @Test
    void addsTheSimplificationStatisticsOnlyForAViewDependentScheme() {
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.LIND1996);
        List<String> lines = lines(false);
        assertEquals(6, lines.size());
        assertTrue(lines.get(2).contains(model.text("IDM_LAYER_SUBDIVISION_LIND1996")), lines.get(2));
        assertTrue(lines.get(5).contains("900"), lines.get(5));
        assertTrue(lines.get(5).contains("33"), lines.get(5));
    }

    @Test
    void reportsTheStatisticsOfTheFrame() {
        String camera = lines(false).get(1);
        assertTrue(camera.contains("7 (3 MB)"), camera);
        String mode = lines(false).get(2);
        assertTrue(mode.contains("11 (5 MB)"), mode);
    }

    @Test
    void followsTheModelState() {
        String triangles = lines(false).get(2);
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.DIAMOND_SQUARE);
        String diamonds = lines(true).get(2);
        assertTrue(triangles.contains(model.text("IDM_LAYER_SUBDIVISION_TRIANGLE_EDGE")), triangles);
        assertTrue(triangles.contains(model.text("IDM_HUD_OFF")), triangles);
        assertTrue(diamonds.contains(model.text("IDM_LAYER_SUBDIVISION_DIAMOND_SQUARE")), diamonds);
        assertTrue(diamonds.contains(model.text("IDM_HUD_ON")), diamonds);

        model.setOperationMode(OperationModes.PALETTE_BASED_IMAGE);
        assertTrue(lines(false).get(2).contains(model.text("IDM_LAYER_ELEVATION_PALETTE")));
    }

    @Test
    void namesTheSelectedViewAndTheLayout() {
        viewports.addView();
        String camera = lines(false).get(1);
        assertTrue(camera.contains(model.text("IDM_HUD_VIEW_TITLE", 2)), camera);
        assertEquals(model.text("IDM_HUD_VIEW_TITLE", 1),
            HudContent.viewTitle(model, viewports, viewports.mainView()));
    }

    @Test
    void addsTheTotalGpuMemoryOfBothRenderers() {
        assertEquals(8 * MB, new HudFrameStats(0, 3 * MB, 0, 5 * MB, 0L, 0, false, SIMPLIFICATION).totalGpuBytes());
    }

    @Test
    void formatsBytesInTheLargestFittingUnit() {
        assertEquals("0 B", HudContent.formatBytes(0));
        assertEquals("1023 B", HudContent.formatBytes(1023));
        assertEquals("1.0 KiB", HudContent.formatBytes(1024));
        assertEquals("1.5 MiB", HudContent.formatBytes(MB + MB / 2));
        assertEquals("2048.0 TiB", HudContent.formatBytes(2048L * 1024L * 1024L * 1024L * 1024L));
    }
}
