package demresampler.options;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliOptionsTest {
    @Test
    void parsesRequiredFoldersAndParallelOptions() {
        CliOptions options = CliOptions.parse(new String[]{
            "/input", "/output", "--threads", "72", "--resume"
        });
        assertEquals(72, options.threads());
        assertTrue(options.resume());
    }

    @Test
    void rejectsMissingFolders() {
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(new String[]{"/input"}));
    }
}
