package matrixmerger.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.processing.uncles.ToUncleRelationship;
import matrixmerger.processing.uncles.UncleDirections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class VisualHierarchyAmbiguityResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void selectsTheParentWhoseImageMatchesTheFourChildMosaic() throws IOException {
        Fixture fixture = fixture(false);

        VisualHierarchyAmbiguityResolver.Report report =
            new VisualHierarchyAmbiguityResolver().resolve(fixture.frames(), Set.of(fixture.child()));

        assertEquals(1, report.ambiguousMatrices());
        assertEquals(1, report.resolvedMatrices());
        assertSame(fixture.correctParent(), fixture.child().getInferredParent());
        assertEquals(0, fixture.child().getParentGridTransform().rowOffset());
        assertEquals(0, fixture.child().getParentGridTransform().colOffset());
    }

    @Test
    void leavesTheHierarchyAmbiguousWhenCandidatesTie() throws IOException {
        Fixture fixture = fixture(true);

        VisualHierarchyAmbiguityResolver.Report report =
            new VisualHierarchyAmbiguityResolver().resolve(fixture.frames(), Set.of(fixture.child()));

        assertEquals(1, report.ambiguousMatrices());
        assertEquals(0, report.resolvedMatrices());
        assertNull(fixture.child().getInferredParent());
    }

    private Fixture fixture(boolean tie) throws IOException {
        int[] colors = {0xff204060, 0xffd05030, 0xff30a060, 0xff3050d0};
        Path[] children = new Path[4];
        for (int index = 0; index < children.length; index++) {
            children[index] = solid("child-" + index + ".png", colors[index]);
        }
        Path correct = quadrantImage("correct.png", colors);
        Path wrong = tie ? quadrantImage("wrong.png", colors) : solid("wrong.png", 0xffffffff);
        FrameMatrixSet correctParent = parent(10, "10_1", correct);
        FrameMatrixSet wrongParent = parent(20, "20_1", wrong);
        FrameMatrixSet child = child(children);
        return new Fixture(List.of(correctParent, wrongParent, child), correctParent, child);
    }

    private FrameMatrixSet child(Path[] textures) {
        UncleDirections[] directions = {
            UncleDirections.WEST_NORTH, UncleDirections.EAST_NORTH,
            UncleDirections.WEST_SOUTH, UncleDirections.EAST_SOUTH
        };
        List<FrameTileMatrix.TileCoord> tiles = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            FrameTileMatrix.TileCoord tile = tile(
                "30_" + (index + 1), index / 2, index % 2, textures[index]
            );
            tile.setUncles(List.of(
                new ToUncleRelationship(directions[index], "10_1"),
                new ToUncleRelationship(directions[index], "20_1")
            ));
            tiles.add(tile);
        }
        return frame(30, tiles, 2, 2);
    }

    private FrameMatrixSet parent(int frameId, String id, Path texture) throws IOException {
        return frame(frameId, List.of(
            tile(id, 0, 0, texture),
            tile(frameId + "_2", 0, 1, solid("unused-" + frameId + ".png", 0xff101010))
        ), 1, 2);
    }

    private static FrameMatrixSet frame(
        int frameId,
        List<FrameTileMatrix.TileCoord> tiles,
        int rows,
        int cols
    ) {
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

    private static FrameTileMatrix.TileCoord tile(String id, int row, int col, Path texture) {
        FrameTileMatrix.TileCoord tile = new FrameTileMatrix.TileCoord();
        tile.setId(id);
        tile.setI(row);
        tile.setJ(col);
        tile.setTextureFile(texture.toString());
        return tile;
    }

    private Path solid(String name, int rgb) throws IOException {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(rgb));
        graphics.fillRect(0, 0, 256, 256);
        graphics.dispose();
        Path output = tempDir.resolve(name);
        ImageIO.write(image, "png", output.toFile());
        return output;
    }

    private Path quadrantImage(String name, int[] colors) throws IOException {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        for (int index = 0; index < 4; index++) {
            graphics.setColor(new Color(colors[index]));
            graphics.fillRect((index % 2) * 128, (index / 2) * 128, 128, 128);
        }
        graphics.dispose();
        Path output = tempDir.resolve(name);
        ImageIO.write(image, "png", output.toFile());
        return output;
    }

    private record Fixture(
        List<FrameMatrixSet> frames,
        FrameMatrixSet correctParent,
        FrameMatrixSet child
    ) {}
}
