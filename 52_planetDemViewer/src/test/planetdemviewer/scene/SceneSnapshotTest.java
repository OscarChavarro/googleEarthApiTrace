package planetdemviewer.scene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import java.io.File;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.PyramidalImage;
import planetdemviewer.model.QuadtreeNode;
import org.junit.jupiter.api.Test;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

class SceneSnapshotTest {
    @Test
    void capturesModelStateWithoutRetainingMutableCameraOrLayerObjects() {
        PlanetViewerModel model = new PlanetViewerModel();
        String source = new File("build/snapshot-dem").getAbsolutePath();
        model.addImage(new PyramidalImage(source, new QuadtreeNode("0", null, null)));
        model.getViewingCamera().setPosition(new Vector3Dd(1.0, 2.0, 3.0));
        model.getViewingCamera().updateVectors();

        SceneSnapshot snapshot = model.snapshot();

        model.getViewingCamera().setPosition(new Vector3Dd(7.0, 8.0, 9.0));
        model.getSelectedInstance().setOpacity(0.25);
        assertEquals(3.0, snapshot.primaryCamera().position().z());
        assertEquals(1.0, snapshot.layers().get(0).opacity());
        assertNotEquals(model.getViewingCamera().getPosition().z(), snapshot.primaryCamera().position().z());
    }
}
