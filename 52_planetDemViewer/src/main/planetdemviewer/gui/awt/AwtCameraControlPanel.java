package planetdemviewer.gui.awt;

import java.awt.BorderLayout;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import planetdemviewer.model.dialog.CameraControlPresenter;
import vsdk.toolkit.gui.widget.Widget;

/** Swing view of the camera control. Like the layer tree, it never takes keyboard focus. */
final class AwtCameraControlPanel extends JPanel {
    private final transient CameraControlPresenter presenter;
    private final JComboBox<String> select = new JComboBox<>();
    /** Tells apart the select following the model from the user choosing an option. */
    private boolean followingModel;

    AwtCameraControlPanel(CameraControlPresenter presenter) {
        super(new BorderLayout());
        this.presenter = presenter;
        setBorder(BorderFactory.createEmptyBorder(4, 6, 6, 6));
        select.setFocusable(false);
        select.addActionListener(event -> {
            if (!followingModel && select.getSelectedIndex() >= 0) {
                presenter.select(select.getSelectedIndex());
            }
        });
        add(select, BorderLayout.CENTER);
        presenter.setChangeListener(() -> SwingUtilities.invokeLater(this::refresh));
    }

    void rebuild(Widget definitions) {
        followingModel = true;
        select.setModel(new DefaultComboBoxModel<>(presenter.optionLabels(definitions).toArray(String[]::new)));
        followingModel = false;
        refresh();
    }

    private void refresh() {
        followingModel = true;
        select.setSelectedIndex(presenter.selectedOption());
        followingModel = false;
    }

    JComboBox<String> select() {
        return select;
    }
}
