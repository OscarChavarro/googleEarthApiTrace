package planetdemviewer.terrain;

import planetdemviewer.config.Configuration;
import planetdemviewer.model.DemTile;
import planetdemviewer.model.QuadtreeNode;
import vsdk.toolkit.environment.geometry.surface.TriangleMesh;

/**
 * Builds the regular two-triangles-per-cell DEM mesh.  The core owns the
 * 256x256 rendered vertices; the one-sample halo participates only in the
 * angle-weighted vertex-normal calculation.
 */
public final class BasicTerrainMeshGenerator implements TerrainMeshGenerator<TriangleMesh> {
    private static final int CORE = DemTile.CORE_SIZE;

    @Override
    public TriangleMesh generate(QuadtreeNode node, DemTile elevationsWithHalo) {
        if (node == null || elevationsWithHalo == null) {
            throw new IllegalArgumentException("A quadtree node and DEM tile are required");
        }

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
                positions[p + 2] = normalizedElevation(elevationsWithHalo.elevation(row + 1, column + 1));

                double[] normal = angleWeightedNormal(
                    elevationsWithHalo, row + 1, column + 1, stepX, stepY);
                normals[p] = normal[0];
                normals[p + 1] = normal[1];
                normals[p + 2] = normal[2];
            }
        }

        int triangleCount = countValidTriangles(elevationsWithHalo);
        mesh.initTriangleArrays(triangleCount);
        int[] indices = mesh.getTriangleIndexes();
        int out = 0;
        for (int row = 0; row < CORE - 1; row++) {
            for (int column = 0; column < CORE - 1; column++) {
                int a = vertexIndex(row, column);
                int b = vertexIndex(row + 1, column);
                int c = vertexIndex(row, column + 1);
                int d = vertexIndex(row + 1, column + 1);
                if (valid(elevationsWithHalo, row + 1, column + 1)
                    && valid(elevationsWithHalo, row + 2, column + 1)
                    && valid(elevationsWithHalo, row + 1, column + 2)) {
                    indices[out++] = a;
                    indices[out++] = b;
                    indices[out++] = c;
                }
                if (valid(elevationsWithHalo, row + 1, column + 2)
                    && valid(elevationsWithHalo, row + 2, column + 1)
                    && valid(elevationsWithHalo, row + 2, column + 2)) {
                    indices[out++] = c;
                    indices[out++] = b;
                    indices[out++] = d;
                }
            }
        }
        return mesh;
    }

    private static int countValidTriangles(DemTile tile) {
        int count = 0;
        for (int row = 1; row < CORE; row++) {
            for (int column = 1; column < CORE; column++) {
                if (valid(tile, row, column)
                    && valid(tile, row + 1, column)
                    && valid(tile, row, column + 1)) {
                    count++;
                }
                if (valid(tile, row, column + 1)
                    && valid(tile, row + 1, column)
                    && valid(tile, row + 1, column + 1)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static double[] angleWeightedNormal(DemTile tile, int storedRow, int storedColumn,
                                                 double stepX, double stepY) {
        if (!valid(tile, storedRow, storedColumn)) {
            return new double[] {0.0, 0.0, 1.0};
        }
        double[] sum = new double[3];
        for (int cellRow = storedRow - 1; cellRow <= storedRow; cellRow++) {
            for (int cellColumn = storedColumn - 1; cellColumn <= storedColumn; cellColumn++) {
                accumulateTriangle(tile, storedRow, storedColumn, stepX, stepY, sum,
                    cellRow, cellColumn, cellRow + 1, cellColumn, cellRow, cellColumn + 1);
                accumulateTriangle(tile, storedRow, storedColumn, stepX, stepY, sum,
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

    private static void accumulateTriangle(DemTile tile, int targetRow, int targetColumn,
                                           double stepX, double stepY, double[] sum,
                                           int r0, int c0, int r1, int c1, int r2, int c2) {
        if (!contains(targetRow, targetColumn, r0, c0, r1, c1, r2, c2)
            || !insideStored(r0, c0) || !insideStored(r1, c1) || !insideStored(r2, c2)
            || !valid(tile, r0, c0) || !valid(tile, r1, c1) || !valid(tile, r2, c2)) {
            return;
        }
        double[] p0 = point(tile, r0, c0, stepX, stepY);
        double[] p1 = point(tile, r1, c1, stepX, stepY);
        double[] p2 = point(tile, r2, c2, stepX, stepY);
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

    private static double[] point(DemTile tile, int row, int column, double stepX, double stepY) {
        return new double[] {
            (column - 1) * stepX,
            -(row - 1) * stepY,
            normalizedElevation(tile.elevation(row, column))
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

    private static boolean valid(DemTile tile, int row, int column) {
        return insideStored(row, column) && tile.elevation(row, column) != DemTile.NO_DATA;
    }

    private static double normalizedElevation(short elevation) {
        return elevation == DemTile.NO_DATA ? 0.0 : elevation / Configuration.WORLD_WIDTH_METRES;
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
}
