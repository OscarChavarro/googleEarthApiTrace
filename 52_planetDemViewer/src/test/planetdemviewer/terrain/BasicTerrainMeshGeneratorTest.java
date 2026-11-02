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

    @Test
    void adaptiveMeshKeepsItsVerticesAndUploadsOnlyChangedTriangles() throws Exception {
        short[] samples = new short[DemTile.SAMPLE_COUNT];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) (2_000 + 1_500 * Math.sin(i * 0.003) + (i * 7919 % 17));
        }
        DemTile tile = writeAndRead(temporaryDirectory.resolve("adaptive.bin"), samples);
        QuadtreeNode node = new QuadtreeNode("0", null, temporaryDirectory.resolve("adaptive.bin").toFile());
        AdaptiveTerrainMesh mesh = new BasicTerrainMeshGenerator(TerrainSubdivisionScheme.LIND1996.traversal())
            .generateAdaptive(node, tile, List.of(), null);
        assertEquals(257 * 257, mesh.getNumVertices());
        assertEquals(131_072, mesh.getNumTriangles());

        TerrainMeshPlacement placement = new TerrainMeshPlacement(0.0, 0.0, 0.0, 1.0, 1.0);
        ProjectedDeltaCriterion criterion = new ProjectedDeltaCriterion(0.3, -0.9, 0.2, 1e-3);
        mesh.simplify(criterion, placement);
        assertEquals(131_072, mesh.fullTriangleCount());
        assertTrue(mesh.getNumTriangles() < 131_072, "got " + mesh.getNumTriangles());
        assertArrayEquals(mesh.getTriangleIndexes(), mesh.takeTrianglesToUpload());

        for (int pass = 0; pass < 64; pass++) {
            mesh.takeTrianglesToUpload();
            mesh.simplify(criterion, placement);
        }
        mesh.takeTrianglesToUpload();
        mesh.simplify(criterion, placement);
        assertEquals(null, mesh.takeTrianglesToUpload());

        mesh.useAllTriangles();
        assertEquals(131_072, mesh.getNumTriangles());
        assertEquals(131_072 * 3, mesh.takeTrianglesToUpload().length);
    }

    @Test
    void levelJumpComparesTheVerticesAParentAndItsChildShare() throws Exception {
        short[] parentSamples = new short[DemTile.SAMPLE_COUNT];
        short[] childSamples = new short[DemTile.SAMPLE_COUNT];
        Arrays.fill(parentSamples, (short) 100);
        Arrays.fill(childSamples, (short) 100);
        // Stored (10, 140) of the parent is in its north-east quadrant, at (20, 24) of that child.
        parentSamples[10 * DemTile.STORED_SIZE + 140] = 150;
        DemTile parent = writeAndRead(temporaryDirectory.resolve("parent.bin"), parentSamples);
        DemTile child = writeAndRead(temporaryDirectory.resolve("child.bin"), childSamples);
        assertEquals(50.0 / Configuration.WORLD_WIDTH_METRES, VertexDeltaTable.levelJump(parent, child, '2'), 1e-15);
        assertEquals(0.0, VertexDeltaTable.levelJump(parent, child, '0'), 1e-15);
    }

    @Test
    void diamondSquareMeshRendersEveryStoredSampleFromZeroTo256() throws Exception {
        short[] samples = new short[DemTile.SAMPLE_COUNT];
        for (int row = 0; row < DemTile.STORED_SIZE; row++) {
            for (int column = 0; column < DemTile.STORED_SIZE; column++) {
                samples[row * DemTile.STORED_SIZE + column] = (short) (row * 10 + column);
            }
        }
        DemTile tile = writeAndRead(temporaryDirectory.resolve("diamond.bin"), samples);
        QuadtreeNode node = new QuadtreeNode("0", null, temporaryDirectory.resolve("diamond.bin").toFile());

        TriangleMesh mesh = diamondSquare().generate(node, tile);

        assertEquals(257 * 257, mesh.getNumVertices());
        assertEquals(2 * 256 * 256, mesh.getNumTriangles());
        for (int[] sample : new int[][] {{0, 0}, {0, 1}, {1, 0}, {7, 3}, {256, 256}}) {
            int z = (sample[0] * 257 + sample[1]) * 3 + 2;
            double metres = tile.elevationMetres(sample[0], sample[1]);
            assertEquals(metres / Configuration.WORLD_WIDTH_METRES, mesh.getVertexPositions()[z], 1e-15);
        }
        // World positions are uniform: sample 1 is 1/256 of the tile from its west edge.
        assertEquals(1.0 / 256.0, mesh.getVertexPositions()[3] - mesh.getVertexPositions()[0], 1e-12);
    }

    @Test
    void diamondSquareSameResolutionNeighboursShareTheirWholeEdge() throws Exception {
        short[] westSamples = new short[DemTile.SAMPLE_COUNT];
        short[] eastSamples = new short[DemTile.SAMPLE_COUNT];
        Arrays.fill(westSamples, (short) 100);
        Arrays.fill(eastSamples, (short) 200);
        for (int row = 0; row < DemTile.STORED_SIZE; row++) {
            westSamples[row * DemTile.STORED_SIZE + 256] = (short) (1_000 + row);
            eastSamples[row * DemTile.STORED_SIZE] = (short) (1_000 + row);
        }
        DemTile westTile = writeAndRead(temporaryDirectory.resolve("dw.bin"), westSamples);
        DemTile eastTile = writeAndRead(temporaryDirectory.resolve("de.bin"), eastSamples);
        QuadtreeNode root = new QuadtreeNode("0", null, temporaryDirectory.resolve("root.bin").toFile());
        QuadtreeNode west = new QuadtreeNode("00", root, temporaryDirectory.resolve("dw.bin").toFile());
        QuadtreeNode east = new QuadtreeNode("01", root, temporaryDirectory.resolve("de.bin").toFile());

        double[] westPositions = diamondSquare().generate(west, westTile).getVertexPositions();
        double[] eastPositions = diamondSquare().generate(east, eastTile).getVertexPositions();

        for (int row = 0; row <= 256; row++) {
            int westEdge = (row * 257 + 256) * 3;
            int eastEdge = row * 257 * 3;
            for (int axis = 0; axis < 3; axis++) {
                assertEquals(westPositions[westEdge + axis], eastPositions[eastEdge + axis], 1e-12);
            }
        }
    }

    @Test
    void diamondSquareFineEdgeInterpolatesTheCoarseEdgeAndOverlapsIt() throws Exception {
        short[] fineElevations = new short[DemTile.SAMPLE_COUNT];
        Arrays.fill(fineElevations, (short) 1_000);
        short[] coarseElevations = new short[DemTile.SAMPLE_COUNT];
        for (int row = 0; row < DemTile.STORED_SIZE; row++) {
            for (int column = 0; column < DemTile.STORED_SIZE; column++) {
                coarseElevations[row * DemTile.STORED_SIZE + column] = (short) (row * 10);
            }
        }
        Path fineFile = temporaryDirectory.resolve("dfine.bin");
        Path coarseFile = temporaryDirectory.resolve("dcoarse.bin");
        DemTile fineTile = writeAndRead(fineFile, fineElevations);
        DemTile coarseTile = writeAndRead(coarseFile, coarseElevations);
        QuadtreeNode root = new QuadtreeNode("0", null, temporaryDirectory.resolve("root.bin").toFile());
        QuadtreeNode southWest = new QuadtreeNode("00", root, temporaryDirectory.resolve("sw.bin").toFile());
        QuadtreeNode fine = new QuadtreeNode("002", southWest, fineFile.toFile());
        QuadtreeNode coarse = new QuadtreeNode("01", root, coarseFile.toFile());

        TriangleMesh mesh = diamondSquare().generate(fine, fineTile,
            List.of(new TerrainSeamConstraint(TerrainEdge.EAST, coarse, coarseTile)));

        // Fine row 1 lies halfway between the first two rows of the coarse edge: 0 m and 10 m.
        int eastVertex = (257 + 256) * 3;
        assertEquals(5.0 / Configuration.WORLD_WIDTH_METRES, mesh.getVertexPositions()[eastVertex + 2], 1e-15);
        double fineStep = (fine.getX1() - fine.getX0()) / 256.0;
        assertEquals(fine.getX1() - 0.5 + fineStep, mesh.getVertexPositions()[eastVertex], 1e-12);
    }

    @Test
    void diamondSquareOmitsTrianglesThatTouchNoData() throws Exception {
        short[] samples = new short[DemTile.SAMPLE_COUNT];
        Arrays.fill(samples, (short) 100);
        samples[2 * DemTile.STORED_SIZE + 2] = DemTile.NO_DATA;
        DemTile tile = writeAndRead(temporaryDirectory.resolve("dnodata.bin"), samples);
        QuadtreeNode node = new QuadtreeNode("0", null, temporaryDirectory.resolve("dnodata.bin").toFile());

        TriangleMesh mesh = diamondSquare().generate(node, tile);

        // Both coordinates even, so this is one of the mesh's eight-triangle vertices.
        assertEquals(2 * 256 * 256 - 8, mesh.getNumTriangles());
    }

    private static BasicTerrainMeshGenerator diamondSquare() {
        return new BasicTerrainMeshGenerator(TerrainSubdivisionScheme.DIAMOND_SQUARE.traversal());
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
