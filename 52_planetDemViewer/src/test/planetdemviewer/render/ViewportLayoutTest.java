package planetdemviewer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import vsdk.toolkit.environment.camera.Camera;

class ViewportLayoutTest {
    private final Camera mainCamera = new Camera();
    private final ViewportLayout layout = new ViewportLayout(mainCamera);

    @Test
    void startsWithTheMainViewSelected() {
        assertEquals(1, layout.views().size());
        assertSame(mainCamera, layout.mainView().getCamera());
        assertSame(layout.mainView(), layout.selectedView());
        assertFalse(layout.isFullScreen());
    }

    @Test
    void addingAViewSelectsItUpToFourViews() {
        layout.addView();
        assertEquals(2, layout.views().size());
        assertSame(layout.views().get(1), layout.selectedView());
        assertEquals(2, layout.numberOf(layout.selectedView()));

        layout.addView();
        layout.addView();
        layout.addView();
        assertEquals(4, layout.views().size());
    }

    @Test
    void theLastViewCannotBeRemovedAndRemovingReturnsToTheMainView() {
        layout.removeView();
        assertEquals(1, layout.views().size());

        layout.addView();
        layout.removeView();
        assertEquals(1, layout.views().size());
        assertSame(layout.mainView(), layout.selectedView());
    }

    @Test
    void cyclingWrapsAroundTheViews() {
        layout.addView();
        layout.cycleSelectedView();
        assertSame(layout.views().get(0), layout.selectedView());
        layout.cycleSelectedView();
        assertSame(layout.views().get(1), layout.selectedView());
    }

    @Test
    void aClickSelectsTheViewUnderIt() {
        layout.addView();
        layout.selectViewAtPixel(100, 300, 800, 600);
        assertSame(layout.views().get(0), layout.selectedView());
        layout.selectViewAtPixel(700, 300, 800, 600);
        assertSame(layout.views().get(1), layout.selectedView());
    }

    @Test
    void aClickOnAnEmptyCanvasChangesNothing() {
        layout.addView();
        layout.selectViewAtPixel(100, 300, 0, 0);
        assertSame(layout.views().get(1), layout.selectedView());
    }

    @Test
    void cyclingTheStyleChangesTheLayout() {
        layout.addView();
        int style = layout.layoutStyle();
        double widthBefore = layout.views().get(0).viewportInPixels(800, 600)[2];
        layout.cycleLayoutStyle();
        assertEquals(style + 1, layout.layoutStyle());
        assertTrue(layout.views().get(0).viewportInPixels(800, 600)[2] != widthBefore);
    }

    @Test
    void callersCannotModifyTheViewList() {
        assertThrows(UnsupportedOperationException.class, () -> layout.views().clear());
    }
}
