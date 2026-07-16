package matrixmerger.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.model.state.MatrixMergerState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class VisualHierarchyRelationshipInferrerTest {
    @TempDir
    Path tempDir;

    @Test
    void connectsADisconnectedChildGridFromConfidentQuadrantMatches() throws Exception {
        BufferedImage parentImage = patternedImage();
        Path parentFile = tempDir.resolve("parent.png");
        ImageIO.write(parentImage, "png", parentFile.toFile());
        FrameTileMatrix.TileCoord parentTile = tile("00010_1", 0, 0, parentFile);
        FrameTileMatrix.TileCoord siblingParentTile = tile("00010_2", 0, 1, parentFile);
        FrameMatrixSet parent = frame(10, 1, 2, List.of(parentTile, siblingParentTile));

        List<FrameTileMatrix.TileCoord> children = new ArrayList<>();
        for (int quadrant = 0; quadrant < 4; quadrant++) {
            Path childFile = tempDir.resolve("child-" + quadrant + ".png");
            ImageIO.write(expandQuadrant(parentImage, quadrant), "png", childFile.toFile());
            boolean south = quadrant == 0 || quadrant == 1;
            boolean east = quadrant == 1 || quadrant == 2;
            children.add(tile("00020_" + quadrant, south ? 1 : 0, east ? 1 : 0, childFile));
        }
        FrameMatrixSet child = frame(20, 2, 2, children);
        MatrixMergerState state = new MatrixMergerState();
        state.setFrameMatrices(List.of(parent, child));

        int inferred = new VisualHierarchyRelationshipInferrer().inferMissingParents(state);

        assertEquals(1, inferred);
        assertEquals(List.of(0), state.getHierarchyOrderDiagnostics().get(1).resolvedParentIndexes());
        assertEquals(
            new matrixmerger.model.contract.ParentGridTransform(0, 0),
            state.getFrameMatrices().get(1).getParentGridTransform()
        );
        for (FrameTileMatrix.TileCoord tile : state.getFrameMatrices().get(1).getMatrices().get(0).getTiles()) {
            assertTrue(tile.getUncles().isEmpty(), "a containing parent must not be serialized as an adjacent uncle");
        }
    }

    private static BufferedImage patternedImage() {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        int[] bases = {0x102030, 0x903020, 0x209050, 0x302090};
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                boolean south = y >= 128;
                boolean east = x >= 128;
                int quadrant = south ? (east ? 1 : 0) : (east ? 2 : 3);
                int base = bases[quadrant];
                int localX = x & 127;
                int localY = y & 127;
                int red = Math.min(255, ((base >> 16) & 255) + localX / 8);
                int green = Math.min(255, ((base >> 8) & 255) + localY / 8);
                int blue = Math.min(255, (base & 255) + (localX + localY) / 16);
                image.setRGB(x, y, red << 16 | green << 8 | blue);
            }
        }
        return image;
    }

    private static BufferedImage expandQuadrant(BufferedImage parent, int quadrant) {
        BufferedImage child = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        int x0 = quadrant == 1 || quadrant == 2 ? 128 : 0;
        int y0 = quadrant == 0 || quadrant == 1 ? 128 : 0;
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                child.setRGB(x, y, parent.getRGB(x0 + x / 2, y0 + y / 2));
            }
        }
        return child;
    }

    private static FrameTileMatrix.TileCoord tile(String id, int i, int j, Path texture) {
        FrameTileMatrix.TileCoord tile = new FrameTileMatrix.TileCoord();
        tile.setId(id);
        tile.setI(i);
        tile.setJ(j);
        tile.setTextureFile(texture.toString());
        return tile;
    }

    private static FrameMatrixSet frame(int frameId, int rows, int cols, List<FrameTileMatrix.TileCoord> tiles) {
        FrameTileMatrix matrix = new FrameTileMatrix();
        matrix.setFrameId(frameId);
        matrix.setRows(rows);
        matrix.setCols(cols);
        matrix.setTiles(tiles);
        FrameMatrixSet frame = new FrameMatrixSet();
        frame.setFrameId(frameId);
        frame.setMatrices(List.of(matrix));
        return frame;
    }
}
