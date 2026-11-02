package planetdemviewer.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import planetdemviewer.model.QuadtreeNode;

class Jogl4TerrainRendererTest {
    @Test
    void mapsEveryTileCornerToItsCompletePngIncludingTopOriginV() {
        QuadtreeNode root = new QuadtreeNode("0", null, Path.of("root.bin").toFile());
        QuadtreeNode northWest = new QuadtreeNode("03", root, Path.of("03.bin").toFile());
        // North-west node bounds: x=[0, .5], y=[.5, 1] in pyramid space.
        double[] positions = new double[256 * 256 * 3];
        for (int row = 0; row < 256; row++) {
            for (int column = 0; column < 256; column++) {
                int offset = (row * 256 + column) * 3;
                positions[offset] = -0.5 + column / 255.0 * 0.5;
                positions[offset + 1] = 0.5 - row / 255.0 * 0.5;
            }
        }

        float[] uvs = Jogl4TerrainRenderer.textureCoordinatesFor(positions, northWest);

        assertArrayEquals(new float[] {0f, 0f}, new float[] {uvs[0], uvs[1]}, 0.00001f);
        assertArrayEquals(new float[] {1f, 0f}, new float[] {uvs[255 * 2], uvs[255 * 2 + 1]}, 0.00001f);
        int southWest = 255 * 256 * 2;
        assertArrayEquals(new float[] {0f, 1f}, new float[] {uvs[southWest], uvs[southWest + 1]}, 0.00001f);
        assertArrayEquals(new float[] {1f, 1f}, new float[] {uvs[southWest + 255 * 2], uvs[southWest + 255 * 2 + 1]}, 0.00001f);
    }

    @Test
    void mapsTheDiamondSquareGridCornersToThePngCorners() {
        QuadtreeNode root = new QuadtreeNode("0", null, Path.of("root.bin").toFile());
        float[] uvs = Jogl4TerrainRenderer.textureCoordinatesFor(new double[257 * 257 * 3], root);
        assertArrayEquals(new float[] {1f, 0f}, new float[] {uvs[256 * 2], uvs[256 * 2 + 1]}, 0.00001f);
        int southEast = (256 * 257 + 256) * 2;
        assertArrayEquals(new float[] {1f, 1f}, new float[] {uvs[southEast], uvs[southEast + 1]}, 0.00001f);
        assertArrayEquals(new float[] {1f / 256f, 0f}, new float[] {uvs[2], uvs[3]}, 0.00001f);
    }
}
