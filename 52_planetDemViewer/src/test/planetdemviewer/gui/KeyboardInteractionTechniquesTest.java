package planetdemviewer.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import org.junit.jupiter.api.Test;
import planetdemviewer.model.PlanetViewerModel;
import vsdk.toolkit.environment.material.RendererConfiguration;

class KeyboardInteractionTechniquesTest {
    private final RendererConfiguration configuration = new RendererConfiguration();
    private final Runnable nothing = () -> { };
    private final KeyboardInteractionTechniques keyboard = new KeyboardInteractionTechniques(
        new PlanetViewerModel(), nothing, null, nothing, nothing,
        new ViewActions(nothing, nothing, nothing, nothing, nothing, () -> configuration),
        step -> { }, nothing, nothing, nothing, nothing, nothing, nothing, nothing);

    private void press(int keyCode) {
        keyboard.keyPressed(new KeyEvent(new Canvas(), KeyEvent.KEY_PRESSED, 0L, 0, keyCode, KeyEvent.CHAR_UNDEFINED));
    }

    @Test
    void f4ChangesTheBoundingVolumesOfTheSelectedView() {
        assertFalse(configuration.isBoundingVolumeSet());
        press(KeyEvent.VK_F4);
        assertTrue(configuration.isBoundingVolumeSet());
        press(KeyEvent.VK_F4);
        assertFalse(configuration.isBoundingVolumeSet());
    }

    @Test
    void f7GoesThroughEveryShadingModel() {
        configuration.setShadingType(RendererConfiguration.SHADING_TYPE_PHONG);
        int[] expected = {RendererConfiguration.SHADING_TYPE_COOK_TERRANCE, RendererConfiguration.SHADING_TYPE_NOLIGHT,
            RendererConfiguration.SHADING_TYPE_FLAT, RendererConfiguration.SHADING_TYPE_GOURAUD,
            RendererConfiguration.SHADING_TYPE_PHONG};
        for (int shadingType : expected) {
            press(KeyEvent.VK_F7);
            assertEquals(shadingType, configuration.getShadingType());
        }
    }
}
