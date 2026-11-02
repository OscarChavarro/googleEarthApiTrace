package planetdemviewer.palette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import org.junit.jupiter.api.Test;
import planetdemviewer.config.Configuration;

class PaletteCatalogTest {
    @Test
    void loadsAllRepositoryPalettesAndCyclesFromTopographic() {
        PaletteCatalog palettes = new PaletteCatalog(
            Configuration.PALETTE_DIRECTORY,
            Configuration.MINIMUM_ELEVATION_METRES,
            Configuration.MAXIMUM_ELEVATION_METRES
        );
        assertEquals(40, palettes.size());
        assertEquals("Topographic.gpl", palettes.selectedName());
        int before = palettes.snapshot().argbFor((short) 2_000);
        palettes.cycle(1);
        assertNotEquals("Topographic.gpl", palettes.selectedName());
        assertNotEquals(before, palettes.snapshot().argbFor((short) 2_000));
    }
}
