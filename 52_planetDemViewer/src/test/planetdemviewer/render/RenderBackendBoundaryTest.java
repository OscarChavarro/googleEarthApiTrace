package planetdemviewer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The graphics backend only controls the GPU, and nothing else in render knows about it. */
class RenderBackendBoundaryTest {
    private static final Path RENDER = Path.of("src/main/planetdemviewer/render");
    private static final List<String> NOT_GPU_WORK = List.of(
        "java.io.", "java.nio.file.", "System.out", "System.err", "planetdemviewer.io.",
        "planetdemviewer.logger.", "ImagePersistence", "AWTTextureIO", "util.texture.", "com.jogamp.common.nio");
    private static final List<Path> BACKEND_FOLDERS = List.of(RENDER.resolve("jogl4"), RENDER.resolve("jogl4/terrain"));
    private static final List<Path> BACKEND_NEUTRAL_FOLDERS = List.of(RENDER, RENDER.resolve("terrain"));
    private static final List<Path> AWT_FREE_FOLDERS = List.of(RENDER, RENDER.resolve("terrain"),
        RENDER.resolve("jogl4"), RENDER.resolve("jogl4/terrain"),
        Path.of("src/main/planetdemviewer/io"), Path.of("src/main/planetdemviewer/io/imagery"),
        Path.of("src/main/planetdemviewer/model"), Path.of("src/main/planetdemviewer/terrain"));

    private static List<Path> javaFilesOf(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void theOpenGlBackendDoesNoInputOutputNorApplicationWork() throws IOException {
        for (Path folder : BACKEND_FOLDERS) {
            List<Path> backend = javaFilesOf(folder);
            assertTrue(!backend.isEmpty(), folder + " has no classes");
            for (Path file : backend) {
                String source = Files.readString(file);
                for (String forbidden : NOT_GPU_WORK) {
                    assertTrue(!source.contains(forbidden), file + " uses " + forbidden);
                }
            }
        }
    }

    @Test
    void renderingAndDataDoNotDependOnAwt() throws IOException {
        for (Path folder : AWT_FREE_FOLDERS) {
            for (Path file : javaFilesOf(folder)) {
                String source = Files.readString(file);
                for (String forbidden : List.of("java.awt", "javax.imageio", "BufferedImage")) {
                    assertTrue(!source.contains(forbidden), file + " uses " + forbidden);
                }
            }
        }
    }

    @Test
    void theBackendNeutralClassesDoNotDependOnAGraphicsApi() throws IOException {
        for (Path folder : BACKEND_NEUTRAL_FOLDERS) {
            List<Path> neutral = javaFilesOf(folder);
            assertTrue(!neutral.isEmpty(), folder + " has no classes");
            for (Path file : neutral) {
                String source = Files.readString(file);
                assertEquals(false, source.contains("com.jogamp") || source.contains("render.jogl")
                    || source.contains("planetdemviewer.gui"),
                    file + " depends on OpenGL or on the GUI toolkit");
            }
        }
    }
}
