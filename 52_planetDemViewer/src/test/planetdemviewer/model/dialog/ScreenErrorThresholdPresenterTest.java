package planetdemviewer.model.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import planetdemviewer.config.Configuration;
import planetdemviewer.gui.GuiController;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.render.terrain.TerrainSimplificationCounts;
import planetdemviewer.render.terrain.TerrainSubdivisionScheme;

class ScreenErrorThresholdPresenterTest {
    private final PlanetViewerModel model = new PlanetViewerModel();
    private TerrainSimplificationCounts counts = new TerrainSimplificationCounts(38, 4_884_133L, 422_879L, 0L, 1);
    private final ScreenErrorThresholdPresenter presenter = new ScreenErrorThresholdPresenter(model, () -> counts);
    private final GuiController gui = new GuiController(Configuration.GUI_DIRECTORY,
        Configuration.DEFAULT_GUI_LANGUAGE, () -> { }, model);

    @Test
    void appearsOnlyWithAViewDependentSchemeOnTheTerrain() {
        assertFalse(presenter.isVisible());
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.DIAMOND_SQUARE);
        assertFalse(presenter.isVisible());
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.LIND1996);
        assertTrue(presenter.isVisible());
        model.setOperationMode(OperationModes.PALETTE_BASED_IMAGE);
        assertFalse(presenter.isVisible());
    }

    @Test
    void theStopsAreAscendingAndIncludeTheDefaultThreshold() {
        for (int stop = 1; stop < presenter.stopCount(); stop++) {
            assertTrue(Double.parseDouble(presenter.stopText(stop - 1)) < Double.parseDouble(presenter.stopText(stop)));
        }
        assertEquals("0", presenter.stopText(0));
        assertEquals("1", presenter.stopText(presenter.selectedStop()));
    }

    @Test
    void selectingAStopChangesTheThresholdAndNotifies() {
        presenter.useDefinitions(gui.definitions());
        List<String> changes = new ArrayList<>();
        presenter.setChangeListener(() -> changes.add("changed"));
        presenter.selectStop(1);
        assertEquals(0.125, model.getScreenErrorThresholdPixels());
        assertEquals(1, presenter.selectedStop());
        assertEquals("τ: 0.125 px", presenter.thresholdText());
        assertEquals(1, changes.size());
    }

    @Test
    void aThresholdBetweenStopsKeepsItsValueInTheText() {
        presenter.useDefinitions(gui.definitions());
        model.setScreenErrorThresholdPixels(3.0);
        assertEquals("τ: 3 px", presenter.thresholdText());
        assertEquals("2", presenter.stopText(presenter.selectedStop()));
        assertEquals(3.0, model.getScreenErrorThresholdPixels());
    }

    @Test
    void showsBothTriangleCountsInTheLanguageOfTheGui() {
        presenter.useDefinitions(gui.definitions());
        assertEquals("Triangles: 4 884 133", presenter.originalTrianglesText());
        assertEquals("Optimized: 422 879 (8.7%)", presenter.optimizedTrianglesText());

        gui.execute(GuiController.COMMAND_LANGUAGE_PREFIX + "SPANISH");
        presenter.useDefinitions(gui.definitions());
        assertEquals("Triángulos: 4 884 133", presenter.originalTrianglesText());
        assertEquals("Optimizados: 422 879 (8.7%)", presenter.optimizedTrianglesText());
    }

    @Test
    void noTerrainYetIsNotADivisionByZero() {
        presenter.useDefinitions(gui.definitions());
        counts = new TerrainSimplificationCounts(0, 0L, 0L, 0L, 0);
        assertEquals("Optimized: 0 (0.0%)", presenter.optimizedTrianglesText());
    }
}
