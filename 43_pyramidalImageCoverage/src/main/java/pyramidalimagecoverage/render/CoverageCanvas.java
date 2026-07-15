package pyramidalimagecoverage.render;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.JViewport;
import pyramidalimagecoverage.io.TileImageRepository;
import pyramidalimagecoverage.model.PyramidCatalog;
import pyramidalimagecoverage.model.PixelSize;
import pyramidalimagecoverage.model.RenderMode;
import pyramidalimagecoverage.model.TileRecord;
import pyramidalimagecoverage.model.ViewerModel;
import pyramidalimagecoverage.processing.LevelLayout;
import pyramidalimagecoverage.processing.SourceRegion;
import pyramidalimagecoverage.processing.TileSourceResolver;

public final class CoverageCanvas extends Canvas {
    private static final Color BACKGROUND = new Color(18, 18, 20);
    private static final Color UNSELECTED_BORDER = Color.BLACK;
    private static final Color SELECTED_BORDER = new Color(0, 255, 0);
    private static final Color HUD_BACKGROUND = new Color(0, 0, 0, 190);
    private static final Font HUD_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 14);

    private final ViewerModel model;
    private final TileImageRepository images;
    private final TileSourceResolver sourceResolver;
    private JViewport viewport;
    private LevelLayout layout = LevelLayout.choose(0, new PixelSize(1, 1));

    public CoverageCanvas(ViewerModel model, TileImageRepository images) {
        this.model = model;
        this.images = images;
        this.sourceResolver = new TileSourceResolver(model.catalog());
        setBackground(BACKGROUND);
        setIgnoreRepaint(false);
    }

    public void setViewport(JViewport viewport) {
        this.viewport = viewport;
    }

    public void setLayoutDescription(LevelLayout layout) {
        this.layout = layout;
        repaint();
    }

    public TileRecord tileAtCanvasPosition(int x, int y) {
        int pixelsPerTile = layout.pixelsPerTile();
        int originX = Math.max(0, (getWidth() - layout.contentSide()) / 2);
        int originY = Math.max(0, (getHeight() - layout.contentSide()) / 2);
        int relativeX = x - originX;
        int relativeY = y - originY;
        if (relativeX < 0 || relativeY < 0 || relativeX >= layout.contentSide() || relativeY >= layout.contentSide()) {
            return null;
        }
        int column = relativeX / pixelsPerTile;
        int northRow = relativeY / pixelsPerTile;
        if (column < 0 || column >= layout.matrixSide() || northRow < 0 || northRow >= layout.matrixSide()) {
            return null;
        }
        int southRow = layout.matrixSide() - 1 - northRow;
        return model.catalog().tileAt(model.selectedDepth(), column, southRow);
    }

    @Override
    public void update(Graphics graphics) {
        paint(graphics);
    }

    @Override
    public void paint(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            Rectangle visible = visibleRectangle();
            g.setColor(BACKGROUND);
            g.fillRect(visible.x, visible.y, visible.width, visible.height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            drawTiles(g, visible);
            drawHud(g, visible);
        }
        finally {
            g.dispose();
        }
    }

    private void drawTiles(Graphics2D g, Rectangle visible) {
        int pixelsPerTile = layout.pixelsPerTile();
        int originX = Math.max(0, (getWidth() - layout.contentSide()) / 2);
        int originY = Math.max(0, (getHeight() - layout.contentSide()) / 2);
        int firstColumn = clamp(Math.floorDiv(visible.x - originX, pixelsPerTile), 0, layout.matrixSide() - 1);
        int lastColumn = clamp(Math.floorDiv(visible.x + visible.width - 1 - originX, pixelsPerTile), 0, layout.matrixSide() - 1);
        int firstNorthRow = clamp(Math.floorDiv(visible.y - originY, pixelsPerTile), 0, layout.matrixSide() - 1);
        int lastNorthRow = clamp(Math.floorDiv(visible.y + visible.height - 1 - originY, pixelsPerTile), 0, layout.matrixSide() - 1);
        PyramidCatalog catalog = model.catalog();
        int depth = model.selectedDepth();

        for (int northRow = firstNorthRow; northRow <= lastNorthRow; northRow++) {
            int southRow = layout.matrixSide() - 1 - northRow;
            int y = originY + northRow * pixelsPerTile;
            for (int column = firstColumn; column <= lastColumn; column++) {
                TileRecord target = catalog.tileAt(depth, column, southRow);
                if (target == null) {
                    continue;
                }
                int x = originX + column * pixelsPerTile;
                drawTile(g, target, depth, column, southRow, x, y);
            }
        }
    }

    private void drawTile(
        Graphics2D g,
        TileRecord target,
        int depth,
        int column,
        int southRow,
        int x,
        int y
    ) {
        drawTileBorder(g, target, x, y);
        if (layout.mode() == RenderMode.NATIVE) {
            BufferedImage image = images.load(target.imagePath());
            if (image != null) {
                drawImage(g, image, x, y, 256, 0, 0, image.getWidth(), image.getHeight());
            }
            return;
        }
        int outputPixels = layout.imagePixelsPerTile();
        SourceRegion source = sourceResolver.resolve(depth, column, southRow, outputPixels);
        if (source == null) {
            return;
        }
        BufferedImage image = images.load(source.tile().imagePath());
        if (image == null) {
            return;
        }
        drawImage(g, image, x, y, outputPixels, source.x0(), source.y0(), source.x1(), source.y1());
    }

    private void drawTileBorder(Graphics2D g, TileRecord target, int x, int y) {
        if (layout.imagePixelsPerTile() <= 1) {
            return;
        }
        g.setColor(target.selected() ? SELECTED_BORDER : UNSELECTED_BORDER);
        g.fillRect(x, y, layout.pixelsPerTile(), layout.pixelsPerTile());
    }

    private void drawImage(
        Graphics2D g,
        BufferedImage image,
        int x,
        int y,
        int outputPixels,
        int sourceX0,
        int sourceY0,
        int sourceX1,
        int sourceY1
    ) {
        int inset = outputPixels == 1 ? 0 : 1;
        g.drawImage(
            image,
            x + inset, y + inset, x + inset + outputPixels, y + inset + outputPixels,
            sourceX0, sourceY0, sourceX1, sourceY1,
            null
        );
    }

    private void drawHud(Graphics2D g, Rectangle visible) {
        String[] lines = {
            "Quadtree depth [1/2]: " + model.selectedDepth() + " / " + model.catalog().maxDepth(),
            "Matrix: " + layout.matrixSide() + " x " + layout.matrixSide(),
            "LOD: " + layout.description(),
            "Fullscreen [F]: " + (isFullScreen() ? "on" : "off")
        };
        g.setFont(HUD_FONT);
        FontMetrics metrics = g.getFontMetrics();
        int lineHeight = metrics.getHeight();
        int width = 0;
        for (String line : lines) width = Math.max(width, metrics.stringWidth(line));
        int boxWidth = width + 20;
        int boxHeight = lines.length * lineHeight + 16;
        int x = visible.x + visible.width - boxWidth - 12;
        int y = visible.y + 12;
        g.setColor(HUD_BACKGROUND);
        g.fillRect(x, y, boxWidth, boxHeight);
        g.setColor(Color.WHITE);
        int baseline = y + 8 + metrics.getAscent();
        for (String line : lines) {
            g.drawString(line, x + 10, baseline);
            baseline += lineHeight;
        }
    }

    private boolean isFullScreen() {
        return javax.swing.SwingUtilities.getWindowAncestor(this) instanceof javax.swing.JFrame frame
            && frame.getGraphicsConfiguration().getDevice().getFullScreenWindow() == frame;
    }

    private Rectangle visibleRectangle() {
        return viewport == null ? new Rectangle(0, 0, getWidth(), getHeight()) : viewport.getViewRect();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
