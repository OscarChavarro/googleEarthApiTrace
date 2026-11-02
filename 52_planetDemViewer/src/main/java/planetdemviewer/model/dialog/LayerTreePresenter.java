package planetdemviewer.model.dialog;

import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import vsdk.toolkit.gui.dialog.components.DialogTreeNode;
import vsdk.toolkit.gui.widget.Widget;
import vsdk.toolkit.gui.widget.WidgetDialog;

/**
 * Layer tree behavior, with the tree's structure taken from dialogs.json; no
 * Swing types. Group nodes come from "column" elements, and radio options from
 * "action" elements, identified by an {@link Option}.
 */
public final class LayerTreePresenter {
    public static final String DIALOG_ID = "layers.panel";
    private static final String TREE_KIND = "tree";
    private static final String GROUP_KIND = "column";
    private static final String OPTION_KIND = "action";
    private static final String SHOW_TERRAIN_MESH = "showTerrainMesh";
    private static final String SHOW_PALETTE_IMAGE = "showPaletteImage";
    private static final String HIDE_IMAGERY = "hideImagery";
    private static final String SHOW_IMAGERY = "showImagery";

    private record Option(String action) { }

    private final PlanetViewerModel model;

    public LayerTreePresenter(PlanetViewerModel model) {
        this.model = model;
    }

    public void setChangeListener(Runnable listener) {
        model.setLayerStateListener(listener);
    }

    public String title(Widget definitions) {
        return definitions.getMessage(dialog(definitions).getTitleKey());
    }

    /** @return an unlabelled root whose children are the layer groups */
    public DialogTreeNode hierarchy(Widget definitions) {
        DialogTreeNode root = new DialogTreeNode("", null);
        for (WidgetDialog.Element element : dialog(definitions).getElements()) {
            if (TREE_KIND.equals(element.getKind())) {
                for (WidgetDialog.Element child : element.getChildren()) {
                    root.add(node(child, definitions));
                }
            }
        }
        return root;
    }

    private static DialogTreeNode node(WidgetDialog.Element element, Widget definitions) {
        String label = definitions.getMessage(element.getLabelKey());
        if (OPTION_KIND.equals(element.getKind())) {
            requireKnownAction(element.getAction());
            return new DialogTreeNode(label, new Option(element.getAction()));
        }
        if (!GROUP_KIND.equals(element.getKind())) {
            throw new IllegalArgumentException("Unsupported layer tree element: " + element.getKind());
        }
        DialogTreeNode group = new DialogTreeNode(label, null);
        for (WidgetDialog.Element child : element.getChildren()) {
            group.add(node(child, definitions));
        }
        return group;
    }

    private static WidgetDialog dialog(Widget definitions) {
        WidgetDialog dialog = definitions.getDialogById(DIALOG_ID);
        if (dialog == null) {
            throw new IllegalStateException("Missing dialog: " + DIALOG_ID);
        }
        return dialog;
    }

    private static void requireKnownAction(String action) {
        switch (action) {
            case SHOW_TERRAIN_MESH, SHOW_PALETTE_IMAGE, HIDE_IMAGERY, SHOW_IMAGERY -> { }
            default -> throw new IllegalArgumentException("Unknown layer action: " + action);
        }
    }

    public boolean isOption(DialogTreeNode node) {
        return node.getIdentity() instanceof Option;
    }

    public boolean isSelected(DialogTreeNode node) {
        if (!(node.getIdentity() instanceof Option option)) {
            return false;
        }
        return switch (option.action()) {
            case SHOW_TERRAIN_MESH -> model.getOperationMode() == OperationModes.BASIC_TRIANGULATION;
            case SHOW_PALETTE_IMAGE -> model.getOperationMode() == OperationModes.PALETTE_BASED_IMAGE;
            case HIDE_IMAGERY -> !model.isImageryVisible();
            default -> model.isImageryVisible();
        };
    }

    /** A group is enabled while any of its options is; color needs imagery and the 3D terrain. */
    public boolean isEnabled(DialogTreeNode node) {
        if (node.getIdentity() instanceof Option option) {
            boolean colorOption = HIDE_IMAGERY.equals(option.action()) || SHOW_IMAGERY.equals(option.action());
            return !colorOption || (model.isImageryAvailable()
                && model.getOperationMode() == OperationModes.BASIC_TRIANGULATION);
        }
        return node.getChildren().stream().anyMatch(this::isEnabled);
    }

    /** Selects a radio option; group nodes and disabled options are ignored. */
    public void activate(DialogTreeNode node) {
        if (!(node.getIdentity() instanceof Option option)) {
            return;
        }
        model.writeLocked(() -> {
            if (!isEnabled(node)) {
                return;
            }
            switch (option.action()) {
                case SHOW_TERRAIN_MESH -> model.setOperationMode(OperationModes.BASIC_TRIANGULATION);
                case SHOW_PALETTE_IMAGE -> model.setOperationMode(OperationModes.PALETTE_BASED_IMAGE);
                case HIDE_IMAGERY -> model.setImageryEnabled(false);
                default -> model.setImageryEnabled(true);
            }
        });
    }
}
