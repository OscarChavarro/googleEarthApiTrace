package planetdemviewer.model.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import planetdemviewer.config.Configuration;
import planetdemviewer.gui.GuiController;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.render.terrain.TerrainImageryCounts;
import planetdemviewer.render.terrain.TerrainSubdivisionScheme;

class ImageryLevelsPresenterTest {
    private final PlanetViewerModel model = new PlanetViewerModel();
    private final GuiController gui = new GuiController(Configuration.GUI_DIRECTORY,
        Configuration.DEFAULT_GUI_LANGUAGE, () -> { }, model);
    private TerrainImageryCounts counts = new TerrainImageryCounts(34, 12, 12, 10, 19, 5, 20, 1);
    private final ImageryLevelsPresenter presenter = new ImageryLevelsPresenter(model, () -> counts);

    @BeforeEach
    void useDefinitions() {
        presenter.useDefinitions(gui.definitions());
        model.setImageryAvailable(true);
    }

    @Test
    void appliesToTheSchemesThatCanPartitionTheirTriangles() {
        assertFalse(presenter.isVisible(), "The row-by-row scheme starts selected");
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.DIAMOND_SQUARE);
        assertTrue(presenter.isVisible());
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.LIND1996);
        assertTrue(presenter.isVisible());
    }

    @Test
    void doesNotApplyWithoutColorOrOutsideTheTerrain() {
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.DIAMOND_SQUARE);
        model.setImageryEnabled(false);
        assertFalse(presenter.isVisible());
        model.setImageryEnabled(true);
        model.setOperationMode(OperationModes.PALETTE_BASED_IMAGE);
        assertFalse(presenter.isVisible());
    }

    @Test
    void theOptionOnlyActsInTheSchemesThatAllowIt() {
        assertTrue(presenter.isIndependent(), "On by default");
        assertFalse(model.usesIndependentImageryLevels(), "Not in the row-by-row scheme");
        model.setTerrainSubdivisionScheme(TerrainSubdivisionScheme.LIND1996);
        assertTrue(model.usesIndependentImageryLevels());
        presenter.setIndependent(false);
        assertFalse(model.usesIndependentImageryLevels());
    }

    @Test
    void showsTheLevelsInTheLanguageOfTheGui() {
        assertEquals("Independent color levels", presenter.independentText());
        assertEquals("Geometry levels: 12", presenter.geometryLevelsText());
        assertEquals("Color levels: 10 - 19", presenter.colorLevelsText());
        assertEquals("Color blocks: 34", presenter.colorBlocksText());
        assertEquals("Coarser 5, finer 20, none 1", presenter.colorSourcesText());

        gui.execute(GuiController.COMMAND_LANGUAGE_PREFIX + "SPANISH");
        presenter.useDefinitions(gui.definitions());
        assertEquals("Niveles de color independientes", presenter.independentText());
        assertEquals("Niveles de color: 10 - 19", presenter.colorLevelsText());
    }

    @Test
    void noBlockYetHasNoLevel() {
        counts = new TerrainImageryCounts(0, -1, -1, -1, -1, 0, 0, 0);
        assertEquals("Color levels: -", presenter.colorLevelsText());
    }
}
