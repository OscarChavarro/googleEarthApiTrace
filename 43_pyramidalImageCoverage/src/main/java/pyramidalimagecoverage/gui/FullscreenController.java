package pyramidalimagecoverage.gui;

import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import javax.swing.JFrame;

public final class FullscreenController {
    private final JFrame frame;
    private final GraphicsDevice device;
    private Rectangle windowedBounds;

    public FullscreenController(JFrame frame, Rectangle initialBounds) {
        this.frame = frame;
        this.device = frame.getGraphicsConfiguration().getDevice();
        this.windowedBounds = new Rectangle(initialBounds);
    }

    public void toggle() {
        if (device.getFullScreenWindow() == frame) {
            leave();
        }
        else {
            enter();
        }
    }

    private void enter() {
        windowedBounds = frame.getBounds();
        frame.dispose();
        frame.setUndecorated(true);
        frame.setResizable(false);
        frame.setVisible(true);
        device.setFullScreenWindow(frame);
        frame.requestFocus();
    }

    private void leave() {
        device.setFullScreenWindow(null);
        frame.dispose();
        frame.setUndecorated(false);
        frame.setResizable(true);
        frame.setBounds(windowedBounds);
        frame.setVisible(true);
        frame.requestFocus();
    }
}
