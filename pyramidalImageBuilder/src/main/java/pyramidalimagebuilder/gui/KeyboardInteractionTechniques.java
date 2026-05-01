package pyramidalimagebuilder.gui;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import pyramidalimagebuilder.model.PyramidalImageModel;

public final class KeyboardInteractionTechniques implements KeyListener {
    private final PyramidalImageModel model;
    private final Runnable closeAction;
    private final Runnable repaintAction;

    public KeyboardInteractionTechniques(
        PyramidalImageModel model,
        Runnable closeAction,
        Runnable repaintAction
    ) {
        this.model = model;
        this.closeAction = closeAction;
        this.repaintAction = repaintAction;
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE && closeAction != null) {
            closeAction.run();
            return;
        }
        if (model == null) {
            return;
        }
        if (e.getKeyCode() == KeyEvent.VK_1) {
            if (model.selectPreviousTileMatrix()) {
                System.out.println("TileMatrix size (columns x rows): " + model.selectedTileMatrixSizeText());
                if (repaintAction != null) {
                    repaintAction.run();
                }
            }
            return;
        }
        if (e.getKeyCode() == KeyEvent.VK_2) {
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
