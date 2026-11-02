package planetdemviewer.gui.awt;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import planetdemviewer.render.RasterizedText;
import planetdemviewer.render.TextLabel;
import planetdemviewer.render.TextRasterizer;
import planetdemviewer.render.TextStyle;
import vsdk.toolkit.common.color.ColorRgb;
import vsdk.toolkit.media.RGBAImageUncompressed;

/** Rasterizes text with Java2D, since Vitral has no font rasterizer that works without AWT. */
public final class AwtTextRasterizerHelper implements TextRasterizer {
    @Override
    public RasterizedText rasterize(TextLabel label, int canvasWidth, int canvasHeight) {
        if (label.text() == null || label.text().isEmpty()) {
            return null;
        }
        Font font = fontOf(label.style());
        BufferedImage measure = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D measureGraphics = measure.createGraphics();
        measureGraphics.setFont(font);
        FontMetrics metrics = measureGraphics.getFontMetrics();
        int width = Math.max(1, metrics.stringWidth(label.text()) + 2);
        int height = Math.max(1, metrics.getHeight() + 2);
        int descent = metrics.getDescent();
        measureGraphics.dispose();

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setFont(font);
        graphics.setColor(colorOf(label.color()));
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawString(label.text(), 1, metrics.getAscent());
        graphics.dispose();
        return RasterizedText.of(toVitralImage(image), label, descent, canvasWidth, canvasHeight);
    }

    private static Font fontOf(TextStyle style) {
        return new Font(style.fontName(), style.isBold() ? Font.BOLD : Font.PLAIN, style.size());
    }

    private static Color colorOf(ColorRgb color) {
        return new Color((int) Math.round(color.r() * 255.0), (int) Math.round(color.g() * 255.0),
            (int) Math.round(color.b() * 255.0));
    }

    /** The bottom row goes first, as Vitral images keep it in memory. */
    private static RGBAImageUncompressed toVitralImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] pixels = new byte[width * height * 4];
        int k = 0;
        for (int row = height - 1; row >= 0; row--) {
            for (int col = 0; col < width; col++) {
                int argb = image.getRGB(col, row);
                pixels[k++] = (byte) (argb >>> 16);
                pixels[k++] = (byte) (argb >>> 8);
                pixels[k++] = (byte) argb;
                pixels[k++] = (byte) (argb >>> 24);
            }
        }
        RGBAImageUncompressed result = new RGBAImageUncompressed();
        result.setRawImage(width, height, pixels);
        return result;
    }
}
