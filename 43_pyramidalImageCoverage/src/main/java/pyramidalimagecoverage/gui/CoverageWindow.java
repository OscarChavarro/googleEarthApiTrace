package pyramidalimagecoverage.gui;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import pyramidalimagecoverage.io.TileImageRepository;
import pyramidalimagecoverage.model.ViewerModel;
import pyramidalimagecoverage.processing.KeyboardCommandProcessor;
import pyramidalimagecoverage.processing.ViewerActions;
import pyramidalimagecoverage.render.CoverageCanvas;

public final class CoverageWindow {
    private final ViewerModel model;

    public CoverageWindow(ViewerModel model) {
        this.model = model;
    }

    public void show() {
        Rectangle maximumBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        JFrame frame = new JFrame("Pyramidal Image Coverage");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        CoverageCanvas canvas = new CoverageCanvas(model, new TileImageRepository());
        JScrollPane scrollPane = new JScrollPane(canvas);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(canvas.getBackground());
        canvas.setViewport(scrollPane.getViewport());
        frame.add(scrollPane, BorderLayout.CENTER);

        CanvasLayoutController layout = new CanvasLayoutController(model, canvas, scrollPane);
        model.addChangeListener(() -> SwingUtilities.invokeLater(layout::refresh));
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                SwingUtilities.invokeLater(layout::refresh);
            }
        });
        scrollPane.getHorizontalScrollBar().addAdjustmentListener(event -> canvas.repaint());
        scrollPane.getVerticalScrollBar().addAdjustmentListener(event -> canvas.repaint());

        FullscreenController fullscreen = new FullscreenController(frame, maximumBounds);
        new KeyboardInteractionTechniques(new KeyboardCommandProcessor(new ViewerActions() {
            @Override
            public void previousDepth() {
                model.previousDepth();
            }

            @Override
            public void nextDepth() {
                model.nextDepth();
            }

            @Override
            public void toggleFullscreen() {
                fullscreen.toggle();
            }

            @Override
            public void exit() {
                frame.dispose();
                System.exit(0);
            }
        })).install();
        frame.setBounds(maximumBounds);
        frame.setVisible(true);
        SwingUtilities.invokeLater(layout::refresh);
    }
}
