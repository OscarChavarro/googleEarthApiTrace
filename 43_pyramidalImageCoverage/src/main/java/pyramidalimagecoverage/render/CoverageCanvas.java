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
import java.util.Locale;
import javax.swing.JViewport;
import pyramidalimagecoverage.io.TileImageRepository;
import pyramidalimagecoverage.model.PyramidCatalog;
import pyramidalimagecoverage.model.GridModes;
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
    private static final Color GRID_COLOR = new Color(255, 230, 0, 220);
    private static final int WORLD10_GROUP_DEGREES = 8;
    private static final Color HUD_BACKGROUND = new Color(0, 0, 0, 190);
    private static final Color PREVIEW_BORDER = Color.WHITE;
    private static final Font HUD_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 28);
    private static final int HUD_MARGIN = 12;
    private static final int PREVIEW_FRAME = 2;

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
            drawWorld10Grid(g, visible);
            drawHud(g, visible);
            drawSelectedTileFileHud(g, visible);
            drawLastSelectedTilePreview(g, visible);
        }
        finally {
            g.dispose();
        }
    }

    private void drawWorld10Grid(Graphics2D g, Rectangle visible) {
        if (model.selectedDepth() != ViewerModel.GRID_DEPTH || model.gridMode() == GridModes.SHOW_NOTHING) {
            return;
        }
        g.setColor(GRID_COLOR);
        g.setStroke(new java.awt.BasicStroke(model.gridMode() == GridModes.SHOW_GROUPS_BEING_DOWNLOADED ? 2f : 1f));
        switch (model.gridMode()) {
            case SHOW_ALL_GROUPS -> forEachWorld10Group(group -> drawGroup(g, group, visible));
            case SHOW_MOSTLY_EMPTY_GROUPS -> forEachWorld10Group(group -> {
                if (atLeastNinetyFivePercentOfGroupTilesAreEmpty(group)) {
                    drawGroup(g, group, visible);
                }
            });
            case SHOW_GROUPS_BEING_DOWNLOADED -> ActiveWorld10Groups.read().forEach(group ->
                drawGroup(g, new World10Group(group.latitude(), group.longitude(), group.latitudeSpan(), group.longitudeSpan()), visible)
            );
            case SHOW_NOTHING -> { }
        }
    }

    private void forEachWorld10Group(java.util.function.Consumer<World10Group> consumer) {
        // Baseline lattice plus a northern band overlapping the 60 N boundary.
        for (int latitude = -60; latitude < 60; latitude += WORLD10_GROUP_DEGREES) {
            for (int longitude = -180; longitude < 180; longitude += WORLD10_GROUP_DEGREES) {
                consumer.accept(new World10Group(latitude, longitude, WORLD10_GROUP_DEGREES, WORLD10_GROUP_DEGREES));
            }
        }
        for (int longitude = -180; longitude < 180; longitude += WORLD10_GROUP_DEGREES) {
            consumer.accept(new World10Group(59, longitude, 9, WORLD10_GROUP_DEGREES));
        }
    }

    private boolean atLeastNinetyFivePercentOfGroupTilesAreEmpty(World10Group group) {
        // Inspect every level-10 cell whose centre lies in the group. Missing
        // PNGs are painted red by drawTiles even when an ancestor is available;
        // ancestor images supply LOD colors only for cells present at this level.
        double minLongitude = group.longitude();
        double maxLongitude = group.longitude() + group.longitudeSpan();
        double minLatitude = group.latitude();
        double maxLatitude = group.latitude() + group.latitudeSpan();
        double tileSpan = 360.0 / (1 << ViewerModel.GRID_DEPTH);
        int firstColumn = (int) Math.ceil((minLongitude + 180.0) / tileSpan - 0.5);
        int lastColumn = (int) Math.floor((maxLongitude + 180.0) / tileSpan - 0.5);
        int firstSouthRow = (int) Math.ceil((minLatitude + 180.0) / tileSpan - 0.5);
        int lastSouthRow = (int) Math.floor((maxLatitude + 180.0) / tileSpan - 0.5);
        int checkedTiles = 0;
        int emptyTiles = 0;
        for (int southRow = firstSouthRow; southRow <= lastSouthRow; southRow++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                checkedTiles++;
                if (model.catalog().tileAt(ViewerModel.GRID_DEPTH, column, southRow) == null) {
                    emptyTiles++;
                }
            }
        }
        return checkedTiles > 0 && emptyTiles * 100 >= checkedTiles * 95;
    }

    private void drawGroup(Graphics2D g, World10Group group, Rectangle visible) {
        int x0 = worldLongitudeToCanvasX(group.longitude());
        int x1 = worldLongitudeToCanvasX(group.longitude() + group.longitudeSpan());
        int y0 = worldLatitudeToCanvasY(group.latitude() + group.latitudeSpan());
        int y1 = worldLatitudeToCanvasY(group.latitude());
        Rectangle rectangle = new Rectangle(Math.min(x0, x1), Math.min(y0, y1), Math.abs(x1 - x0), Math.abs(y1 - y0));
        if (rectangle.intersects(visible)) {
            g.drawRect(rectangle.x, rectangle.y, Math.max(1, rectangle.width), Math.max(1, rectangle.height));
        }
    }

    private int worldLongitudeToCanvasX(double longitude) {
        return originX() + (int) Math.round(((longitude + 180.0) / 360.0 * layout.matrixSide()
            - layout.visibleTiles().minimumColumn()) * layout.pixelsPerTile());
    }

    private int worldLatitudeToCanvasY(double latitude) {
        return originY() + (int) Math.round((layout.visibleTiles().maximumSouthRow() + 1
            - (latitude + 180.0) / 360.0 * layout.matrixSide()) * layout.pixelsPerTile());
    }

    private int originX() {
        return Math.max(0, (getWidth() - layout.contentWidth()) / 2);
    }

    private int originY() {
        return Math.max(0, (getHeight() - layout.contentHeight()) / 2);
    }

    private record World10Group(double latitude, double longitude, double latitudeSpan, double longitudeSpan) {}

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
            BufferedImage image = images.getOrRequest(target.imagePath(), this::repaint);
            if (image != null) {
                drawImage(g, image, x, y, 256, 0, 0, image.getWidth(), image.getHeight());
            }
            else if (images.failed(target.imagePath())) {
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
        BufferedImage image = images.getOrRequest(source.tile().imagePath(), this::repaint);
        if (image == null) {
            if (images.failed(source.tile().imagePath())) {
                drawMissingData(g, depth, southRow, x, y);
            }
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
        int x = visible.x + visible.width - boxWidth - HUD_MARGIN;
        int y = visible.y + HUD_MARGIN;
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
        int x = visible.x + HUD_MARGIN;
        int y = visible.y + visible.height - boxHeight - HUD_MARGIN;
        g.setColor(HUD_BACKGROUND);
        g.fillRect(x, y, boxWidth, boxHeight);
        g.setColor(selectedTileFileExists() ? Color.WHITE : Color.RED);
        g.drawString(line, x + 10, y + 8 + metrics.getAscent());
    }

    private void drawLastSelectedTilePreview(Graphics2D g, Rectangle visible) {
        TileAddress previewAddress = model.lastSelectedAddress();
        if (previewAddress == null) {
            return;
        }
        TileRecord tile = model.catalog().tileAt(
            previewAddress.depth(), previewAddress.column(), previewAddress.southRow()
        );
        if (tile == null) {
            return;
        }
        BufferedImage image = images.getOrRequest(tile.imagePath(), this::repaint);
        if (image == null) {
            return;
        }
        int frameWidth = image.getWidth() + PREVIEW_FRAME * 2;
        int frameHeight = image.getHeight() + PREVIEW_FRAME * 2;
        if (frameWidth + HUD_MARGIN * 2 > visible.width || frameHeight + HUD_MARGIN * 2 > visible.height) {
            return;
        }
        int x = visible.x + visible.width - frameWidth - HUD_MARGIN;
        int y = visible.y + visible.height - frameHeight - HUD_MARGIN;
        g.setColor(HUD_BACKGROUND);
        g.fillRect(x, y, frameWidth, frameHeight);
        g.drawImage(image, x + PREVIEW_FRAME, y + PREVIEW_FRAME, null);
        g.setColor(PREVIEW_BORDER);
        g.drawRect(x, y, frameWidth - 1, frameHeight - 1);
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
        if (model.selectedDepth() == ViewerModel.GRID_DEPTH) {
            lines.add("Grid [G]: " + model.gridMode());
        }
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
        lines.add("Refresh coverage [R]");
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
            && model.catalog().tileAt(selected.depth(), selected.column(), selected.southRow()) != null;
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
