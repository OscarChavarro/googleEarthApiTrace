package pyramidalimagecoverage.gui;

import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import pyramidalimagecoverage.processing.KeyboardCommandProcessor;
import vsdk.toolkit.gui.AwtSystem;

/** AWT adapter only; command decisions live in KeyboardCommandProcessor. */
public final class KeyboardInteractionTechniques implements KeyEventDispatcher {
    private final KeyboardCommandProcessor commandProcessor;

    public KeyboardInteractionTechniques(KeyboardCommandProcessor commandProcessor) {
        this.commandProcessor = commandProcessor;
    }

    public void install() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
    }

    @Override
    public boolean dispatchKeyEvent(java.awt.event.KeyEvent event) {
        if (event.getID() != java.awt.event.KeyEvent.KEY_PRESSED) {
            return false;
        }
        return commandProcessor.process(AwtSystem.awt2vsdkEvent(event));
    }
}
