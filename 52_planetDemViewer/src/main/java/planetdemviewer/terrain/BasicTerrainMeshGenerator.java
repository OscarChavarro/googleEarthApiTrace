package planetdemviewer.terrain;

import java.util.EnumSet;
import java.util.List;
import planetdemviewer.config.Configuration;
import planetdemviewer.model.DemTile;
import planetdemviewer.model.QuadtreeNode;
import vsdk.toolkit.environment.geometry.surface.TriangleMesh;

/**
 * Builds the regular two-triangles-per-cell DEM mesh.  The core owns the
 * 256x256 rendered vertices.  A published 258x258 tile has a one-sample
 * halo: its west/north halo is the last core sample of the adjacent tile.
 * Consequently the first rendered row/column comes from that halo, whereas
 * the remaining vertices come from the tile core.  This makes both sides of
 * a same-resolution boundary use the exact same elevation sample.
 */
public final class BasicTerrainMeshGenerator implements TerrainMeshGenerator<TriangleMesh> {
    private static final int CORE = DemTile.CORE_SIZE;
    /**
     * A fine edge is extended by one of its own grid steps into a coarser
     * neighbour.  The edge height is already snapped to that neighbour, so
     * this is a coverage apron rather than a vertical skirt.  It prevents a
     * rasterization crack at a T-junction, where mathematically coincident
     * edges otherwise arrive at GL as independently rounded vertices.
     */
    private static final double STITCHED_EDGE_OVERLAP_STEPS = 1.0;

    @Override
    public TriangleMesh generate(QuadtreeNode node, DemTile elevationsWithHalo) {
        return generate(node, elevationsWithHalo, List.of());
    }

    public TriangleMesh generate(QuadtreeNode node, DemTile elevationsWithHalo,
                                 List<TerrainSeamConstraint> seamConstraints) {
        if (node == null || elevationsWithHalo == null) {
            throw new IllegalArgumentException("A quadtree node and DEM tile are required");
        }
        HeightField heightField = new HeightField(node, elevationsWithHalo, seamConstraints);
        EnumSet<TerrainEdge> stitchedEdges = stitchedEdges(seamConstraints);

        TriangleMesh mesh = new TriangleMesh();
        mesh.setName("DEM " + node.getId());
        mesh.initVertexPositionsArray(CORE * CORE);
        mesh.initVertexNormalsArray();

        double[] positions = mesh.getVertexPositions();
        double[] normals = mesh.getVertexNormals();
        double stepX = (node.getX1() - node.getX0()) / (CORE - 1.0);
        double stepY = (node.getY1() - node.getY0()) / (CORE - 1.0);

        for (int row = 0; row < CORE; row++) {
            for (int column = 0; column < CORE; column++) {
                int vertex = vertexIndex(row, column);
                int p = vertex * 3;
                positions[p] = node.getX0() - 0.5 + column * stepX;
                positions[p + 1] = node.getY1() - 0.5 - row * stepY;
                extendStitchedBoundary(positions, p, row, column, stepX, stepY, stitchedEdges);
                int storedRow = renderedStoredCoordinate(row);
                int storedColumn = renderedStoredCoordinate(column);
                positions[p + 2] = heightField.elevation(storedRow, storedColumn);

                double[] normal = heightField.stitchedNormal(storedRow, storedColumn);
                if (normal == null) {
                    normal = angleWeightedNormal(heightField, storedRow, storedColumn, stepX, stepY);
                }
                normals[p] = normal[0];
                normals[p + 1] = normal[1];
                normals[p + 2] = normal[2];
            }
        }

        int triangleCount = countValidTriangles(heightField);
        mesh.initTriangleArrays(triangleCount);
        int[] indices = mesh.getTriangleIndexes();
        int out = 0;
        for (int row = 0; row < CORE - 1; row++) {
            for (int column = 0; column < CORE - 1; column++) {
                int a = vertexIndex(row, column);
                int b = vertexIndex(row + 1, column);
                int c = vertexIndex(row, column + 1);
                int d = vertexIndex(row + 1, column + 1);
                if (validRendered(heightField, row, column)
                    && validRendered(heightField, row + 1, column)
                    && validRendered(heightField, row, column + 1)) {
                    indices[out++] = a;
                    indices[out++] = b;
                    indices[out++] = c;
                }
                if (validRendered(heightField, row, column + 1)
                    && validRendered(heightField, row + 1, column)
                    && validRendered(heightField, row + 1, column + 1)) {
                    indices[out++] = c;
                    indices[out++] = b;
                    indices[out++] = d;
                }
            }
        }
        return mesh;
    }

    private static EnumSet<TerrainEdge> stitchedEdges(List<TerrainSeamConstraint> constraints) {
        EnumSet<TerrainEdge> result = EnumSet.noneOf(TerrainEdge.class);
        if (constraints != null) {
            for (TerrainSeamConstraint constraint : constraints) {
                if (constraint != null) {
                    result.add(constraint.fineEdge());
                }
            }
        }
        return result;
    }

    /** Extends only the constrained outer row/column, never an ordinary tile edge. */
    private static void extendStitchedBoundary(double[] positions, int offset, int row, int column,
                                               double stepX, double stepY,
                                               EnumSet<TerrainEdge> stitchedEdges) {
        if (column == 0 && stitchedEdges.contains(TerrainEdge.WEST)) {
            positions[offset] -= stepX * STITCHED_EDGE_OVERLAP_STEPS;
        }
        if (column == CORE - 1 && stitchedEdges.contains(TerrainEdge.EAST)) {
            positions[offset] += stepX * STITCHED_EDGE_OVERLAP_STEPS;
        }
        if (row == 0 && stitchedEdges.contains(TerrainEdge.NORTH)) {
            positions[offset + 1] += stepY * STITCHED_EDGE_OVERLAP_STEPS;
        }
        if (row == CORE - 1 && stitchedEdges.contains(TerrainEdge.SOUTH)) {
            positions[offset + 1] -= stepY * STITCHED_EDGE_OVERLAP_STEPS;
        }
    }

    private static int countValidTriangles(HeightField field) {
        int count = 0;
        for (int row = 0; row < CORE - 1; row++) {
            for (int column = 0; column < CORE - 1; column++) {
                if (validRendered(field, row, column)
                    && validRendered(field, row + 1, column)
                    && validRendered(field, row, column + 1)) {
                    count++;
                }
                if (validRendered(field, row, column + 1)
                    && validRendered(field, row + 1, column)
                    && validRendered(field, row + 1, column + 1)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static double[] angleWeightedNormal(HeightField field, int storedRow, int storedColumn,
                                                 double stepX, double stepY) {
        if (!field.valid(storedRow, storedColumn)) {
            return new double[] {0.0, 0.0, 1.0};
        }
        double[] sum = new double[3];
        for (int cellRow = storedRow - 1; cellRow <= storedRow; cellRow++) {
            for (int cellColumn = storedColumn - 1; cellColumn <= storedColumn; cellColumn++) {
                accumulateTriangle(field, storedRow, storedColumn, stepX, stepY, sum,
                    cellRow, cellColumn, cellRow + 1, cellColumn, cellRow, cellColumn + 1);
                accumulateTriangle(field, storedRow, storedColumn, stepX, stepY, sum,
                    cellRow, cellColumn + 1, cellRow + 1, cellColumn,
                    cellRow + 1, cellColumn + 1);
            }
        }
        double length = Math.sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2]);
        if (!(length > 0.0)) {
            return new double[] {0.0, 0.0, 1.0};
        }
        return new double[] {sum[0] / length, sum[1] / length, sum[2] / length};
    }

    private static void accumulateTriangle(HeightField field, int targetRow, int targetColumn,
                                           double stepX, double stepY, double[] sum,
                                           int r0, int c0, int r1, int c1, int r2, int c2) {
        if (!contains(targetRow, targetColumn, r0, c0, r1, c1, r2, c2)
            || !insideStored(r0, c0) || !insideStored(r1, c1) || !insideStored(r2, c2)
            || !field.valid(r0, c0) || !field.valid(r1, c1) || !field.valid(r2, c2)) {
            return;
        }
        double[] p0 = point(field, r0, c0, stepX, stepY);
        double[] p1 = point(field, r1, c1, stepX, stepY);
        double[] p2 = point(field, r2, c2, stepX, stepY);
        double[] target;
        double[] other1;
        double[] other2;
        if (targetRow == r0 && targetColumn == c0) {
            target = p0; other1 = p1; other2 = p2;
        }
        else if (targetRow == r1 && targetColumn == c1) {
            target = p1; other1 = p2; other2 = p0;
        }
        else {
            target = p2; other1 = p0; other2 = p1;
        }

        double[] face = cross(subtract(p1, p0), subtract(p2, p0));
        double faceLength = length(face);
        double[] edge1 = subtract(other1, target);
        double[] edge2 = subtract(other2, target);
        double edgeProduct = length(edge1) * length(edge2);
        if (!(faceLength > 0.0) || !(edgeProduct > 0.0)) {
            return;
        }
        double cosine = dot(edge1, edge2) / edgeProduct;
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, cosine)));
        sum[0] += face[0] / faceLength * angle;
        sum[1] += face[1] / faceLength * angle;
        sum[2] += face[2] / faceLength * angle;
    }

    private static double[] point(HeightField field, int row, int column, double stepX, double stepY) {
        return new double[] {
            (column - 1) * stepX,
            -(row - 1) * stepY,
            field.elevation(row, column)
        };
    }

    private static boolean contains(int row, int column,
                                    int r0, int c0, int r1, int c1, int r2, int c2) {
        return (row == r0 && column == c0)
            || (row == r1 && column == c1)
            || (row == r2 && column == c2);
    }

    private static boolean insideStored(int row, int column) {
        return row >= 0 && row < DemTile.STORED_SIZE
            && column >= 0 && column < DemTile.STORED_SIZE;
    }

    private static boolean validRendered(HeightField field, int row, int column) {
        return field.valid(renderedStoredCoordinate(row), renderedStoredCoordinate(column));
    }

    /**
     * The 258 raster is [west/north halo, 256 core samples, east/south halo].
     * The shared edge is owned by the west/north halo of the tile on its
     * east/south side, so it is the same sample as the core edge of the other
     * tile.  Keeping the mesh at 256 vertices requires omitting core index 1
     * on those two edges rather than rendering two unequal samples at one
     * world-space edge.
     */
    private static int renderedStoredCoordinate(int renderedCoordinate) {
        return renderedCoordinate == 0 ? 0 : renderedCoordinate + 1;
    }

    private static double normalizedElevation(double elevationMetres) {
        return Double.isFinite(elevationMetres) ? elevationMetres / Configuration.WORLD_WIDTH_METRES : 0.0;
    }

    private static int vertexIndex(int row, int column) {
        return row * CORE + column;
    }

    private static double[] subtract(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        };
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double length(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    /** Normalized elevations after snapping stitched edges and their outside halo. */
    private static final class HeightField {
        private static final double WEIGHT_EPSILON = 1e-12;
        private final double[] values = new double[DemTile.SAMPLE_COUNT];
        private final boolean[] valid = new boolean[DemTile.SAMPLE_COUNT];
        private final double[] normalX = new double[DemTile.SAMPLE_COUNT];
        private final double[] normalY = new double[DemTile.SAMPLE_COUNT];
        private final double[] normalZ = new double[DemTile.SAMPLE_COUNT];
        private final int[] normalCounts = new int[DemTile.SAMPLE_COUNT];

        HeightField(QuadtreeNode fineNode, DemTile fineTile,
                    List<TerrainSeamConstraint> constraints) {
            for (int row = 0; row < DemTile.STORED_SIZE; row++) {
                for (int column = 0; column < DemTile.STORED_SIZE; column++) {
                    int index = index(row, column);
                    valid[index] = fineTile.elevation(row, column) != DemTile.NO_DATA;
                    values[index] = normalizedElevation(fineTile.elevationMetres(row, column));
                }
            }
            if (constraints == null || constraints.isEmpty()) {
                return;
            }

            double[] stitchedSums = new double[DemTile.SAMPLE_COUNT];
            int[] stitchedCounts = new int[DemTile.SAMPLE_COUNT];
            double stepX = (fineNode.getX1() - fineNode.getX0()) / (CORE - 1.0);
            double stepY = (fineNode.getY1() - fineNode.getY0()) / (CORE - 1.0);
            for (TerrainSeamConstraint constraint : constraints) {
                HeightField coarseField = new HeightField(
                    constraint.coarseNode(), constraint.coarseElevations(), List.of());
                for (int row = 0; row < DemTile.STORED_SIZE; row++) {
                    for (int column = 0; column < DemTile.STORED_SIZE; column++) {
                        if (!affected(constraint.fineEdge(), row, column)) {
                            continue;
                        }
                        double x = fineNode.getX0() + (column - 1) * stepX;
                        double y = fineNode.getY1() - (row - 1) * stepY;
                        Sample sample = sampleCoarse(constraint, coarseField, x, y);
                        if (sample.valid) {
                            int index = index(row, column);
                            stitchedSums[index] += sample.elevation;
                            stitchedCounts[index]++;
                            if (isRenderedBoundaryVertex(constraint.fineEdge(), row, column)) {
                                normalX[index] += sample.normalX;
                                normalY[index] += sample.normalY;
                                normalZ[index] += sample.normalZ;
                                normalCounts[index]++;
                            }
                        }
                    }
                }
            }
            for (int i = 0; i < normalCounts.length; i++) {
                if (normalCounts[i] == 0) {
                    continue;
                }
                double length = Math.sqrt(normalX[i] * normalX[i]
                    + normalY[i] * normalY[i] + normalZ[i] * normalZ[i]);
                if (length > 0.0) {
                    normalX[i] /= length;
                    normalY[i] /= length;
                    normalZ[i] /= length;
                }
            }
            for (int i = 0; i < values.length; i++) {
                if (stitchedCounts[i] > 0) {
                    values[i] = stitchedSums[i] / stitchedCounts[i];
                    valid[i] = true;
                }
            }
        }

        double elevation(int row, int column) {
            return values[index(row, column)];
        }

        boolean valid(int row, int column) {
            return insideStored(row, column) && valid[index(row, column)];
        }

        double[] stitchedNormal(int row, int column) {
            int index = index(row, column);
            if (normalCounts[index] == 0) {
                return null;
            }
            return new double[] {normalX[index], normalY[index], normalZ[index]};
        }

        private static boolean affected(TerrainEdge edge, int row, int column) {
            return switch (edge) {
                case NORTH -> row <= 1;
                case SOUTH -> row >= CORE;
                case WEST -> column <= 1;
                case EAST -> column >= CORE;
            };
        }

        private static boolean isRenderedBoundaryVertex(TerrainEdge edge, int row, int column) {
            return switch (edge) {
                case NORTH -> row == 0 && isRenderedStoredCoordinate(column);
                case SOUTH -> row == CORE && isRenderedStoredCoordinate(column);
                case WEST -> column == 0 && isRenderedStoredCoordinate(row);
                case EAST -> column == CORE && isRenderedStoredCoordinate(row);
            };
        }

        private static boolean isRenderedStoredCoordinate(int coordinate) {
            return coordinate == 0 || (coordinate >= 2 && coordinate <= CORE);
        }

        private static Sample sampleCoarse(TerrainSeamConstraint constraint, HeightField coarseField,
                                           double x, double y) {
            QuadtreeNode coarse = constraint.coarseNode();
            double columnPosition = (x - coarse.getX0()) / (coarse.getX1() - coarse.getX0()) * (CORE - 1.0);
            double rowPosition = (coarse.getY1() - y) / (coarse.getY1() - coarse.getY0()) * (CORE - 1.0);
            if (columnPosition < -WEIGHT_EPSILON || columnPosition > CORE - 1 + WEIGHT_EPSILON
                || rowPosition < -WEIGHT_EPSILON || rowPosition > CORE - 1 + WEIGHT_EPSILON) {
                return Sample.INVALID;
            }
            columnPosition = Math.max(0.0, Math.min(CORE - 1.0, columnPosition));
            rowPosition = Math.max(0.0, Math.min(CORE - 1.0, rowPosition));
            int c0 = (int) Math.floor(columnPosition);
            int r0 = (int) Math.floor(rowPosition);
            int c1 = Math.min(CORE - 1, c0 + 1);
            int r1 = Math.min(CORE - 1, r0 + 1);
            double tx = columnPosition - c0;
            double ty = rowPosition - r0;
            int[] rows = {r0, r0, r1, r1};
            int[] columns = {c0, c1, c0, c1};
            double[] weights = {(1 - tx) * (1 - ty), tx * (1 - ty), (1 - tx) * ty, tx * ty};
            double result = 0.0;
            double[] normal = new double[3];
            double stepX = (coarse.getX1() - coarse.getX0()) / (CORE - 1.0);
            double stepY = (coarse.getY1() - coarse.getY0()) / (CORE - 1.0);
            for (int i = 0; i < weights.length; i++) {
                if (weights[i] <= WEIGHT_EPSILON) {
                    continue;
                }
                int storedRow = renderedStoredCoordinate(rows[i]);
                int storedColumn = renderedStoredCoordinate(columns[i]);
                if (!coarseField.valid(storedRow, storedColumn)) {
                    return Sample.INVALID;
                }
                result += weights[i] * coarseField.elevation(storedRow, storedColumn);
                double[] vertexNormal = angleWeightedNormal(
                    coarseField, storedRow, storedColumn, stepX, stepY);
                normal[0] += weights[i] * vertexNormal[0];
                normal[1] += weights[i] * vertexNormal[1];
                normal[2] += weights[i] * vertexNormal[2];
            }
            double normalLength = Math.sqrt(normal[0] * normal[0]
                + normal[1] * normal[1] + normal[2] * normal[2]);
            if (!(normalLength > 0.0)) {
                return Sample.INVALID;
            }
            return new Sample(true, result, normal[0] / normalLength,
                normal[1] / normalLength, normal[2] / normalLength);
        }

        private static int index(int row, int column) {
            return row * DemTile.STORED_SIZE + column;
        }

        private record Sample(boolean valid, double elevation,
                              double normalX, double normalY, double normalZ) {
            private static final Sample INVALID = new Sample(false, 0.0, 0.0, 0.0, 1.0);
        }
    }
}
