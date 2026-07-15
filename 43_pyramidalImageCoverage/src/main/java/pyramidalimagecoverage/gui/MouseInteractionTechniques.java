package pyramidalimagecoverage.gui;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
