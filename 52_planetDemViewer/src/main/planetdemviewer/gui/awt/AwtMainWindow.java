package planetdemviewer.gui.awt;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.WindowConstants;
import planetdemviewer.gui.GuiController;
import planetdemviewer.model.dialog.CameraControlPresenter;
import planetdemviewer.model.dialog.ImageryLevelsPresenter;
import planetdemviewer.model.dialog.LayerTreePresenter;
import planetdemviewer.model.dialog.ScreenErrorThresholdPresenter;
import vsdk.toolkit.gui.widget.Widget;
import vsdk.toolkit.render.swing.SwingGuiRenderer;

/**
 * Main window: menu bar, side panel with the categories of controls, and OpenGL canvas. Language and full-screen
 * changes update this same window, so the canvas keeps its OpenGL context and
 * loaded tiles.
 */
public final class AwtMainWindow extends JFrame {
    private static final int LAYER_PANEL_WIDTH = 260;

    private final transient GuiController gui;
    private final Component canvas;
    private final AwtLayerTreePanel layerPanel;
    private final AwtScreenErrorThresholdPanel thresholdPanel;
    private final AwtImageryLevelsPanel imageryLevelsPanel;
    private final AwtCameraControlPanel cameraControlPanel;
    private final transient LayerTreePresenter layers;
    private final transient CameraControlPresenter cameraControl;
    private final AwtCollapsiblePanel layersCategory;
    private final AwtCollapsiblePanel cameraControlCategory;
    private final JScrollPane sidePanel;
    private final JSplitPane split;
    private final int dividerSize;
    private int windowedDividerLocation = LAYER_PANEL_WIDTH;
    private boolean fullScreen;

    public AwtMainWindow(Component canvas, GuiController gui, LayerTreePresenter layers,
                         ScreenErrorThresholdPresenter threshold, ImageryLevelsPresenter imageryLevels,
                         CameraControlPresenter cameraControl, Runnable closeAction) {
        this.canvas = canvas;
        this.gui = gui;
        this.layers = layers;
        this.cameraControl = cameraControl;
        layerPanel = new AwtLayerTreePanel(layers);
        thresholdPanel = new AwtScreenErrorThresholdPanel(threshold);
        imageryLevelsPanel = new AwtImageryLevelsPanel(imageryLevels);
        JPanel detailsPanels = new JPanel(new BorderLayout());
        detailsPanels.add(thresholdPanel, BorderLayout.CENTER);
        detailsPanels.add(imageryLevelsPanel, BorderLayout.SOUTH);
        JPanel layersContent = new JPanel(new BorderLayout());
        layersContent.add(layerPanel, BorderLayout.CENTER);
        layersContent.add(detailsPanels, BorderLayout.SOUTH);
        layersCategory = new AwtCollapsiblePanel(layersContent);
        cameraControlPanel = new AwtCameraControlPanel(cameraControl);
        cameraControlCategory = new AwtCollapsiblePanel(cameraControlPanel);
        AwtControlCategoriesPanel categories = new AwtControlCategoriesPanel();
        categories.addCategory(layersCategory);
        categories.addCategory(cameraControlCategory);
        sidePanel = new JScrollPane(categories, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sidePanel.setFocusable(false);
        sidePanel.getVerticalScrollBar().setFocusable(false);

        // Inside a lightweight container, the split pane resizes the heavyweight
        // canvas instead of removing it and destroying its OpenGL context.
        JPanel canvasHost = new JPanel(new BorderLayout());
        canvasHost.add(canvas, BorderLayout.CENTER);
        canvasHost.setMinimumSize(new Dimension(160, 120));
        sidePanel.setMinimumSize(new Dimension(120, 120));
        split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidePanel, canvasHost);
        split.setContinuousLayout(true);
        split.setResizeWeight(0.0);
        split.setDividerLocation(LAYER_PANEL_WIDTH);
        dividerSize = split.getDividerSize();
        add(split, BorderLayout.CENTER);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeAction.run();
            }
        });
        setMinimumSize(new Dimension(960, 600));
        // Fill the usable desktop session without entering OS fullscreen mode.
        setBounds(GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds());

        gui.setDefinitionsListener(this::applyDefinitions);
        applyDefinitions();
    }

    private void applyDefinitions() {
        Widget definitions = gui.definitions();
        setTitle(definitions.getMessage("IDM_WINDOW_TITLE"));
        JMenuBar menuBar = SwingGuiRenderer.buildMenubar(definitions, null, event -> {
            gui.execute(event.getActionCommand());
            canvas.requestFocusInWindow();
        });
        menuBar.setVisible(!fullScreen);
        setJMenuBar(menuBar);
        layersCategory.setTitle(layers.title(definitions));
        cameraControlCategory.setTitle(cameraControl.title(definitions));
        layerPanel.rebuild(definitions);
        cameraControlPanel.rebuild(definitions);
        thresholdPanel.rebuild(definitions);
        imageryLevelsPanel.rebuild(definitions);
        revalidate();
        repaint();
    }

    /** Shows only the canvas across the whole screen, or returns to the normal window. */
    public void toggleFullScreen() {
        GraphicsDevice device = getGraphicsConfiguration().getDevice();
        fullScreen = !fullScreen;
        if (fullScreen) {
            windowedDividerLocation = split.getDividerLocation();
        }
        getJMenuBar().setVisible(!fullScreen);
        sidePanel.setVisible(!fullScreen);
        split.setDividerSize(fullScreen ? 0 : dividerSize);
        device.setFullScreenWindow(fullScreen ? this : null);
        if (!fullScreen) {
            split.setDividerLocation(windowedDividerLocation);
        }
        revalidate();
        canvas.requestFocusInWindow();
    }
}
