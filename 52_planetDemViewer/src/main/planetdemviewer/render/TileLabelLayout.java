package planetdemviewer.render;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;
import planetdemviewer.processing.DrawCommand;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.common.linealAlgebra.Vector4Dd;
import vsdk.toolkit.environment.camera.Camera;

/** Places each drawn tile's name and geographic center on the part of the tile that is on screen. */
public final class TileLabelLayout {
    private TileLabelLayout() {
    }

    /** The viewport is {x, y, width, height} in window pixels. */
    public static List<TextLabel> labels(List<DrawCommand> commands, Camera camera, int[] viewport) {
        Matrix4x4d projection = camera.calculateProjectionMatrix();
        List<TextLabel> labels = new ArrayList<>();
        for (DrawCommand command : commands) {
            NdcPoint center = visibleViewportCenter(command.corners(), projection);
            if (center == null) {
                continue;
            }
            int x = viewport[0] + (int) Math.round((center.x + 1.0) * viewport[2] * 0.5);
            int y = viewport[1] + (int) Math.round((center.y + 1.0) * viewport[3] * 0.5);
            labels.add(TextLabel.centered(tileName(command), TextStyle.TILE_LABEL, x, y + 6, TextLabel.YELLOW));
            labels.add(TextLabel.centered(geographicCenter(command), TextStyle.TILE_LABEL, x, y - 9, TextLabel.YELLOW));
        }
        return labels;
    }

    /** Center of the tile portion clipped to the current viewport in NDC, or null if none of it is visible. */
    static NdcPoint visibleViewportCenter(Vector3Dd[] corners, Matrix4x4d projection) {
        List<NdcPoint> polygon = new ArrayList<>(corners.length);
        for (Vector3Dd corner : corners) {
            Vector4Dd clip = projection.multiply(new Vector4Dd(corner));
            if (clip.w() <= 1e-7) {
                return null;
            }
            polygon.add(new NdcPoint(clip.x() / clip.w(), clip.y() / clip.w()));
        }
        polygon = clipToViewport(polygon, point -> point.x + 1.0);
        polygon = clipToViewport(polygon, point -> 1.0 - point.x);
        polygon = clipToViewport(polygon, point -> point.y + 1.0);
        polygon = clipToViewport(polygon, point -> 1.0 - point.y);
        if (polygon.isEmpty()) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (NdcPoint point : polygon) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }
        return new NdcPoint((minX + maxX) * 0.5, (minY + maxY) * 0.5);
    }

    private static List<NdcPoint> clipToViewport(List<NdcPoint> polygon, ToDoubleFunction<NdcPoint> distance) {
        List<NdcPoint> result = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            NdcPoint from = polygon.get(i);
            NdcPoint to = polygon.get((i + 1) % polygon.size());
            double fromDistance = distance.applyAsDouble(from);
            double toDistance = distance.applyAsDouble(to);
            boolean fromInside = fromDistance >= 0.0;
            boolean toInside = toDistance >= 0.0;
            if (fromInside) {
                result.add(from);
            }
            if (fromInside != toInside) {
                double fraction = fromDistance / (fromDistance - toDistance);
                result.add(new NdcPoint(from.x + (to.x - from.x) * fraction,
                    from.y + (to.y - from.y) * fraction));
            }
        }
        return result;
    }

    private static String tileName(DrawCommand command) {
        File file = command.node().getTileFile();
        String name = file == null ? command.node().getId() : file.getName();
        int extension = name.lastIndexOf('.');
        return extension > 0 ? name.substring(0, extension) : name;
    }

    private static String geographicCenter(DrawCommand command) {
        double longitude = (command.node().getX0() + command.node().getX1()) * 180.0 - 180.0;
        double latitude = (command.node().getY0() + command.node().getY1()) * 90.0 - 90.0;
        return String.format(Locale.ROOT, "%.4f°, %.4f°", longitude, latitude);
    }

    record NdcPoint(double x, double y) {
    }
}
