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
import java.nio.file.Files;
import java.util.Locale;
import javax.swing.JViewport;
import pyramidalimagecoverage.io.TileImageRepository;
import pyramidalimagecoverage.model.PyramidCatalog;
import pyramidalimagecoverage.model.PixelSize;
import pyramidalimagecoverage.model.RenderMode;
import pyramidalimagecoverage.model.TileAddress;
import pyramidalimagecoverage.model.TileDelta;
import pyramidalimagecoverage.model.TileRecord;
import pyramidalimagecoverage.model.ViewerModel;
import pyramidalimagecoverage.processing.LevelLayout;
import pyramidalimagecoverage.processing.SourceRegion;
import pyramidalimagecoverage.processing.TileSourceResolver;

public final class CoverageCanvas extends Canvas {
    private static final Color BACKGROUND = new Color(18, 18, 20);
    private static final Color MISSING_DATA = Color.RED;
    private static final Color UNSELECTED_BORDER = Color.BLACK;
    private static final Color SELECTED_BORDER = new Color(0, 255, 0);
    private static final Color SECONDARY_SELECTED_BORDER = new Color(255, 255, 0);
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
        TileAddress address = tileAddressAtCanvasPosition(x, y);
        return address == null ? null : model.catalog().tileAt(
            address.depth(), address.column(), address.southRow()
        );
    }

    public TileAddress tileAddressAtCanvasPosition(int x, int y) {
        int pixelsPerTile = layout.pixelsPerTile();
        int originX = Math.max(0, (getWidth() - layout.contentWidth()) / 2);
        int originY = Math.max(0, (getHeight() - layout.contentHeight()) / 2);
        int relativeX = x - originX;
        int relativeY = y - originY;
        if (relativeX < 0 || relativeY < 0
            || relativeX >= layout.contentWidth() || relativeY >= layout.contentHeight()) {
            return null;
        }
        int localColumn = relativeX / pixelsPerTile;
        int localNorthRow = relativeY / pixelsPerTile;
        if (localColumn < 0 || localColumn >= layout.visibleTiles().columnCount()
            || localNorthRow < 0 || localNorthRow >= layout.visibleTiles().rowCount()) {
            return null;
        }
        int column = layout.visibleTiles().minimumColumn() + localColumn;
        int southRow = layout.visibleTiles().maximumSouthRow() - localNorthRow;
        TileAddress address = TileAddress.fromCoordinates(model.selectedDepth(), column, southRow);
        if (!address.hasGeographicCoverage()) {
            return null;
        }
        if (model.selectedDepth() <= 1
            && !isInsideGeographicPartOfLowLevelTile(southRow, relativeY % pixelsPerTile)) {
            return null;
        }
        return address;
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
            drawSelectedTileFileHud(g, visible);
        }
        finally {
            g.dispose();
        }
    }

    private void drawTiles(Graphics2D g, Rectangle visible) {
        int pixelsPerTile = layout.pixelsPerTile();
        int originX = Math.max(0, (getWidth() - layout.contentWidth()) / 2);
        int originY = Math.max(0, (getHeight() - layout.contentHeight()) / 2);
        int columnCount = layout.visibleTiles().columnCount();
        int rowCount = layout.visibleTiles().rowCount();
        int firstLocalColumn = clamp(Math.floorDiv(visible.x - originX, pixelsPerTile), 0, columnCount - 1);
        int lastLocalColumn = clamp(
            Math.floorDiv(visible.x + visible.width - 1 - originX, pixelsPerTile), 0, columnCount - 1
        );
        int firstLocalNorthRow = clamp(Math.floorDiv(visible.y - originY, pixelsPerTile), 0, rowCount - 1);
        int lastLocalNorthRow = clamp(
            Math.floorDiv(visible.y + visible.height - 1 - originY, pixelsPerTile), 0, rowCount - 1
        );
        PyramidCatalog catalog = model.catalog();
        int depth = model.selectedDepth();

        for (int localNorthRow = firstLocalNorthRow; localNorthRow <= lastLocalNorthRow; localNorthRow++) {
            int southRow = layout.visibleTiles().maximumSouthRow() - localNorthRow;
            TileAddress address = TileAddress.fromCoordinates(depth, 0, southRow);
            if (!address.hasGeographicCoverage()) {
                continue;
            }
            int y = originY + localNorthRow * pixelsPerTile;
            for (int localColumn = firstLocalColumn; localColumn <= lastLocalColumn; localColumn++) {
                int column = layout.visibleTiles().minimumColumn() + localColumn;
                int x = originX + localColumn * pixelsPerTile;
                TileRecord target = catalog.tileAt(depth, column, southRow);
                if (target == null) {
                    drawMissingTile(g, depth, column, southRow, x, y);
                    continue;
                }
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
            else {
                drawMissingData(g, depth, southRow, x, y);
            }
            return;
        }
        int outputPixels = layout.imagePixelsPerTile();
        SourceRegion source = sourceResolver.resolve(depth, column, southRow, outputPixels);
        if (source == null) {
            drawMissingData(g, depth, southRow, x, y);
            return;
        }
        BufferedImage image = images.load(source.tile().imagePath());
        if (image == null) {
            drawMissingData(g, depth, southRow, x, y);
            return;
        }
        drawImage(g, image, x, y, outputPixels, source.x0(), source.y0(), source.x1(), source.y1());
    }

    private void drawMissingTile(Graphics2D g, int depth, int column, int southRow, int x, int y) {
        if (layout.imagePixelsPerTile() > 1) {
            g.setColor(selectionBorderColor(depth, column, southRow));
            g.fillRect(x, y, layout.pixelsPerTile(), layout.pixelsPerTile());
        }
        drawMissingData(g, depth, southRow, x, y);
    }

    private void drawMissingData(Graphics2D g, int depth, int southRow, int x, int y) {
        int outputPixels = layout.imagePixelsPerTile();
        int inset = outputPixels == 1 ? 0 : 1;
        int validY0 = validImageY0(depth, southRow, outputPixels);
        int validY1 = validImageY1(depth, southRow, outputPixels);
        g.setColor(MISSING_DATA);
        g.fillRect(x + inset, y + inset + validY0, outputPixels, validY1 - validY0);
    }

    private void drawTileBorder(Graphics2D g, TileRecord target, int x, int y) {
        if (layout.imagePixelsPerTile() <= 1) {
            return;
        }
        TileAddress address = target.address();
        g.setColor(selectionBorderColor(address.depth(), address.column(), address.southRow()));
        g.fillRect(x, y, layout.pixelsPerTile(), layout.pixelsPerTile());
    }

    private Color selectionBorderColor(int depth, int column, int southRow) {
        if (model.isSecondarySelectedAt(depth, column, southRow)) {
            return SECONDARY_SELECTED_BORDER;
        }
        return model.isSelectedAt(depth, column, southRow) ? SELECTED_BORDER : UNSELECTED_BORDER;
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
        String[] lines = hudLines();
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

    private void drawSelectedTileFileHud(Graphics2D g, Rectangle visible) {
        TileAddress selected = model.selectedAddress();
        if (selected == null) {
            return;
        }
        String line = selectedTileFileName();
        g.setFont(HUD_FONT);
        FontMetrics metrics = g.getFontMetrics();
        int lineHeight = metrics.getHeight();
        int boxWidth = metrics.stringWidth(line) + 20;
        int boxHeight = lineHeight + 16;
        int x = visible.x + 12;
        int y = visible.y + visible.height - boxHeight - 12;
        g.setColor(HUD_BACKGROUND);
        g.fillRect(x, y, boxWidth, boxHeight);
        g.setColor(selectedTileFileExists() ? Color.WHITE : Color.RED);
        g.drawString(line, x + 10, y + 8 + metrics.getAscent());
    }

    String[] hudLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("Quadtree depth [1/2]: " + model.selectedDepth() + " / " + model.catalog().maxDepth());
        lines.add("Matrix: " + layout.matrixSide() + " x " + layout.matrixSide());
        if (layout.focused()) {
            lines.add("Focused tiles: " + layout.visibleTiles().columnCount()
                + " x " + layout.visibleTiles().rowCount());
        }
        lines.add("LOD: " + layout.description());
        TileAddress selected = model.selectedAddress();
        if (selected != null) {
            lines.add(String.format(Locale.US, "lat: %.8f", selected.centerLatitude()));
            lines.add(String.format(Locale.US, "lon: %.8f", selected.centerLongitude()));
        }
        TileAddress secondary = model.secondarySelectedAddress();
        if (secondary != null) {
            lines.add(String.format(Locale.US, "secondary lat: %.8f", secondary.centerLatitude()));
            lines.add(String.format(Locale.US, "secondary lon: %.8f", secondary.centerLongitude()));
        }
        if (selected != null && secondary != null) {
            TileDelta delta = TileDelta.between(selected, secondary);
            lines.add(String.format(Locale.US, "deltaLat (2-1): %+.8f deg", delta.latitudeDegrees()));
            lines.add(String.format(Locale.US, "deltaLon (2-1): %+.8f deg", delta.longitudeDegrees()));
            lines.add(String.format(Locale.US, "distance (1-2): %.3f km", delta.distanceKilometers()));
        }
        lines.add("Fullscreen [F]: " + (isFullScreen() ? "on" : "off"));
        return lines.toArray(String[]::new);
    }

    String selectedTileFileName() {
        TileAddress selected = model.selectedAddress();
        if (selected == null) {
            return "";
        }
        return model.catalog().relativePathFor(selected).toString();
    }

    boolean selectedTileFileExists() {
        TileAddress selected = model.selectedAddress();
        return selected != null
            && Files.isRegularFile(model.catalog().rootFolder().resolve(model.catalog().relativePathFor(selected)));
    }

    private boolean isFullScreen() {
        return javax.swing.SwingUtilities.getWindowAncestor(this) instanceof javax.swing.JFrame frame
            && frame.getGraphicsConfiguration().getDevice().getFullScreenWindow() == frame;
    }

    private Rectangle visibleRectangle() {
        return viewport == null ? new Rectangle(0, 0, getWidth(), getHeight()) : viewport.getViewRect();
    }

    private boolean isInsideGeographicPartOfLowLevelTile(int southRow, int localY) {
        int outputPixels = layout.imagePixelsPerTile();
        int inset = outputPixels == 1 ? 0 : 1;
        int validY0 = validImageY0(model.selectedDepth(), southRow, outputPixels);
        int validY1 = validImageY1(model.selectedDepth(), southRow, outputPixels);
        return localY >= inset + validY0 && localY < inset + validY1;
    }

    private static int validImageY0(int depth, int southRow, int outputPixels) {
        if (depth == 0) {
            return outputPixels / 4;
        }
        if (depth == 1 && southRow == 1) {
            return outputPixels / 2;
        }
        return 0;
    }

    private static int validImageY1(int depth, int southRow, int outputPixels) {
        if (depth == 0) {
            return (3 * outputPixels + 3) / 4;
        }
        if (depth == 1 && southRow == 0) {
            return (outputPixels + 1) / 2;
        }
        return outputPixels;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
