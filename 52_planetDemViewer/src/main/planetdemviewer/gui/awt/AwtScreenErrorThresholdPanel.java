package planetdemviewer.gui.awt;

import java.awt.Component;
import java.util.Dictionary;
import java.util.Hashtable;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import planetdemviewer.model.dialog.ScreenErrorThresholdPresenter;
import vsdk.toolkit.gui.widget.Widget;

/**
 * Swing view of the threshold details, shown in the side panel only while the presenter
 * asks for it. Like the layer tree, it never takes keyboard focus, so the viewer's keys
 * keep reaching the canvas. The triangle counts change with the camera too, without any
 * event, so they are read periodically.
 */
final class AwtScreenErrorThresholdPanel extends JPanel {
    private static final int COUNTS_REFRESH_MILLISECONDS = 200;

    private final transient ScreenErrorThresholdPresenter presenter;
    private final JLabel threshold = new JLabel();
    private final JSlider stops;
    private final JLabel originalTriangles = new JLabel();
    private final JLabel optimizedTriangles = new JLabel();
    private final Timer countsRefresh = new Timer(COUNTS_REFRESH_MILLISECONDS, event -> refresh());
    /** Tells apart the slider following the model, between stops too, from the user moving it. */
    private boolean followingModel;

    AwtScreenErrorThresholdPanel(ScreenErrorThresholdPresenter presenter) {
        this.presenter = presenter;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        stops = new JSlider(0, presenter.stopCount() - 1, 0);
        stops.setFocusable(false);
        stops.setMajorTickSpacing(1);
        stops.setSnapToTicks(true);
        stops.setPaintTicks(true);
        stops.setPaintLabels(true);
        stops.setLabelTable(stopLabels());
        stops.addChangeListener(event -> {
            if (!followingModel) {
                presenter.selectStop(stops.getValue());
            }
        });
        stops.addMouseWheelListener(event -> {
            int stop = stops.getValue() - event.getWheelRotation();
            stops.setValue(Math.max(stops.getMinimum(), Math.min(stops.getMaximum(), stop)));
        });
        for (Component line : new Component[] {threshold, stops, Box.createVerticalStrut(4),
                                               originalTriangles, optimizedTriangles}) {
            ((javax.swing.JComponent) line).setAlignmentX(Component.LEFT_ALIGNMENT);
            add(line);
        }
        presenter.setChangeListener(() -> SwingUtilities.invokeLater(this::refresh));
    }

    @Override
    public void addNotify() {
        super.addNotify();
        countsRefresh.start();
    }

    /** A running repeating timer keeps the event thread, and so the application, alive after the window closes. */
    @Override
    public void removeNotify() {
        countsRefresh.stop();
        super.removeNotify();
    }

    boolean isRefreshingCounts() {
        return countsRefresh.isRunning();
    }

    /** Labels the ends and the middle of the scale, since all the stops do not fit in the side panel. */
    private Dictionary<Integer, JLabel> stopLabels() {
        Dictionary<Integer, JLabel> labels = new Hashtable<>();
        int last = presenter.stopCount() - 1;
        for (int stop : new int[] {0, last / 2, last}) {
            labels.put(stop, new JLabel(presenter.stopText(stop)));
        }
        return labels;
    }

    void rebuild(Widget definitions) {
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(presenter.useDefinitions(definitions)),
            BorderFactory.createEmptyBorder(2, 6, 4, 6)));
        refresh();
    }

    private void refresh() {
        boolean visible = presenter.isVisible();
        if (visible != isVisible()) {
            setVisible(visible);
        }
        if (!visible) {
            return;
        }
        followingModel = true;
        stops.setValue(presenter.selectedStop());
        followingModel = false;
        threshold.setText(presenter.thresholdText());
        originalTriangles.setText(presenter.originalTrianglesText());
        optimizedTriangles.setText(presenter.optimizedTrianglesText());
    }

    JSlider slider() {
        return stops;
    }

    JLabel thresholdLabel() {
        return threshold;
    }

    JLabel originalTrianglesLabel() {
        return originalTriangles;
    }

    JLabel optimizedTrianglesLabel() {
        return optimizedTriangles;
    }
}
