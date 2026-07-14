package pyramidalimagecoverage.processing;

import vsdk.toolkit.gui.KeyEvent;

/** Platform-neutral keyboard command mapping over Vitral key events. */
public final class KeyboardCommandProcessor {
    private final ViewerActions actions;

    public KeyboardCommandProcessor(ViewerActions actions) {
        this.actions = actions;
    }

    public boolean process(KeyEvent event) {
        if (event == null) {
            return false;
        }
        switch (event.keycode) {
            case KeyEvent.KEY_1 -> actions.previousDepth();
            case KeyEvent.KEY_2 -> actions.nextDepth();
            case KeyEvent.KEY_f, KeyEvent.KEY_F -> actions.toggleFullscreen();
            case KeyEvent.KEY_ESC -> actions.exit();
            default -> {
                return false;
            }
        }
        return true;
    }
}
