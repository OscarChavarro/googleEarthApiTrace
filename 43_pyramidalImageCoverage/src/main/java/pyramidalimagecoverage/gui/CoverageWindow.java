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
    private final TileImageRepository images;
    private final Runnable refreshCoverage;

    public CoverageWindow(ViewerModel model, TileImageRepository images, Runnable refreshCoverage) {
        this.model = model;
        this.images = images;
        this.refreshCoverage = refreshCoverage;
    }

    public void show() {
        Rectangle maximumBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        JFrame frame = new JFrame("Pyramidal Image Coverage");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        CoverageCanvas canvas = new CoverageCanvas(model, images);
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
        new MouseInteractionTechniques(model, canvas).install();

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
            public void cycleGridMode() {
                model.cycleGridMode();
            }

            @Override
            public void refreshCoverage() {
                refreshCoverage.run();
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
        int halfWidth = maximumBounds.width / 2;
        frame.setBounds(
            maximumBounds.x + halfWidth,
            maximumBounds.y,
            maximumBounds.width - halfWidth,
            maximumBounds.height
        );
        frame.setVisible(true);
        SwingUtilities.invokeLater(layout::refresh);
    }
}
