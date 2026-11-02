package planetdemviewer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import planetdemviewer.model.QuadtreeNode;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.spatial.Ray3;
import planetdemviewer.spatial.WorldPosition;
import planetdemviewer.terrain.TerrainSubdivisionScheme;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.geometry.surface.TriangleMesh;
import vsdk.toolkit.media.RGBAImageUncompressed;

class TerrainFramePlannerTest {
    private final TerrainPickFrame pickFrame = new TerrainPickFrame();
    private final TerrainFramePlanner<String, String> planner = new TerrainFramePlanner<>(
        null, null, null, new TerrainSimplificationStatistics(), pickFrame);
    private final Camera camera = new Camera();
    private final TerrainFrameSettings settings = new TerrainFrameSettings(camera, 1.0, 1.0, false,
        TerrainSubdivisionScheme.TRIANGLE_EDGE, false, null, false, 1.0, true);
    private final List<String> uploaded = new ArrayList<>();
    private final List<String> destroyed = new ArrayList<>();
    private final TerrainMeshUploader<String> gpu = new TerrainMeshUploader<>() {
        @Override
        public String upload(TerrainMeshBuffers buffers) {
            String mesh = "mesh" + uploaded.size();
            uploaded.add(mesh);
            return mesh;
        }

        @Override
        public void replaceTriangles(String mesh, IntBuffer indices) {
            throw new AssertionError("A mesh that is not view-dependent keeps its triangles");
        }

        @Override
        public void destroy(String mesh) {
            destroyed.add(mesh);
        }
    };
    private final TextureUploader<String> textures = new TextureUploader<>() {
        @Override
        public String upload(RGBAImageUncompressed image) {
            throw new AssertionError("These items show no imagery");
        }

        @Override
        public void destroy(String texture) {
            throw new AssertionError("These items show no imagery");
        }
    };

    @AfterEach
    void stopPlanner() {
        planner.shutdown();
    }

    private List<TerrainMeshDraw<String, String>> prepare(TerrainDrawItem... items) {
        return planner.prepareItems(List.of(items), settings, camera, gpu, textures);
    }

    private static TerrainDrawItem item(String variantKey, CompletableFuture<TriangleMesh> preparation) {
        QuadtreeNode node = new QuadtreeNode("0", null, Path.of("0.bin").toFile());
        return new TerrainDrawItem(new DrawCommand(node, new Vector3Dd[0]), variantKey, preparation,
            Matrix4x4d.identityMatrix(), 0.0, 0.0, 0.0, 1.0, 1.0, false, null);
    }

    /** A grid of two by two vertices on the plane z = 0, with one triangle around the origin. */
    private static CompletableFuture<TriangleMesh> triangle() {
        TriangleMesh mesh = new TriangleMesh();
        mesh.initVertexPositionsArray(4);
        mesh.initVertexNormalsArray();
        mesh.initTriangleArrays(1);
        System.arraycopy(new double[] {-1, -1, 0, 1, -1, 0, -1, 1, 0, 1, 1, 0}, 0, mesh.getVertexPositions(), 0, 12);
        System.arraycopy(new int[] {0, 1, 3}, 0, mesh.getTriangleIndexes(), 0, 3);
        return CompletableFuture.completedFuture(mesh);
    }

    @Test
    void aMeshIsUploadedOnceAndDrawnEveryFrame() {
        assertEquals("mesh0", prepare(item("a", triangle())).get(0).mesh());
        assertEquals("mesh0", prepare(item("a", null)).get(0).mesh());
        assertEquals(List.of("mesh0"), uploaded);
    }

    @Test
    void anItemWithoutAMeshIsNotDrawn() {
        assertEquals(List.of(), prepare(item("a", null)));
    }

    @Test
    void aMeshThatFailsToGenerateDoesNotStopTheOthers() {
        List<TerrainMeshDraw<String, String>> draws = prepare(
            item("bad", CompletableFuture.failedFuture(new IllegalStateException("broken page"))),
            item("good", triangle()));
        assertEquals(1, draws.size());
        assertEquals("good", draws.get(0).item().variantKey());
    }

    @Test
    void whatIsDrawnBecomesWhatPickingSees() {
        Ray3 down = new Ray3(new WorldPosition(0, 0, 5), new WorldPosition(0, 0, -1));
        prepare(item("a", null));
        assertNull(pickFrame.intersect(down));
        prepare(item("a", triangle()));
        assertNotNull(pickFrame.intersect(down));
    }

    @Test
    void aDrawListsItsCommandAndHasNoImageryUnlessAsked() {
        TerrainDrawItem item = item("a", triangle());
        List<TerrainMeshDraw<String, String>> draws = prepare(item);
        assertNull(draws.get(0).imagery());
        assertEquals(List.of(item.command()), TerrainMeshDraw.commands(draws));
    }

    @Test
    void clearingDestroysEveryMesh() {
        prepare(item("a", triangle()), item("b", triangle()));
        planner.clearMeshes(gpu);
        assertEquals(List.of("mesh0", "mesh1"), destroyed);
        assertEquals(List.of(), prepare(item("a", null)));
    }
}
