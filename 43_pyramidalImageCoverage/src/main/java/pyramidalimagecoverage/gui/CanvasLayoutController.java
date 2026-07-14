package pyramidalimagecoverage.gui;

import java.awt.Dimension;
import javax.swing.SwingUtilities;
import javax.swing.JScrollPane;
import pyramidalimagecoverage.model.ViewerModel;
import pyramidalimagecoverage.model.PixelSize;
import pyramidalimagecoverage.model.ViewPosition;
import pyramidalimagecoverage.processing.LevelLayout;
import pyramidalimagecoverage.processing.ScrollCenterCalculator;
import pyramidalimagecoverage.render.CoverageCanvas;

public final class CanvasLayoutController {
    private final ViewerModel model;
    private final CoverageCanvas canvas;
    private final JScrollPane scrollPane;
    private int centeredScrollableDepth = -1;

    public CanvasLayoutController(ViewerModel model, CoverageCanvas canvas, JScrollPane scrollPane) {
        this.model = model;
        this.canvas = canvas;
        this.scrollPane = scrollPane;
    }

    public void refresh() {
        Dimension extent = scrollPane.getViewport().getExtentSize();
        if (extent.width <= 0 || extent.height <= 0) {
            return;
        }
        LevelLayout layout = LevelLayout.choose(model.selectedDepth(), sizeOf(extent));
        boolean centerActiveTiles = layout.scrollable() && centeredScrollableDepth != model.selectedDepth();
        if (!layout.scrollable()) {
            centeredScrollableDepth = -1;
        }
        int policy = layout.scrollable() ? JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS : JScrollPane.HORIZONTAL_SCROLLBAR_NEVER;
        int verticalPolicy = layout.scrollable() ? JScrollPane.VERTICAL_SCROLLBAR_ALWAYS : JScrollPane.VERTICAL_SCROLLBAR_NEVER;
        scrollPane.setHorizontalScrollBarPolicy(policy);
        scrollPane.setVerticalScrollBarPolicy(verticalPolicy);

        Dimension adjustedExtent = scrollPane.getViewport().getExtentSize();
        Dimension size = new Dimension(
            Math.max(layout.contentSide(), adjustedExtent.width),
            Math.max(layout.contentSide(), adjustedExtent.height)
        );
        canvas.setPreferredSize(size);
        canvas.setSize(size);
        canvas.setLayoutDescription(layout);
        scrollPane.revalidate();
        canvas.repaint();
        if (centerActiveTiles) {
            centeredScrollableDepth = model.selectedDepth();
            SwingUtilities.invokeLater(() -> centerActiveTiles(layout));
        }
    }

    private void centerActiveTiles(LevelLayout layout) {
        model.catalog().tileBoundsAt(model.selectedDepth()).ifPresent(bounds -> {
            ViewPosition position = ScrollCenterCalculator.viewPosition(
                bounds,
                layout.matrixSide(),
                layout.pixelsPerTile(),
                sizeOf(scrollPane.getViewport().getExtentSize()),
                sizeOf(canvas.getSize())
            );
            scrollPane.getViewport().setViewPosition(new java.awt.Point(position.x(), position.y()));
        });
    }

    private static PixelSize sizeOf(Dimension size) {
        return new PixelSize(size.width, size.height);
    }
}
