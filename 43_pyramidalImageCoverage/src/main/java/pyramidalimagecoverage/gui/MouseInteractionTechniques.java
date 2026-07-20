package pyramidalimagecoverage.gui;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Locale;
import pyramidalimagecoverage.model.TileAddress;
import pyramidalimagecoverage.model.TileRecord;
import pyramidalimagecoverage.model.ViewerModel;
import pyramidalimagecoverage.render.CoverageCanvas;
import vsdk.toolkit.gui.AwtSystem;

public final class MouseInteractionTechniques implements MouseListener {
    private final ViewerModel model;
    private final CoverageCanvas canvas;

    public MouseInteractionTechniques(ViewerModel model, CoverageCanvas canvas) {
        this.model = model;
        this.canvas = canvas;
    }

    public void install() {
        canvas.addMouseListener(this);
    }

    @Override
    public void mouseClicked(MouseEvent event) {
        if (event.getButton() != MouseEvent.BUTTON1) {
            return;
        }
        vsdk.toolkit.gui.MouseEvent vitralEvent = AwtSystem.awt2vsdkEvent(event);
        TileRecord tile = canvas.tileAtCanvasPosition(xOf(vitralEvent), yOf(vitralEvent));
        if (tile == null) {
            model.clearSelection();
            return;
        }
        printTileInfo(tile);
        model.toggleSelection(tile);
    }

    @Override
    public void mousePressed(MouseEvent event) {
    }

    @Override
    public void mouseReleased(MouseEvent event) {
    }

    @Override
    public void mouseEntered(MouseEvent event) {
    }

    @Override
    public void mouseExited(MouseEvent event) {
    }

    private static int xOf(vsdk.toolkit.gui.MouseEvent event) {
        return coordinate(event, "x", "getX");
    }

    private static int yOf(vsdk.toolkit.gui.MouseEvent event) {
        return coordinate(event, "y", "getY");
    }

    private void printTileInfo(TileRecord tile) {
        TileAddress address = tile.address();
        Path imagePath = model.catalog().rootFolder().relativize(tile.imagePath());
        System.out.printf(
            Locale.US,
            "Clicked tile: image=%s, quadkey=%s, depth=%d, column=%d, southRow=%d, lowerLeftLat=%.8f, lowerLeftLon=%.8f%n",
            imagePath,
            address.quadKey(),
            address.depth(),
            address.column(),
            address.southRow(),
            address.lowerLeftLatitude(),
            address.lowerLeftLongitude()
        );
    }

    private static int coordinate(vsdk.toolkit.gui.MouseEvent event, String fieldName, String getterName) {
        try {
            Field field = event.getClass().getField(fieldName);
            return field.getInt(event);
        }
        catch (ReflectiveOperationException ignored) {
            try {
                Method method = event.getClass().getMethod(getterName);
                return ((Number) method.invoke(event)).intValue();
            }
            catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Vitral mouse event does not expose " + fieldName, exception);
            }
        }
    }
}
