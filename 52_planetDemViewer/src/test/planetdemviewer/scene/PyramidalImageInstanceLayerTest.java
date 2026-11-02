package planetdemviewer.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.File;
import planetdemviewer.model.PyramidalImage;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.model.QuadtreeNode;
import org.junit.jupiter.api.Test;

class PyramidalImageInstanceLayerTest {
    @Test
    void currentDemPlacementExposesTheTerrainLayerContract() {
        String source = new File("build/test-dem-source").getAbsolutePath();
        PyramidalImage image = new PyramidalImage(source, new QuadtreeNode("0", null, null));
        ElevationLayer layer = new PyramidalImageInstance(image);

        assertTrue(layer.isVisible());
        assertTrue(layer.descriptor().elevation());
        assertEquals("FABDEM DEM", layer.descriptor().sourceType());
        assertEquals("planetdem-equirectangular-v1", layer.descriptor().spatialReferenceId());
        assertEquals("EGM2008-orthometric", layer.sourceVerticalDatumId());
        assertEquals("WGS84-ellipsoidal", layer.canonicalVerticalDatumId());
        assertEquals("fabdem-quadkey-v1", layer.tileScheme().id());
        assertTrue(layer.layerId().value().contains("test-dem-source"));
    }
}

