package planetdemviewer.gui.awt;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * The categories of controls of the side panel, stacked from the top. Each one takes the height
 * of its content, and the scroll pane around this panel handles those that do not fit.
 */
final class AwtControlCategoriesPanel extends JPanel implements Scrollable {
    private static final int SCROLL_UNIT_PIXELS = 16;

    AwtControlCategoriesPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    }

    void addCategory(AwtCollapsiblePanel category) {
        category.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(category);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return SCROLL_UNIT_PIXELS;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
