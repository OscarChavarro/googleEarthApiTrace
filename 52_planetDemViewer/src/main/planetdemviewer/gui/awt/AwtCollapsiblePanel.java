package planetdemviewer.gui.awt;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * A category of controls: a title with a triangle, pointing right while collapsed and down while
 * expanded, and the controls below it. It takes no keyboard focus, so the viewer's keys keep
 * reaching the canvas. The triangle is drawn, since not every font has those characters.
 */
final class AwtCollapsiblePanel extends JPanel {
    private static final int TRIANGLE_SIZE = 10;

    private final JLabel header = new JLabel();
    private final JComponent content;

    AwtCollapsiblePanel(JComponent content) {
        super(new BorderLayout());
        this.content = content;
        header.setOpaque(true);
        header.setBackground(headerBackground());
        header.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.setIcon(new TriangleIcon());
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event)) {
                    setExpanded(!isExpanded());
                }
            }
        });
        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
    }

    private static Color headerBackground() {
        Color panel = new JPanel().getBackground();
        return panel == null ? Color.LIGHT_GRAY : panel.darker();
    }

    void setTitle(String title) {
        header.setText(title);
    }

    boolean isExpanded() {
        return content.isVisible();
    }

    void setExpanded(boolean expanded) {
        content.setVisible(expanded);
        header.repaint();
        revalidate();
    }

    JLabel header() {
        return header;
    }

    private final class TriangleIcon implements Icon {
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(component.getForeground());
            int size = TRIANGLE_SIZE;
            g.fill(isExpanded()
                ? new Polygon(new int[] {x, x + size, x + size / 2}, new int[] {y + 1, y + 1, y + size - 1}, 3)
                : new Polygon(new int[] {x + 1, x + size - 1, x + 1}, new int[] {y, y + size / 2, y + size}, 3));
            g.dispose();
        }

        @Override
        public int getIconWidth() {
            return TRIANGLE_SIZE;
        }

        @Override
        public int getIconHeight() {
            return TRIANGLE_SIZE;
        }
    }
}
