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
import planetdemviewer.terrain.TerrainSubdivisionScheme;
import vsdk.toolkit.gui.dialog.components.DialogTreeNode;

class LayerTreePresenterTest {
    private final PlanetViewerModel model = new PlanetViewerModel();
    private final LayerTreePresenter presenter = new LayerTreePresenter(model);
    private final GuiController gui = new GuiController(Configuration.GUI_DIRECTORY,
        Configuration.DEFAULT_GUI_LANGUAGE, () -> { }, model);

    private DialogTreeNode option(int group, int option) {
        return presenter.hierarchy(gui.definitions()).getChildren().get(group).getChildren().get(option);
    }

    @Test
    void startsWithTerrainAndImageryAndWithoutPaletteImage() {
        model.setImageryAvailable(true);
        assertTrue(presenter.isSelected(option(0, 0)));
        assertFalse(presenter.isSelected(option(0, 1)));
        assertFalse(presenter.isSelected(option(1, 0)));
        assertTrue(presenter.isSelected(option(1, 1)));
    }

    @Test
    void radioOptionsChangeTheModelAndNotifyTheView() {
        model.setImageryAvailable(true);
        List<String> changes = new ArrayList<>();
        presenter.setChangeListener(() -> changes.add("changed"));

        presenter.activate(option(1, 0));
        assertFalse(model.isImageryVisible());
        presenter.activate(option(0, 1));
        assertEquals(OperationModes.PALETTE_BASED_IMAGE, model.getOperationMode());
        assertEquals(2, changes.size());
    }

    @Test
    void treeFollowsTheKeyboardActions() {
        model.setImageryAvailable(true);
        model.toggleImageryVisible();
        assertTrue(presenter.isSelected(option(1, 0)));
        model.cycleOperationMode();
        assertTrue(presenter.isSelected(option(0, 1)));
    }

    @Test
    void colorIsDisabledWithoutImageryOrOutsideTheTerrain() {
        assertFalse(presenter.isEnabled(option(1, 1)));
        assertFalse(model.isImageryVisible());
        model.toggleImageryVisible();
        assertTrue(model.isImageryEnabled());

        model.setImageryAvailable(true);
        assertTrue(presenter.isEnabled(option(1, 0)));
        model.setOperationMode(OperationModes.PALETTE_BASED_IMAGE);
        assertFalse(presenter.isEnabled(option(1, 0)));
        presenter.activate(option(1, 0));
        assertTrue(model.isImageryEnabled());
    }

    @Test
    void subdivisionOptionsSelectTheSchemeOnlyOnTheTerrain() {
        assertTrue(presenter.isSelected(option(2, 0)));
        presenter.activate(option(2, 1));
        assertEquals(TerrainSubdivisionScheme.DIAMOND_SQUARE, model.getTerrainSubdivisionScheme());
        assertTrue(presenter.isSelected(option(2, 1)));
        assertFalse(presenter.isSelected(option(2, 0)));

        model.setOperationMode(OperationModes.PALETTE_BASED_IMAGE);
        assertFalse(presenter.isEnabled(option(2, 0)));
        presenter.activate(option(2, 0));
        assertEquals(TerrainSubdivisionScheme.DIAMOND_SQUARE, model.getTerrainSubdivisionScheme());
    }
}
