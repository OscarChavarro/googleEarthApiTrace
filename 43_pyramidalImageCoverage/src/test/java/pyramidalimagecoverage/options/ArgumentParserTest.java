package pyramidalimagecoverage.options;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArgumentParserTest {
    @TempDir
    Path temporaryFolder;

    @Test
    void acceptsExactlyOneValidPyramidFolder() throws IOException {
        Files.createFile(temporaryFolder.resolve("0.png"));
        assertEquals(temporaryFolder.toAbsolutePath(), ArgumentParser.parse(new String[] {
            temporaryFolder.toString()
        }).pyramidalImageFolder());
    }

    @Test
    void rejectsMissingAndAdditionalArguments() throws IOException {
        Files.createFile(temporaryFolder.resolve("0.png"));
        assertThrows(IllegalArgumentException.class, () -> ArgumentParser.parse(new String[0]));
        assertThrows(IllegalArgumentException.class, () -> ArgumentParser.parse(new String[] {
            temporaryFolder.toString(), "extra"
        }));
    }
}
