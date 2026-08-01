package matrixmerger.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MatrixLayerExportWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesCurrentScopedUncleIdThroughItsFrameJsonCanonicalTexture() throws Exception {
        Path texture = tempDir.resolve("canonical-parent.png");
        Files.write(texture, new byte[]{1, 2, 3});
        Path frameDirectory = Files.createDirectories(tempDir.resolve("00167"));
        Files.writeString(frameDirectory.resolve("frame.json"), """
            {"tiles":[
              {"contentId":"167_99","textureFile":"%s"}
            ]}
            """.formatted(texture.toString().replace("\\", "\\\\")));

        MatrixLayerExportWriter writer = new MatrixLayerExportWriter(null, tempDir);

        assertEquals(texture, writer.resolveExternalUncleTexture("00167_99"));
        assertNull(writer.resolveExternalUncleTexture("invalid"));
    }
}
