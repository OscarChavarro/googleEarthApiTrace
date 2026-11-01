package planetdemviewer.terrain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import planetdemviewer.model.DemTile;
import planetdemviewer.model.QuadtreeNode;
import planetdemviewer.config.Configuration;
import vsdk.toolkit.environment.geometry.surface.TriangleMesh;

class BasicTerrainMeshGeneratorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void buildsRequestedRegularTopologyWithUpwardNormals() throws Exception {
        short[] elevations = new short[DemTile.SAMPLE_COUNT];
        Arrays.fill(elevations, (short) 100);
        TriangleMesh mesh = generate(elevations);

        assertEquals(256 * 256, mesh.getNumVertices());
        assertEquals(255 * 255 * 2, mesh.getNumTriangles());
        assertArrayEquals(new int[] {0, 256, 1, 1, 256, 257},
            Arrays.copyOf(mesh.getTriangleIndexes(), 6));
        assertEquals(0.0, mesh.getVertexNormals()[0], 1e-12);
        assertEquals(0.0, mesh.getVertexNormals()[1], 1e-12);
        assertEquals(1.0, mesh.getVertexNormals()[2], 1e-12);
    }

    @Test
    void haloSamplesInfluenceBoundaryVertexNormals() throws Exception {
        short[] elevations = new short[DemTile.SAMPLE_COUNT];
        Arrays.fill(elevations, (short) 0);
        elevations[1] = 10_000; // north halo, immediately above core vertex (0,0)
        TriangleMesh mesh = generate(elevations);

        double nx = mesh.getVertexNormals()[0];
        double ny = mesh.getVertexNormals()[1];
        double nz = mesh.getVertexNormals()[2];
        assertTrue(Math.abs(nx) > 1e-12 || Math.abs(ny) > 1e-12);
        assertTrue(nz > 0.0);
    }

    @Test
    void fineEdgeUsesLinearInterpolationFromRenderedCoarseNeighbour() throws Exception {
        short[] fineElevations = new short[DemTile.SAMPLE_COUNT];
        Arrays.fill(fineElevations, (short) 1_000);
        short[] coarseElevations = new short[DemTile.SAMPLE_COUNT];
        for (int row = 0; row < DemTile.STORED_SIZE; row++) {
            short elevation = (short) Math.max(0, Math.min(2_550, (row - 1) * 10));
            for (int column = 0; column < DemTile.STORED_SIZE; column++) {
                coarseElevations[row * DemTile.STORED_SIZE + column] = elevation;
            }
        }

        Path fineFile = temporaryDirectory.resolve("fine.bin");
        Path coarseFile = temporaryDirectory.resolve("coarse.bin");
        DemTile fineTile = writeAndRead(fineFile, fineElevations);
        DemTile coarseTile = writeAndRead(coarseFile, coarseElevations);
        QuadtreeNode root = new QuadtreeNode("0", null, temporaryDirectory.resolve("root.bin").toFile());
        QuadtreeNode southWest = new QuadtreeNode("00", root, temporaryDirectory.resolve("sw.bin").toFile());
        QuadtreeNode fine = new QuadtreeNode("002", southWest, fineFile.toFile());
        QuadtreeNode coarse = new QuadtreeNode("01", root, coarseFile.toFile());

        TriangleMesh mesh = new BasicTerrainMeshGenerator().generate(
            fine,
            fineTile,
            List.of(new TerrainSeamConstraint(TerrainEdge.EAST, coarse, coarseTile)));

        int eastVertexOnSecondRow = (DemTile.CORE_SIZE + DemTile.CORE_SIZE - 1) * 3;
        assertEquals(5.0 / Configuration.WORLD_WIDTH_METRES,
            mesh.getVertexPositions()[eastVertexOnSecondRow + 2], 1e-15);
    }

    @Test
    void stitchedFineEdgeOverlapsTheCoarseTileByOneFineGridStep() throws Exception {
        short[] elevations = new short[DemTile.SAMPLE_COUNT];
        Arrays.fill(elevations, (short) 1_000);
        DemTile fineTile = writeAndRead(temporaryDirectory.resolve("overlap-fine.bin"), elevations);
        DemTile coarseTile = writeAndRead(temporaryDirectory.resolve("overlap-coarse.bin"), elevations);
        QuadtreeNode root = new QuadtreeNode("0", null, temporaryDirectory.resolve("root.bin").toFile());
        QuadtreeNode fine = new QuadtreeNode("00", root, temporaryDirectory.resolve("fine.bin").toFile());
        QuadtreeNode coarse = new QuadtreeNode("01", root, temporaryDirectory.resolve("coarse.bin").toFile());

        TriangleMesh unstiched = new BasicTerrainMeshGenerator().generate(fine, fineTile);
        TriangleMesh stitched = new BasicTerrainMeshGenerator().generate(fine, fineTile,
            List.of(new TerrainSeamConstraint(TerrainEdge.EAST, coarse, coarseTile)));

        int eastVertex = DemTile.CORE_SIZE - 1;
        double fineStep = (fine.getX1() - fine.getX0()) / (DemTile.CORE_SIZE - 1.0);
        assertEquals(fineStep, stitched.getVertexPositions()[eastVertex * 3]
            - unstiched.getVertexPositions()[eastVertex * 3], 1e-15);
        assertEquals(unstiched.getVertexPositions()[0], stitched.getVertexPositions()[0], 0.0);
    }

    @Test
    void fineEdgeUsesTheInterpolatedCoarseVertexNormal() throws Exception {
        short[] fineElevations = new short[DemTile.SAMPLE_COUNT];
        Arrays.fill(fineElevations, (short) 1_000);
        short[] coarseElevations = new short[DemTile.SAMPLE_COUNT];
        for (int row = 0; row < DemTile.STORED_SIZE; row++) {
            for (int column = 0; column < DemTile.STORED_SIZE; column++) {
                coarseElevations[row * DemTile.STORED_SIZE + column] =
                    (short) ((row - 1) * 6 + (column - 1) * 4);
            }
        }

        Path fineFile = temporaryDirectory.resolve("normal-fine.bin");
        Path coarseFile = temporaryDirectory.resolve("normal-coarse.bin");
        DemTile fineTile = writeAndRead(fineFile, fineElevations);
        DemTile coarseTile = writeAndRead(coarseFile, coarseElevations);
        QuadtreeNode root = new QuadtreeNode("0", null, temporaryDirectory.resolve("root.bin").toFile());
        QuadtreeNode southWest = new QuadtreeNode("00", root, temporaryDirectory.resolve("sw.bin").toFile());
        QuadtreeNode fine = new QuadtreeNode("002", southWest, fineFile.toFile());
        QuadtreeNode coarse = new QuadtreeNode("01", root, coarseFile.toFile());
        BasicTerrainMeshGenerator generator = new BasicTerrainMeshGenerator();
        TriangleMesh coarseMesh = generator.generate(coarse, coarseTile);
        TriangleMesh fineMesh = generator.generate(fine, fineTile,
            List.of(new TerrainSeamConstraint(TerrainEdge.EAST, coarse, coarseTile)));

        double[] coarseNormals = coarseMesh.getVertexNormals();
        double[] expected = normalize(
            coarseNormals[0] + coarseNormals[DemTile.CORE_SIZE * 3] ,
            coarseNormals[1] + coarseNormals[DemTile.CORE_SIZE * 3 + 1],
            coarseNormals[2] + coarseNormals[DemTile.CORE_SIZE * 3 + 2]);
        int fineEdge = (DemTile.CORE_SIZE + DemTile.CORE_SIZE - 1) * 3;
        assertEquals(expected[0], fineMesh.getVertexNormals()[fineEdge], 1e-12);
        assertEquals(expected[1], fineMesh.getVertexNormals()[fineEdge + 1], 1e-12);
        assertEquals(expected[2], fineMesh.getVertexNormals()[fineEdge + 2], 1e-12);
    }

    @Test
    void sameResolutionNeighboursShareExactlyTheRenderedC0Edge() throws Exception {
        short[] westSamples = new short[DemTile.SAMPLE_COUNT];
        short[] eastSamples = new short[DemTile.SAMPLE_COUNT];
        Arrays.fill(westSamples, (short) 100);
        Arrays.fill(eastSamples, (short) 200);
        for (int row = 0; row < DemTile.STORED_SIZE; row++) {
            westSamples[row * DemTile.STORED_SIZE + DemTile.CORE_SIZE] = (short) (1_000 + row);
            eastSamples[row * DemTile.STORED_SIZE] = (short) (1_000 + row);
        }

        DemTile westTile = writeAndRead(temporaryDirectory.resolve("west.bin"), westSamples);
        DemTile eastTile = writeAndRead(temporaryDirectory.resolve("east.bin"), eastSamples);
        QuadtreeNode root = new QuadtreeNode("0", null, temporaryDirectory.resolve("root.bin").toFile());
        QuadtreeNode west = new QuadtreeNode("00", root, temporaryDirectory.resolve("west.bin").toFile());
        QuadtreeNode east = new QuadtreeNode("01", root, temporaryDirectory.resolve("east.bin").toFile());

        TriangleMesh westMesh = new BasicTerrainMeshGenerator().generate(west, westTile);
        TriangleMesh eastMesh = new BasicTerrainMeshGenerator().generate(east, eastTile);
        for (int row = 0; row < DemTile.CORE_SIZE; row++) {
            int westEdge = (row * DemTile.CORE_SIZE + DemTile.CORE_SIZE - 1) * 3 + 2;
            int eastEdge = row * DemTile.CORE_SIZE * 3 + 2;
            assertEquals(westMesh.getVertexPositions()[westEdge], eastMesh.getVertexPositions()[eastEdge], 0.0);
        }
    }

    private TriangleMesh generate(short[] elevations) throws Exception {
        ByteBuffer bytes = ByteBuffer.allocate(DemTile.BYTE_COUNT).order(ByteOrder.LITTLE_ENDIAN);
        for (short elevation : elevations) {
            bytes.putShort(elevation);
        }
        Path file = temporaryDirectory.resolve("0.bin");
        Files.write(file, bytes.array());
        DemTile tile = DemTile.read(file);
        QuadtreeNode node = new QuadtreeNode("0", null, file.toFile());
        return new BasicTerrainMeshGenerator().generate(node, tile);
    }

    private DemTile writeAndRead(Path file, short[] elevations) throws Exception {
        ByteBuffer bytes = ByteBuffer.allocate(DemTile.BYTE_COUNT).order(ByteOrder.LITTLE_ENDIAN);
        for (short elevation : elevations) {
            bytes.putShort(elevation);
        }
        Files.write(file, bytes.array());
        return DemTile.read(file);
    }

    private static double[] normalize(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / length, y / length, z / length};
    }
}
