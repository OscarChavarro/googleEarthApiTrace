package planetdemviewer.model.dialog;

import java.util.function.Supplier;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.render.terrain.TerrainImageryCounts;
import vsdk.toolkit.gui.widget.Widget;
import vsdk.toolkit.gui.widget.WidgetDialog;

/**
 * Behavior of the option that lets color and geometry come from different levels of their
 * quadtrees, with its texts taken from dialogs.json; no Swing types. It applies to the 3D
 * terrain with color, in the subdivision schemes that allow it.
 */
public final class ImageryLevelsPresenter {
    public static final String DIALOG_ID = "imageryLevels.details";
    private static final String INDEPENDENT = "independentImageryLevels";
    private static final String GEOMETRY_LEVELS = "geometryLevels";
    private static final String COLOR_LEVELS = "colorLevels";
    private static final String COLOR_BLOCKS = "colorBlocks";
    private static final String COLOR_SOURCES = "colorSources";

    private final PlanetViewerModel model;
    private final Supplier<TerrainImageryCounts> counts;
    private WidgetDialog dialog;

    public ImageryLevelsPresenter(PlanetViewerModel model, Supplier<TerrainImageryCounts> counts) {
        this.model = model;
        this.counts = counts;
    }

    public void setChangeListener(Runnable listener) {
        model.addLayerStateListener(listener);
    }

    /** Returns the title, in the language of these definitions. */
    public String useDefinitions(Widget definitions) {
        dialog = definitions.getDialogById(DIALOG_ID);
        if (dialog == null) {
            throw new IllegalStateException("Missing dialog: " + DIALOG_ID);
        }
        return definitions.getMessage(dialog.getTitleKey());
    }

    public boolean isVisible() {
        return model.getOperationMode() == OperationModes.BASIC_TRIANGULATION && model.isImageryVisible()
            && model.getTerrainSubdivisionScheme().allowsImageryBeyondGeometryLevel();
    }

    public String independentText() {
        return text(INDEPENDENT);
    }

    public boolean isIndependent() {
        return model.isIndependentImageryLevels();
    }

    public void setIndependent(boolean enabled) {
        model.writeLocked(() -> model.setIndependentImageryLevels(enabled));
    }

    public String geometryLevelsText() {
        TerrainImageryCounts frame = counts.get();
        return text(GEOMETRY_LEVELS, range(frame.minGeometryLevel(), frame.maxGeometryLevel()));
    }

    public String colorLevelsText() {
        TerrainImageryCounts frame = counts.get();
        return text(COLOR_LEVELS, range(frame.minColorLevel(), frame.maxColorLevel()));
    }

    public String colorBlocksText() {
        return text(COLOR_BLOCKS, counts.get().blocks());
    }

    public String colorSourcesText() {
        TerrainImageryCounts frame = counts.get();
        return text(COLOR_SOURCES, frame.coarserBlocks(), frame.finerBlocks(), frame.blocksWithoutColor());
    }

    private static String range(int low, int high) {
        return low < 0 ? "-" : low == high ? Integer.toString(low) : low + " - " + high;
    }

    private String text(String elementId, Object... arguments) {
        for (WidgetDialog.Element element : dialog.getElements()) {
            if (elementId.equals(element.getId())) {
                return model.text(element.getLabelKey(), arguments);
            }
        }
        throw new IllegalStateException("Missing element " + elementId + " in dialog " + DIALOG_ID);
    }
}
