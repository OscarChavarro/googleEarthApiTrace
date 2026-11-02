package planetdemviewer.gui.awt;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import planetdemviewer.model.dialog.LayerTreePresenter;
import vsdk.toolkit.gui.dialog.components.DialogTreeNode;
import vsdk.toolkit.gui.widget.Widget;

/**
 * Swing view of the layer tree. It never takes keyboard focus, so the viewer's
 * keys keep reaching the canvas after a click on the tree.
 */
final class AwtLayerTreePanel extends JPanel {
    private final LayerTreePresenter presenter;
    private final JLabel title = new JLabel();
    private final JTree tree = new JTree(new DefaultTreeModel(null));

    AwtLayerTreePanel(LayerTreePresenter presenter) {
        super(new BorderLayout());
        this.presenter = presenter;
        title.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(0);
        tree.setFocusable(false);
        tree.setSelectionModel(null);
        tree.setCellRenderer(new LayerCellRenderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path != null && SwingUtilities.isLeftMouseButton(event)) {
                    presenter.activate(dialogNode(path.getLastPathComponent()));
                }
            }
        });
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setFocusable(false);
        add(title, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        presenter.setChangeListener(() -> SwingUtilities.invokeLater(tree::repaint));
    }

    void rebuild(Widget definitions) {
        title.setText(presenter.title(definitions));
        tree.setModel(new DefaultTreeModel(treeNode(presenter.hierarchy(definitions))));
        for (int row = 0; row < tree.getRowCount(); row++) {
            tree.expandRow(row);
        }
    }

    private static DefaultMutableTreeNode treeNode(DialogTreeNode node) {
        DefaultMutableTreeNode result = new DefaultMutableTreeNode(node);
        for (DialogTreeNode child : node.getChildren()) {
            result.add(treeNode(child));
        }
        return result;
    }

    private static DialogTreeNode dialogNode(Object treeNode) {
        return (DialogTreeNode) ((DefaultMutableTreeNode) treeNode).getUserObject();
    }

    private final class LayerCellRenderer implements TreeCellRenderer {
        private final JLabel groupView = new JLabel();
        private final JRadioButton optionView = new JRadioButton();

        LayerCellRenderer() {
            optionView.setOpaque(false);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree owner, Object value, boolean selected,
            boolean expanded, boolean leaf, int row, boolean hasFocus) {
            DialogTreeNode node = dialogNode(value);
            if (presenter.isOption(node)) {
                optionView.setText(node.getLabel());
                optionView.setSelected(presenter.isSelected(node));
                optionView.setEnabled(presenter.isEnabled(node));
                return optionView;
            }
            groupView.setText(node.getLabel());
            groupView.setEnabled(presenter.isEnabled(node));
            return groupView;
        }
    }
}
