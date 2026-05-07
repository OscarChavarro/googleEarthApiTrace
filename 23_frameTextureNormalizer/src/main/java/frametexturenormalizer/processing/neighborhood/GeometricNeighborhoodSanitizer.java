package frametexturenormalizer.processing.neighborhood;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.model.TileMatrix;
import frametexturenormalizer.model.TileInstance.TriangleStripGeometry;
import frametexturenormalizer.model.TileInstance.TriangleStripVertex;
import frametexturenormalizer.processing.matrix.TileSetToMatrixConverter;

public final class GeometricNeighborhoodSanitizer {
    private static final double UV_EPS = 1.0e-6;
    private static final double POINT_EPS = 1.0e-6;
    private static final double REL_MEAN_DIST_THRESHOLD = 0.02;
    private static final double REL_MAX_DIST_THRESHOLD = 0.05;
    private static final double REL_LENGTH_DIFF_THRESHOLD = 0.02;

    public FrameData sanitizeFrame(FrameData frame) {
        if (frame == null || frame.getTiles() == null || frame.getTiles().isEmpty()) {
            return frame;
        }
        return tightenNeighborhoodsByMatrixLayout(frame);
    }

    private static FrameData tightenNeighborhoodsByMatrixLayout(FrameData frame) {
        TileSetToMatrixConverter converter = new TileSetToMatrixConverter();
        TileMatrix matrix = converter.convert(frame);
        if (matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
            return frame;
        }

        Map<Integer, TileInstance> byId = new HashMap<>();
        for (TileInstance tile : frame.getTiles()) {
            if (tile != null) {
                byId.put(tile.getTileId(), tile);
            }
        }
        if (byId.isEmpty()) {
            return frame;
        }

        Map<Integer, NeighborSet> tightenedByTileId = new HashMap<>();
        Map<String, TileMatrix.TileCoord> byPos = new HashMap<>();
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile != null) {
                byPos.put(key(tile.i(), tile.j()), tile);
            }
        }
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null) {
                continue;
            }
            Integer north = tileIdAt(byPos, tile.i() - 1, tile.j());
            Integer south = tileIdAt(byPos, tile.i() + 1, tile.j());
            Integer east = tileIdAt(byPos, tile.i(), tile.j() + 1);
            Integer west = tileIdAt(byPos, tile.i(), tile.j() - 1);
            tightenedByTileId.put(tile.tileId(), new NeighborSet(south, north, east, west));
        }

        if (tightenedByTileId.isEmpty()) {
            return frame;
        }

        List<TileInstance> tightenedTiles = new ArrayList<>(frame.getTiles().size());
        for (TileInstance tile : frame.getTiles()) {
            if (tile == null) {
                continue;
            }
            NeighborSet tightened = tightenedByTileId.get(tile.getTileId());
            if (tightened == null) {
                tightenedTiles.add(tile);
                continue;
            }
            Integer south = chooseNeighbor(tile.getSouthNeighbor(), tightened.south());
            Integer north = chooseNeighbor(tile.getNorthNeighbor(), tightened.north());
            Integer east = chooseNeighbor(tile.getEastNeighbor(), tightened.east());
            Integer west = tile.isWestCuttingCell() ? null : chooseNeighbor(tile.getWestNeighbor(), tightened.west());
            tightenedTiles.add(copyTileWithNeighbors(tile, south, north, east, west));
        }
        return rebuildFrame(frame, tightenedTiles);
    }

    private static FrameData rebuildFrame(FrameData originalFrame, List<TileInstance> sanitizedTiles) {
        return new FrameData(
            originalFrame.getId(),
            sanitizedTiles,
            originalFrame.getLines(),
            originalFrame.getCameraState(),
            originalFrame.getProjectionMatrix(),
            originalFrame.getModelViewMatrix(),
            originalFrame.isWithMatrixErrors()
        );
    }

    private static TileInstance copyTileWithNeighbors(
        TileInstance tile,
        Integer south,
        Integer north,
        Integer east,
        Integer west
    ) {
        return new TileInstance(
            tile.getTileId(),
            tile.getFrameId(),
            tile.getTextureFile(),
            south,
            north,
            east,
            west,
            tile.getTriangleStrip(),
            tile.getModelViewMatrix(),
            tile.getMatrixI(),
            tile.getMatrixJ(),
            tile.isIncorrectMatrixMapping(),
            tile.isWestCuttingCell(),
            tile.isSelected()
        );
    }

    private static Integer tileIdAt(Map<String, TileMatrix.TileCoord> byPos, int i, int j) {
        TileMatrix.TileCoord tile = byPos.get(key(i, j));
        return tile == null ? null : tile.tileId();
    }

    private static Integer chooseNeighbor(Integer preferred, Integer fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static String key(int i, int j) {
        return i + ":" + j;
    }

    private enum Direction {
        NORTH,
        SOUTH,
        EAST,
        WEST
    }

    private record NeighborSet(Integer south, Integer north, Integer east, Integer west) {
    }

    private record BorderMatchScore(double meanDistance, boolean accepted) {
        static BorderMatchScore compute(BorderProfile a, BorderProfile b, double scaleA, double scaleB) {
            if (a == null || b == null || a.points().isEmpty() || b.points().isEmpty()) {
                return new BorderMatchScore(Double.POSITIVE_INFINITY, false);
            }
            List<Point3> ap = resample(a.points(), 5);
            List<Point3> bp = resample(b.points(), 5);
            Score direct = score(ap, bp);
            List<Point3> reversed = new ArrayList<>(bp);
            java.util.Collections.reverse(reversed);
            Score reverse = score(ap, reversed);
            Score best = direct.meanDistance <= reverse.meanDistance ? direct : reverse;

            double scale = Math.max(POINT_EPS, Math.max(scaleA, scaleB));
            boolean accepted = best.meanDistance <= scale * REL_MEAN_DIST_THRESHOLD
                && best.maxDistance <= scale * REL_MAX_DIST_THRESHOLD
                && Math.abs(a.pathLength() - b.pathLength()) <= scale * REL_LENGTH_DIFF_THRESHOLD;
            return new BorderMatchScore(best.meanDistance, accepted);
        }

        private static Score score(List<Point3> a, List<Point3> b) {
            int n = Math.min(a.size(), b.size());
            if (n <= 0) {
                return new Score(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
            }
            double sum = 0.0;
            double max = 0.0;
            for (int i = 0; i < n; i++) {
                double dist = a.get(i).distance(b.get(i));
                sum += dist;
                max = Math.max(max, dist);
            }
            return new Score(sum / n, max);
        }

        private static List<Point3> resample(List<Point3> input, int targetCount) {
            if (input.size() <= 1 || targetCount <= 1) {
                return input;
            }
            double total = 0.0;
            double[] cumulative = new double[input.size()];
            for (int i = 1; i < input.size(); i++) {
                total += input.get(i - 1).distance(input.get(i));
                cumulative[i] = total;
            }
            if (total <= POINT_EPS) {
                return input;
            }
            List<Point3> out = new ArrayList<>(targetCount);
            out.add(input.get(0));
            for (int sample = 1; sample < targetCount - 1; sample++) {
                double target = (total * sample) / (targetCount - 1);
                int idx = 1;
                while (idx < cumulative.length && cumulative[idx] < target) {
                    idx++;
                }
                if (idx >= input.size()) {
                    out.add(input.get(input.size() - 1));
                    continue;
                }
                Point3 a = input.get(idx - 1);
                Point3 b = input.get(idx);
                double span = cumulative[idx] - cumulative[idx - 1];
                double t = span <= POINT_EPS ? 0.0 : (target - cumulative[idx - 1]) / span;
                out.add(a.interpolate(b, t));
            }
            out.add(input.get(input.size() - 1));
            return out;
        }

        private record Score(double meanDistance, double maxDistance) {
        }
    }

    private record BorderProfile(List<Point3> points, Point3 center, double pathLength) {
    }

    private record TileProfile(TileInstance tile, Map<Direction, BorderProfile> borders, double scale) {
        static TileProfile build(TileInstance tile, double[] defaultModelViewMatrix) {
            TriangleStripGeometry strip = tile == null ? null : tile.getTriangleStrip();
            if (tile == null || strip == null || strip.vertices() == null || strip.vertices().isEmpty()) {
                return new TileProfile(tile, Map.of(), 0.0);
            }

            List<Sample> samples = collectSamples(strip.vertices(), tile.getModelViewMatrix(), defaultModelViewMatrix);
            if (samples.isEmpty()) {
                return new TileProfile(tile, Map.of(), 0.0);
            }

            double minU = Double.POSITIVE_INFINITY;
            double maxU = Double.NEGATIVE_INFINITY;
            double minV = Double.POSITIVE_INFINITY;
            double maxV = Double.NEGATIVE_INFINITY;
            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY;
            double maxZ = Double.NEGATIVE_INFINITY;

            for (Sample sample : samples) {
                minU = Math.min(minU, sample.u());
                maxU = Math.max(maxU, sample.u());
                minV = Math.min(minV, sample.v());
                maxV = Math.max(maxV, sample.v());
                minX = Math.min(minX, sample.point().x());
                maxX = Math.max(maxX, sample.point().x());
                minY = Math.min(minY, sample.point().y());
                maxY = Math.max(maxY, sample.point().y());
                minZ = Math.min(minZ, sample.point().z());
                maxZ = Math.max(maxZ, sample.point().z());
            }

            BorderProfile uMin = border(samples, minU, true);
            BorderProfile uMax = border(samples, maxU, true);
            BorderProfile vMin = border(samples, minV, false);
            BorderProfile vMax = border(samples, maxV, false);

            Map<Direction, BorderProfile> out = new EnumMap<>(Direction.class);
            assignHorizontal(out, uMin, uMax);
            assignVertical(out, vMin, vMax);

            double dx = maxX - minX;
            double dy = maxY - minY;
            double dz = maxZ - minZ;
            double scale = Math.max(POINT_EPS, Math.sqrt(dx * dx + dy * dy + dz * dz));
            return new TileProfile(tile, out, scale);
        }

        boolean isUsable() {
            return borders != null && borders.size() == 4;
        }

        BorderProfile border(Direction direction) {
            return borders == null ? null : borders.get(direction);
        }

        private static void assignHorizontal(Map<Direction, BorderProfile> out, BorderProfile a, BorderProfile b) {
            if (a == null || b == null) {
                return;
            }
            if (a.center().x() <= b.center().x()) {
                out.put(Direction.WEST, a);
                out.put(Direction.EAST, b);
            }
            else {
                out.put(Direction.WEST, b);
                out.put(Direction.EAST, a);
            }
        }

        private static void assignVertical(Map<Direction, BorderProfile> out, BorderProfile a, BorderProfile b) {
            if (a == null || b == null) {
                return;
            }
            if (a.center().y() <= b.center().y()) {
                out.put(Direction.SOUTH, a);
                out.put(Direction.NORTH, b);
            }
            else {
                out.put(Direction.SOUTH, b);
                out.put(Direction.NORTH, a);
            }
        }

        private static BorderProfile border(List<Sample> samples, double target, boolean byU) {
            Map<String, Point3> unique = new LinkedHashMap<>();
            for (Sample sample : samples) {
                double value = byU ? sample.u() : sample.v();
                if (Math.abs(value - target) > UV_EPS) {
                    continue;
                }
                String key = quantizedKey(sample.point());
                unique.putIfAbsent(key, sample.point());
            }
            if (unique.isEmpty()) {
                return null;
            }
            List<Point3> points = new ArrayList<>(unique.values());
            points.sort((a, b) -> {
                int cmp = byU ? Double.compare(a.y(), b.y()) : Double.compare(a.x(), b.x());
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Double.compare(a.z(), b.z());
                if (cmp != 0) {
                    return cmp;
                }
                return byU ? Double.compare(a.x(), b.x()) : Double.compare(a.y(), b.y());
            });
            double sx = 0.0;
            double sy = 0.0;
            double sz = 0.0;
            double length = 0.0;
            for (int i = 0; i < points.size(); i++) {
                Point3 p = points.get(i);
                sx += p.x();
                sy += p.y();
                sz += p.z();
                if (i > 0) {
                    length += points.get(i - 1).distance(p);
                }
            }
            Point3 center = new Point3(sx / points.size(), sy / points.size(), sz / points.size());
            return new BorderProfile(points, center, length);
        }

        private static List<Sample> collectSamples(
            List<TriangleStripVertex> vertices,
            double[] tileModelViewMatrix,
            double[] defaultModelViewMatrix
        ) {
            double[] modelView = tileModelViewMatrix != null && tileModelViewMatrix.length == 16
                ? tileModelViewMatrix
                : defaultModelViewMatrix;
            List<Sample> out = new ArrayList<>(vertices.size());
            for (TriangleStripVertex vertex : vertices) {
                if (vertex == null) {
                    continue;
                }
                Point3 transformed = transform(vertex.x(), vertex.y(), vertex.z(), modelView);
                out.add(new Sample(vertex.u(), vertex.v(), transformed));
            }
            return out;
        }

        private static Point3 transform(double x, double y, double z, double[] modelView) {
            if (modelView == null || modelView.length != 16) {
                return new Point3(x, y, z);
            }
            double tx = modelView[0] * x + modelView[4] * y + modelView[8] * z + modelView[12];
            double ty = modelView[1] * x + modelView[5] * y + modelView[9] * z + modelView[13];
            double tz = modelView[2] * x + modelView[6] * y + modelView[10] * z + modelView[14];
            double tw = modelView[3] * x + modelView[7] * y + modelView[11] * z + modelView[15];
            if (Math.abs(tw) > POINT_EPS && Math.abs(tw - 1.0) > POINT_EPS) {
                tx /= tw;
                ty /= tw;
                tz /= tw;
            }
            return new Point3(tx, ty, tz);
        }

        private static String quantizedKey(Point3 point) {
            return q(point.x()) + ":" + q(point.y()) + ":" + q(point.z());
        }

        private static long q(double value) {
            return Math.round(value * 1_000_000.0);
        }

        private record Sample(double u, double v, Point3 point) {
        }
    }

    private record Point3(double x, double y, double z) {
        double distance(Point3 other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        Point3 interpolate(Point3 other, double t) {
            double clamped = Math.max(0.0, Math.min(1.0, t));
            return new Point3(
                x + (other.x - x) * clamped,
                y + (other.y - y) * clamped,
                z + (other.z - z) * clamped
            );
        }
    }
}
