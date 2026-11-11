package planetdemviewer.model.dialog;

import java.util.ArrayList;
import java.util.List;
import planetdemviewer.model.CameraControlMode;
import planetdemviewer.model.PlanetViewerModel;
import vsdk.toolkit.gui.widget.Widget;
import vsdk.toolkit.gui.widget.WidgetDialog;

/**
 * Behavior of the camera control category, with its options taken from dialogs.json; no Swing
 * types. The select is a "column" element, and each of its options an "action" element, because
 * Vitral's dialog format has no element of its own for a select.
 */
public final class CameraControlPresenter {
    public static final String DIALOG_ID = "cameraControl.panel";
    private static final String USE_AQUYNZA = "useAquynzaCameraControl";
    private static final String USE_LEVELS_BROWSER = "useLevelsBrowserCameraControl";

    private final PlanetViewerModel model;
    private final List<CameraControlMode> modes = new ArrayList<>();

    public CameraControlPresenter(PlanetViewerModel model) {
        this.model = model;
    }

    public void setChangeListener(Runnable listener) {
        model.addLayerStateListener(listener);
    }

    public String title(Widget definitions) {
        return definitions.getMessage(dialog(definitions).getTitleKey());
    }

    /** @return the name of each option, in the language of these definitions */
    public List<String> optionLabels(Widget definitions) {
        List<String> labels = new ArrayList<>();
        modes.clear();
        for (WidgetDialog.Element select : dialog(definitions).getElements()) {
            for (WidgetDialog.Element option : select.getChildren()) {
                modes.add(mode(option.getAction()));
                labels.add(definitions.getMessage(option.getLabelKey()));
            }
        }
        return labels;
    }

    private static CameraControlMode mode(String action) {
        return switch (action) {
            case USE_AQUYNZA -> CameraControlMode.AQUYNZA;
            case USE_LEVELS_BROWSER -> CameraControlMode.LEVELS_BROWSER;
            default -> throw new IllegalArgumentException("Unknown camera control action: " + action);
        };
    }

    public int selectedOption() {
        return modes.indexOf(model.getCameraControlMode());
    }

    public void select(int option) {
        model.writeLocked(() -> model.setCameraControlMode(modes.get(option)));
    }

    private static WidgetDialog dialog(Widget definitions) {
        WidgetDialog dialog = definitions.getDialogById(DIALOG_ID);
        if (dialog == null) {
            throw new IllegalStateException("Missing dialog: " + DIALOG_ID);
        }
        return dialog;
    }
}
