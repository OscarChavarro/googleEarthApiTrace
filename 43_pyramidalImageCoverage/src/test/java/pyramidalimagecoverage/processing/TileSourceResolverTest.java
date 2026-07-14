package pyramidalimagecoverage.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import pyramidalimagecoverage.model.PyramidCatalog;
import pyramidalimagecoverage.model.TileAddress;
import pyramidalimagecoverage.model.TileRecord;

class TileSourceResolverTest {
    @Test
    void rootImageProvidesTwoByTwoRegionsThroughDepthSeven() {
        PyramidCatalog catalog = catalogWith("0");
        SourceRegion region = new TileSourceResolver(catalog).resolve(7, 0, 0, 2);
        assertNotNull(region);
        assertEquals(0, region.x0());
        assertEquals(254, region.y0());
        assertEquals(2, region.x1());
        assertEquals(256, region.y1());
    }

    @Test
    void levelOneImageSuppliesDepthEightSummary() {
        PyramidCatalog catalog = catalogWith("0", "02");
        SourceRegion region = new TileSourceResolver(catalog).resolve(8, 128, 128, 2);
        assertNotNull(region);
        assertEquals("02", region.tile().address().quadKey());
        assertEquals(0, region.x0());
        assertEquals(254, region.y0());
    }

    @Test
    void rootImageProvidesSinglePixelsThroughDepthEight() {
        PyramidCatalog catalog = catalogWith("0");
        SourceRegion region = new TileSourceResolver(catalog).resolve(8, 255, 255, 1);
        assertEquals(255, region.x0());
        assertEquals(0, region.y0());
        assertEquals(256, region.x1());
        assertEquals(1, region.y1());
    }

    @Test
    void outputSizeSelectsMatchingAncestorDepth() {
        PyramidCatalog catalog = catalogWith("0", "02222222");
        SourceRegion region = new TileSourceResolver(catalog).resolve(8, 254, 254, 128);
        assertNotNull(region);
        assertEquals("02222222", region.tile().address().quadKey());
        assertEquals(0, region.x0());
        assertEquals(128, region.x1());
        assertEquals(128, region.y0());
        assertEquals(256, region.y1());
    }

    private static PyramidCatalog catalogWith(String... keys) {
        PyramidCatalog catalog = new PyramidCatalog(Path.of("/pyramid"));
        for (String key : keys) {
            TileAddress address = TileAddress.fromQuadKey(key);
            catalog.add(new TileRecord(address, Path.of(key + ".png")));
        }
        return catalog;
    }
}
