package planetdemviewer.gui.awt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import planetdemviewer.render.RasterizedText;
import planetdemviewer.render.TextLabel;
import planetdemviewer.render.TextRasterizer;
import planetdemviewer.render.TextStyle;

class AwtTextRasterizerHelperTest {
    private final TextRasterizer rasterizer = new AwtTextRasterizerHelper();

    @Test
    void anEmptyLabelHasNothingToShow() {
        assertNull(rasterizer.rasterize(TextLabel.left("", TextStyle.STATUS, 0, 0, TextLabel.WHITE), 800, 600));
    }

    @Test
    void aRightAlignedLabelEndsAtItsAnchor() {
        RasterizedText text = rasterizer.rasterize(
            TextLabel.rightAligned("Hello", TextStyle.STATUS, 400, 300, TextLabel.WHITE), 800, 600);
        assertTrue(text.image().getXSize() > 2);
        assertEquals(0.0f, text.quadVertices()[4], 1e-6f);
        assertEquals(-2.0f * text.image().getXSize() / 800, text.quadVertices()[0], 1e-6f);
    }

    @Test
    void drawsTheGlyphsInTheLabelColor() {
        RasterizedText text = rasterizer.rasterize(
            TextLabel.left("H", TextStyle.PERFORMANCE, 0, 0, TextLabel.YELLOW), 800, 600);
        var pixels = text.image().getRawImageDirectBuffer();
        boolean yellowFound = false;
        for (int k = 0; k + 3 < pixels.capacity(); k += 4) {
            yellowFound |= pixels.get(k + 3) == (byte) 255 && pixels.get(k) == (byte) 255
                && pixels.get(k + 1) == (byte) 255 && pixels.get(k + 2) == 0;
        }
        assertTrue(yellowFound);
    }
}
