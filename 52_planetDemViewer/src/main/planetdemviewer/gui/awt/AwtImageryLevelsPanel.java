package planetdemviewer.gui.awt;

import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import planetdemviewer.model.dialog.ImageryLevelsPresenter;
import vsdk.toolkit.gui.widget.Widget;

/**
 * Swing view of the independent color levels, shown in the side panel only while the presenter
 * asks for it. Like the layer tree, it never takes keyboard focus. The levels change with the
 * camera too, without any event, so they are read periodically.
 */
final class AwtImageryLevelsPanel extends JPanel {
    private static final int COUNTS_REFRESH_MILLISECONDS = 200;

    private final transient ImageryLevelsPresenter presenter;
    private final JCheckBox independent = new JCheckBox();
    private final JLabel geometryLevels = new JLabel();
    private final JLabel colorLevels = new JLabel();
    private final JLabel colorBlocks = new JLabel();
    private final JLabel colorSources = new JLabel();
    private final Timer countsRefresh = new Timer(COUNTS_REFRESH_MILLISECONDS, event -> refresh());

    AwtImageryLevelsPanel(ImageryLevelsPresenter presenter) {
        this.presenter = presenter;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        independent.setFocusable(false);
        independent.addActionListener(event -> presenter.setIndependent(independent.isSelected()));
        for (JComponent line : new JComponent[] {independent, geometryLevels, colorLevels, colorBlocks, colorSources}) {
            line.setAlignmentX(Component.LEFT_ALIGNMENT);
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
        independent.setText(presenter.independentText());
        independent.setSelected(presenter.isIndependent());
        geometryLevels.setText(presenter.geometryLevelsText());
        colorLevels.setText(presenter.colorLevelsText());
        colorBlocks.setText(presenter.colorBlocksText());
        colorSources.setText(presenter.colorSourcesText());
    }

    JCheckBox independentCheckBox() {
        return independent;
    }

    JLabel colorLevelsLabel() {
        return colorLevels;
    }
}
