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
}
