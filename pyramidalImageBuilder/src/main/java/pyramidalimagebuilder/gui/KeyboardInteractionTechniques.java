package pyramidalimagebuilder.gui;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import pyramidalimagebuilder.model.PyramidalImageModel;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.RendererConfigurationController;

public final class KeyboardInteractionTechniques implements KeyListener {
    private final PyramidalImageModel model;
    private final Runnable closeAction;
    private final Runnable repaintAction;
    private final RendererConfigurationController renderingConfigurationController;

    public KeyboardInteractionTechniques(
        PyramidalImageModel model,
        Runnable closeAction,
        Runnable repaintAction
    ) {
        this.model = model;
        this.closeAction = closeAction;
        this.repaintAction = repaintAction;
        this.renderingConfigurationController = model == null
            ? null
            : new RendererConfigurationController(model.getRenderingConfiguration().getDelegate());
    }

    @Override
    public void keyPressed(KeyEvent e) {
        vsdk.toolkit.gui.KeyEvent event = AwtSystem.awt2vsdkEvent(e);

        if (event.keycode == vsdk.toolkit.gui.KeyEvent.KEY_ESC && closeAction != null) {
            closeAction.run();
            return;
        }
        if (model == null) {
            return;
        }
        if (renderingConfigurationController != null
            && renderingConfigurationController.processKeyPressedEvent(event)) {
            if (repaintAction != null) {
                repaintAction.run();
            }
            return;
        }
        if (event.keycode == vsdk.toolkit.gui.KeyEvent.KEY_1) {
            if (model.selectPreviousTileMatrix()) {
                System.out.println("TileMatrix size (columns x rows): " + model.selectedTileMatrixSizeText());
                if (repaintAction != null) {
                    repaintAction.run();
                }
            }
            return;
        }
        if (event.keycode == vsdk.toolkit.gui.KeyEvent.KEY_2) {
            if (model.selectNextTileMatrix()) {
                System.out.println("TileMatrix size (columns x rows): " + model.selectedTileMatrixSizeText());
                if (repaintAction != null) {
                    repaintAction.run();
                }
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }
}
