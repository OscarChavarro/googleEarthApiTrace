package matrixmerger.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import matrixmerger.model.contract.FrameMatrixSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrameMatrixReaderTest {
    @TempDir
    Path tempDir;

    @Test
    void readsAllMatricesFromVersionTwoEnvelope() throws Exception {
        Path frameDirectory = Files.createDirectory(tempDir.resolve("00020"));
        Files.writeString(frameDirectory.resolve("matrix.json"), """
            {
              "contractVersion": 2,
              "frameId": 20,
              "matrices": [
                {
                  "rows": 1,
                  "cols": 2,
                  "tiles": [
                    {"id": "00020_1", "i": 0, "j": 0, "textureFile": "/tmp/1.png", "uncles": []},
                    {"id": "00020_2", "i": 0, "j": 1, "textureFile": "/tmp/2.png", "uncles": []}
                  ]
                },
                {
                  "rows": 2,
                  "cols": 1,
                  "tiles": [
                    {"id": "00020_3", "i": 0, "j": 0, "textureFile": "/tmp/3.png", "uncles": []},
                    {"id": "00020_4", "i": 1, "j": 0, "textureFile": "/tmp/4.png", "uncles": []}
                  ]
                }
              ]
            }
            """);

        List<FrameMatrixSet> frames = new FrameMatrixReader().readAllFromOutput(tempDir);

        assertEquals(1, frames.size());
        assertEquals(2, frames.get(0).getMatrices().size());
        assertEquals(20, frames.get(0).getFrameId());
        assertEquals(2, frames.get(0).getContractVersion());
    }
}
