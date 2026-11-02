package planetdemviewer.model.dialog;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.function.Supplier;
import planetdemviewer.config.Configuration;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.render.terrain.TerrainSimplificationCounts;
import vsdk.toolkit.gui.widget.Widget;
import vsdk.toolkit.gui.widget.WidgetDialog;

/**
 * Behavior of the details of a view-dependent subdivision scheme, with its texts taken
 * from dialogs.json; no Swing types. The threshold moves over a fixed list of stops. A
 * threshold set by other means may lie between two stops: the texts show its real value,
 * and the selected stop is the nearest one.
 */
public final class ScreenErrorThresholdPresenter {
    public static final String DIALOG_ID = "lind1996.details";
    private static final String THRESHOLD = "threshold";
    private static final String DEPENDENCIES_CROSS_TILES = "dependenciesCrossTiles";
    private static final String ORIGINAL_TRIANGLES = "originalTriangles";
    private static final String OPTIMIZED_TRIANGLES = "optimizedTriangles";

    private final PlanetViewerModel model;
    private final Supplier<TerrainSimplificationCounts> counts;
    private final double[] stops = Configuration.screenErrorThresholdStopsPixels();
    private WidgetDialog dialog;

    public ScreenErrorThresholdPresenter(PlanetViewerModel model, Supplier<TerrainSimplificationCounts> counts) {
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
        return model.getOperationMode() == OperationModes.BASIC_TRIANGULATION
            && model.getTerrainSubdivisionScheme().isViewDependent();
    }

    public int stopCount() {
        return stops.length;
    }

    public String stopText(int stop) {
        return number(stops[stop]);
    }

    /** The stop at the threshold of the model, or the nearest one. */
    public int selectedStop() {
        double threshold = model.getScreenErrorThresholdPixels();
        int nearest = 0;
        for (int stop = 1; stop < stops.length; stop++) {
            if (Math.abs(stops[stop] - threshold) < Math.abs(stops[nearest] - threshold)) {
                nearest = stop;
            }
        }
        return nearest;
    }

    public void selectStop(int stop) {
        model.writeLocked(() -> model.setScreenErrorThresholdPixels(stops[stop]));
    }

    public String thresholdText() {
        return text(THRESHOLD, number(model.getScreenErrorThresholdPixels()));
    }

    public String dependenciesCrossTilesText() {
        return text(DEPENDENCIES_CROSS_TILES);
    }

    public boolean isDependenciesCrossTiles() {
        return model.isDependenciesCrossTiles();
    }

    public void setDependenciesCrossTiles(boolean enabled) {
        model.writeLocked(() -> model.setDependenciesCrossTiles(enabled));
    }

    public String originalTrianglesText() {
        return text(ORIGINAL_TRIANGLES, count(counts.get().trianglesBeforeSimplification()));
    }

    public String optimizedTrianglesText() {
        TerrainSimplificationCounts frame = counts.get();
        double percent = frame.trianglesBeforeSimplification() == 0L ? 0.0
            : 100.0 * frame.trianglesRendered() / frame.trianglesBeforeSimplification();
        return text(OPTIMIZED_TRIANGLES, count(frame.trianglesRendered()),
            String.format(Locale.ROOT, "%.1f", percent));
    }

    private String text(String elementId, Object... arguments) {
        for (WidgetDialog.Element element : dialog.getElements()) {
            if (elementId.equals(element.getId())) {
                return model.text(element.getLabelKey(), arguments);
            }
        }
        throw new IllegalStateException("Missing element " + elementId + " in dialog " + DIALOG_ID);
    }

    private static String number(double value) {
        return value == 0.0 ? "0" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** A space separates thousands, which reads the same in every language of the GUI. */
    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }
}
