package pyramidalimagecoverage.processing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vsdk.toolkit.gui.KeyEvent;

class KeyboardCommandProcessorTest {
    @Test
    void mapsVitralEventsWithoutAwtOrSwing() {
        RecordingActions actions = new RecordingActions();
        KeyboardCommandProcessor processor = new KeyboardCommandProcessor(actions);

        assertTrue(processor.process(event(KeyEvent.KEY_1)));
        assertTrue(processor.process(event(KeyEvent.KEY_2)));
        assertTrue(processor.process(event(KeyEvent.KEY_f)));
        assertTrue(processor.process(event(KeyEvent.KEY_ESC)));
        assertFalse(processor.process(event(KeyEvent.KEY_a)));
        assertTrue(actions.previous && actions.next && actions.fullscreen && actions.exit);
    }

    private static KeyEvent event(int keycode) {
        KeyEvent event = new KeyEvent();
        event.keycode = keycode;
        return event;
    }

    private static final class RecordingActions implements ViewerActions {
        private boolean previous;
        private boolean next;
        private boolean fullscreen;
        private boolean exit;

        @Override public void previousDepth() { previous = true; }
        @Override public void nextDepth() { next = true; }
        @Override public void toggleFullscreen() { fullscreen = true; }
        @Override public void exit() { exit = true; }
    }
}
