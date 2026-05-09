package dumpanalyzer.processing;

import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public final class VisualTilePositioner {
    private static final double CLUSTERING_FACTOR = 0.10;

    private VisualTilePositioner() {
    }

    public static Frame reorderFrame(Frame frame, int viewportWidth, int viewportHeight) {
        if (frame == null || frame.getTiles().size() < 2) {
            return frame;
        }

        Matrix4x4 projection = matrixFromColumnMajor(frame.getProjectionMatrix());
        if (projection == null) {
            projection = Matrix4x4.identityMatrix();
        }

        List<ProjectedTile> projectedTiles = new ArrayList<>(frame.getTiles().size());
        for (int i = 0; i < frame.getTiles().size(); i++) {
            TileInstance tile = frame.getTiles().get(i);
            projectedTiles.add(projectTile(i, tile, frame, projection, viewportWidth, viewportHeight));
        }

        List<ProjectedTile> projectable = projectedTiles.stream()
            .filter(ProjectedTile::isProjectable)
            .toList();

        if (projectable.isEmpty()) {
            return frame;
        }

        List<ProjectedTile> ordered = new ArrayList<>(projectable.size());
        List<List<ProjectedTile>> columns = clusterByAxis(
            projectable,
            Comparator
                .comparingDouble(ProjectedTile::projectedX)
                .thenComparingDouble(ProjectedTile::projectedY).reversed()
                .thenComparingInt(ProjectedTile::originalIndex),
            ProjectedTile::projectedX
        );
        columns.sort(Comparator
            .comparingDouble(VisualTilePositioner::averageProjectedX)
            .thenComparingDouble(VisualTilePositioner::averageProjectedY).reversed());

        for (List<ProjectedTile> column : columns) {
            List<List<ProjectedTile>> rows = clusterByAxis(
                column,
                Comparator
                    .comparingDouble(ProjectedTile::projectedY).reversed()
                    .thenComparingDouble(ProjectedTile::projectedX)
                    .thenComparingInt(ProjectedTile::originalIndex),
                ProjectedTile::projectedY
            );
            rows.sort(Comparator
                .comparingDouble(VisualTilePositioner::averageProjectedY).reversed()
                .thenComparingDouble(VisualTilePositioner::averageProjectedX));
            for (List<ProjectedTile> row : rows) {
                row.sort(Comparator
                    .comparingDouble(ProjectedTile::projectedY).reversed()
                    .thenComparingDouble(ProjectedTile::projectedX)
                    .thenComparingInt(ProjectedTile::originalIndex));
                ordered.addAll(row);
            }
        }

        for (ProjectedTile tile : projectedTiles) {
            if (!tile.isProjectable()) {
                ordered.add(tile);
            }
        }

        boolean changed = false;
        List<TileInstance> reorderedTiles = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            ProjectedTile projectedTile = ordered.get(i);
            reorderedTiles.add(projectedTile.tile());
            if (projectedTile.originalIndex() != i) {
                changed = true;
            }
        }
        if (!changed) {
            return frame;
        }

        return new Frame(
            frame.getId(),
            reorderedTiles,
            frame.getLines(),
            frame.getProjectionMatrix(),
            frame.getModelViewMatrix(),
            frame.getGoogleCamera()
        );
    }

    private static ProjectedTile projectTile(
        int originalIndex,
        TileInstance tile,
        Frame frame,
        Matrix4x4 projection,
        int viewportWidth,
        int viewportHeight
    ) {
        Vector3D center = centerOfTile(tile);
        double[] modelView = modelViewForTile(tile, frame);
        double[] pixel = projectToViewport(center, modelView, projection, viewportWidth, viewportHeight);
        if (pixel == null) {
            return new ProjectedTile(originalIndex, tile, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, false);
        }
        return new ProjectedTile(originalIndex, tile, pixel[0], pixel[1], true);
    }

    private static Vector3D centerOfTile(TileInstance tile) {
        if (tile == null) {
            return null;
        }
        TileInstance.TriangleStripGeometry geometry = tile.getTriangleStrip();
        if (geometry != null && geometry.vertices() != null && !geometry.vertices().isEmpty()) {
            double sx = 0.0;
            double sy = 0.0;
            double sz = 0.0;
            for (TileInstance.TriangleStripVertex v : geometry.vertices()) {
                sx += v.x();
                sy += v.y();
                sz += v.z();
            }
            double inv = 1.0 / geometry.vertices().size();
            return new Vector3D(sx * inv, sy * inv, sz * inv);
        }
        if (tile.getMin() == null || tile.getMax() == null) {
            return null;
        }
        return new Vector3D(
            (tile.getMin().x() + tile.getMax().x()) * 0.5,
            (tile.getMin().y() + tile.getMax().y()) * 0.5,
            (tile.getMin().z() + tile.getMax().z()) * 0.5
        );
    }

    private static double[] modelViewForTile(TileInstance tile, Frame frame) {
        double[] tileModelView = tile == null ? null : tile.getModelViewMatrix();
        if (tileModelView != null && tileModelView.length == 16) {
            return tileModelView;
        }
        double[] frameModelView = frame == null ? null : frame.getModelViewMatrix();
        if (frameModelView != null && frameModelView.length == 16) {
            return frameModelView;
        }
        return null;
    }

    private static double[] projectToViewport(
        Vector3D point,
        double[] modelView,
        Matrix4x4 projection,
        int viewportWidth,
        int viewportHeight
    ) {
        if (point == null
            || modelView == null
            || modelView.length != 16
            || projection == null
            || viewportWidth <= 0
            || viewportHeight <= 0) {
            return null;
        }

        double x = point.x();
        double y = point.y();
        double z = point.z();
        double vx = modelView[0] * x + modelView[4] * y + modelView[8] * z + modelView[12];
        double vy = modelView[1] * x + modelView[5] * y + modelView[9] * z + modelView[13];
        double vz = modelView[2] * x + modelView[6] * y + modelView[10] * z + modelView[14];
        double vw = modelView[3] * x + modelView[7] * y + modelView[11] * z + modelView[15];
        double[] proj = projection.exportToDoubleArrayColumnOrder();
        if (proj == null || proj.length != 16) {
            return null;
        }
        double cx = proj[0] * vx + proj[4] * vy + proj[8] * vz + proj[12] * vw;
        double cy = proj[1] * vx + proj[5] * vy + proj[9] * vz + proj[13] * vw;
        double cw = proj[3] * vx + proj[7] * vy + proj[11] * vz + proj[15] * vw;
        if (Math.abs(cw) < 1e-12) {
            return null;
        }
        double ndcX = cx / cw;
        double ndcY = cy / cw;
        if (!Double.isFinite(ndcX) || !Double.isFinite(ndcY)) {
            return null;
        }
        double px = (ndcX * 0.5 + 0.5) * (viewportWidth - 1);
        double py = (ndcY * 0.5 + 0.5) * (viewportHeight - 1);
        if (!Double.isFinite(px) || !Double.isFinite(py)) {
            return null;
        }
        return new double[] { px, py };
    }

    private static Matrix4x4 matrixFromColumnMajor(double[] m) {
        if (m == null || m.length != 16) {
            return null;
        }
        Matrix4x4 out = new Matrix4x4();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                out = out.withVal(row, col, m[col * 4 + row]);
            }
        }
        return out;
    }

    private static List<List<ProjectedTile>> clusterByAxis(
        List<ProjectedTile> tiles,
        Comparator<ProjectedTile> ordering,
        AxisExtractor axisExtractor
    ) {
        if (tiles == null || tiles.isEmpty()) {
            return List.of();
        }

        List<ProjectedTile> sorted = new ArrayList<>(tiles);
        sorted.sort(ordering);

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (ProjectedTile tile : sorted) {
            double value = axisExtractor.valueOf(tile);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        double tolerance = Math.max(1.0, (max - min) * CLUSTERING_FACTOR);

        List<List<ProjectedTile>> clusters = new ArrayList<>();
        List<ProjectedTile> current = new ArrayList<>();
        double currentRepresentative = axisExtractor.valueOf(sorted.get(0));
        for (ProjectedTile tile : sorted) {
            double value = axisExtractor.valueOf(tile);
            if (!current.isEmpty() && Math.abs(value - currentRepresentative) > tolerance) {
                clusters.add(current);
                current = new ArrayList<>();
                currentRepresentative = value;
            }
            current.add(tile);
            currentRepresentative = averageAxis(current, axisExtractor);
        }
        if (!current.isEmpty()) {
            clusters.add(current);
        }
        return clusters;
    }

    private static double averageProjectedX(List<ProjectedTile> tiles) {
        return averageAxis(tiles, ProjectedTile::projectedX);
    }

    private static double averageProjectedY(List<ProjectedTile> tiles) {
        return averageAxis(tiles, ProjectedTile::projectedY);
    }

    private static double averageAxis(List<ProjectedTile> tiles, AxisExtractor axisExtractor) {
        if (tiles == null || tiles.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (ProjectedTile tile : tiles) {
            sum += axisExtractor.valueOf(tile);
        }
        return sum / tiles.size();
    }

    @FunctionalInterface
    private interface AxisExtractor {
        double valueOf(ProjectedTile tile);
    }

    private record ProjectedTile(
        int originalIndex,
        TileInstance tile,
        double projectedX,
        double projectedY,
        boolean isProjectable
    ) {}
}
