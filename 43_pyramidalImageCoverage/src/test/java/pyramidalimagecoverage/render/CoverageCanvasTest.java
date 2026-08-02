package pyramidalimagecoverage.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pyramidalimagecoverage.io.TileImageRepository;
import pyramidalimagecoverage.model.PixelSize;
import pyramidalimagecoverage.model.PyramidCatalog;
import pyramidalimagecoverage.model.RenderMode;
import pyramidalimagecoverage.model.TileAddress;
import pyramidalimagecoverage.model.TileRecord;
import pyramidalimagecoverage.model.ViewerModel;
import pyramidalimagecoverage.processing.LevelLayout;

class CoverageCanvasTest {
    @TempDir
    Path temporaryFolder;

    @Test
    void nativeImageKeepsItsPixelsAndLeavesOneBlackBorderPixelPerSide() throws IOException {
        Path tilePath = temporaryFolder.resolve("0.png");
        BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D tileGraphics = tile.createGraphics();
        tileGraphics.setColor(Color.RED);
        tileGraphics.fillRect(0, 0, 256, 256);
        tileGraphics.dispose();
        ImageIO.write(tile, "png", tilePath.toFile());

        PyramidCatalog catalog = new PyramidCatalog(temporaryFolder);
        TileAddress root = TileAddress.fromQuadKey("0");
        catalog.add(new TileRecord(root, tilePath));
        CoverageCanvas canvas = new CoverageCanvas(new ViewerModel(catalog), new TileImageRepository());
        canvas.setSize(258, 258);
        canvas.setLayoutDescription(LevelLayout.choose(0, new PixelSize(258, 258)));

        BufferedImage result = new BufferedImage(258, 258, BufferedImage.TYPE_INT_RGB);
        canvas.paint(result.createGraphics());

        assertEquals(Color.BLACK.getRGB(), result.getRGB(0, 200));
        assertEquals(Color.RED.getRGB(), result.getRGB(1, 200));
        assertEquals(Color.RED.getRGB(), result.getRGB(256, 200));
        assertEquals(Color.BLACK.getRGB(), result.getRGB(257, 200));
    }

    @Test
    void selectedTilePaintsGreenBorder() throws IOException {
        Path tilePath = temporaryFolder.resolve("0.png");
        BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D tileGraphics = tile.createGraphics();
        tileGraphics.setColor(Color.BLUE);
        tileGraphics.fillRect(0, 0, 256, 256);
        tileGraphics.dispose();
        ImageIO.write(tile, "png", tilePath.toFile());

        PyramidCatalog catalog = new PyramidCatalog(temporaryFolder);
        TileRecord rootTile = new TileRecord(TileAddress.fromQuadKey("0"), tilePath);
        catalog.add(rootTile);
        ViewerModel model = new ViewerModel(catalog);
        model.toggleSelection(rootTile);
        CoverageCanvas canvas = new CoverageCanvas(model, new TileImageRepository());
        canvas.setSize(258, 258);
        canvas.setLayoutDescription(LevelLayout.choose(0, new PixelSize(258, 258)));

        BufferedImage result = new BufferedImage(258, 258, BufferedImage.TYPE_INT_RGB);
        canvas.paint(result.createGraphics());

        assertEquals(new Color(0, 255, 0).getRGB(), result.getRGB(0, 200));
        assertEquals(Color.BLUE.getRGB(), result.getRGB(1, 200));
    }

    @Test
    void missingNativeTilesPaintTheirInteriorRedAndKeepBlackBorders() throws IOException {
        PyramidCatalog catalog = catalogWithBlueRootAndSouthWestChild();
        ViewerModel model = new ViewerModel(catalog);
        model.nextDepth();
        CoverageCanvas canvas = new CoverageCanvas(model, new TileImageRepository());
        canvas.setSize(516, 516);
        canvas.setLayoutDescription(LevelLayout.choose(1, new PixelSize(516, 516)));

        BufferedImage result = new BufferedImage(516, 516, BufferedImage.TYPE_INT_RGB);
        canvas.paint(result.createGraphics());

        assertEquals(Color.BLACK.getRGB(), result.getRGB(100, 100));
        assertEquals(Color.RED.getRGB(), result.getRGB(100, 200));
        assertEquals(Color.BLACK.getRGB(), result.getRGB(258, 200));
        assertEquals(Color.RED.getRGB(), result.getRGB(400, 300));
        assertEquals(Color.BLACK.getRGB(), result.getRGB(400, 400));
        assertEquals(Color.BLUE.getRGB(), result.getRGB(100, 400));
        assertNull(canvas.tileAddressAtCanvasPosition(100, 100));
        assertNotNull(canvas.tileAddressAtCanvasPosition(100, 200));
    }

    @Test
    void missingCoverageCellsPaintTheirPixelRed() throws IOException {
        PyramidCatalog catalog = catalogWithBlueRootAndSouthWestChild();
        ViewerModel model = new ViewerModel(catalog);
        model.nextDepth();
        CoverageCanvas canvas = new CoverageCanvas(model, new TileImageRepository());
        canvas.setSize(2, 2);
        canvas.setLayoutDescription(LevelLayout.choose(1, new PixelSize(1, 1)));

        BufferedImage result = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        canvas.paint(result.createGraphics());

        assertEquals(Color.RED.getRGB(), result.getRGB(1, 0));
        assertEquals(Color.RED.getRGB(), result.getRGB(1, 1));
        assertEquals(Color.BLUE.getRGB(), result.getRGB(0, 1));
    }

    @Test
    void selectedMissingTileGetsGreenBorderAndItsCenterCoordinatesInHud() throws IOException {
        PyramidCatalog catalog = catalogWithBlueRootAndSouthWestChild();
        ViewerModel model = new ViewerModel(catalog);
        model.nextDepth();
        model.toggleSelection(TileAddress.fromCoordinates(1, 1, 1));
        CoverageCanvas canvas = new CoverageCanvas(model, new TileImageRepository());
        canvas.setSize(516, 516);
        canvas.setLayoutDescription(LevelLayout.choose(1, new PixelSize(516, 516)));

        BufferedImage result = new BufferedImage(516, 516, BufferedImage.TYPE_INT_RGB);
        canvas.paint(result.createGraphics());

        assertEquals(new Color(0, 255, 0).getRGB(), result.getRGB(258, 200));
        assertEquals(Color.RED.getRGB(), result.getRGB(259, 200));
        assertTrue(java.util.Arrays.asList(canvas.hudLines()).contains("lat: 45.00000000"));
        assertTrue(java.util.Arrays.asList(canvas.hudLines()).contains("lon: 90.00000000"));
    }

    @Test
    void secondaryTileGetsYellowBorderAndHudShowsSignedDeltaAndDistance() throws IOException {
        PyramidCatalog catalog = catalogWithBlueRootAndSouthWestChild();
        ViewerModel model = new ViewerModel(catalog);
        model.nextDepth();
        model.toggleSelection(TileAddress.fromCoordinates(1, 0, 0));
        model.toggleSecondarySelection(TileAddress.fromCoordinates(1, 1, 1));
        CoverageCanvas canvas = new CoverageCanvas(model, new TileImageRepository());
        canvas.setSize(516, 516);
        canvas.setLayoutDescription(LevelLayout.choose(1, new PixelSize(516, 516)));

        BufferedImage result = new BufferedImage(516, 516, BufferedImage.TYPE_INT_RGB);
        canvas.paint(result.createGraphics());
        java.util.List<String> hud = java.util.Arrays.asList(canvas.hudLines());

        assertEquals(new Color(255, 255, 0).getRGB(), result.getRGB(258, 250));
        assertTrue(hud.contains("secondary lat: 45.00000000"));
        assertTrue(hud.contains("secondary lon: 90.00000000"));
        assertTrue(hud.contains("deltaLat (2-1): +90.00000000 deg"));
        assertTrue(hud.contains("deltaLon (2-1): +180.00000000 deg"));
        assertTrue(hud.contains("distance (1-2): 20015.114 km"));
    }

    @Test
    void selectedExistingTileFileHudUsesItsRelativePathAndExistsFlag() throws IOException {
        Path childPath = temporaryFolder.resolve("0").resolve("00.png");
        java.nio.file.Files.createDirectories(childPath.getParent());
        BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(tile, "png", childPath.toFile());

        PyramidCatalog catalog = new PyramidCatalog(temporaryFolder);
        TileRecord childTile = new TileRecord(TileAddress.fromQuadKey("00"), childPath);
        catalog.add(new TileRecord(TileAddress.fromQuadKey("0"), temporaryFolder.resolve("0.png")));
        catalog.add(childTile);
        ViewerModel model = new ViewerModel(catalog);
        model.toggleSelection(childTile);
        CoverageCanvas canvas = new CoverageCanvas(model, new TileImageRepository());

        assertEquals(Path.of("0", "00.png").toString(), canvas.selectedTileFileName());
        assertTrue(canvas.selectedTileFileExists());
    }

    @Test
    void selectedMissingTileFileHudUsesExpectedDataFolderPathAndMissingFlag() throws IOException {
        PyramidCatalog catalog = catalogWithBlueRootAndSouthWestChild();
        ViewerModel model = new ViewerModel(catalog);
        model.toggleSelection(TileAddress.fromCoordinates(2, 2, 2));
        CoverageCanvas canvas = new CoverageCanvas(model, new TileImageRepository());

        assertEquals(Path.of("2", "0", "020.png").toString(), canvas.selectedTileFileName());
        assertFalse(canvas.selectedTileFileExists());
    }

    @Test
    void levelTwoLeavesRowsOutsideEarthLatitudesDarkAndUnclickable() throws IOException {
        PyramidCatalog catalog = catalogWithBlueRootAndSouthWestChild();
        catalog.add(new TileRecord(TileAddress.fromQuadKey("003"), temporaryFolder.resolve("0.png")));
        ViewerModel model = new ViewerModel(catalog);
        model.nextDepth();
        model.nextDepth();
        CoverageCanvas canvas = new CoverageCanvas(model, new TileImageRepository());
        canvas.setSize(4, 4);
        canvas.setLayoutDescription(LevelLayout.choose(2, new PixelSize(1, 1)));

        BufferedImage result = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        canvas.paint(result.createGraphics());

        int background = new Color(18, 18, 20).getRGB();
        assertEquals(background, result.getRGB(3, 0));
        assertEquals(Color.RED.getRGB(), result.getRGB(3, 1));
        assertEquals(Color.RED.getRGB(), result.getRGB(3, 2));
        assertEquals(background, result.getRGB(3, 3));
        assertNull(canvas.tileAddressAtCanvasPosition(3, 0));
        assertNotNull(canvas.tileAddressAtCanvasPosition(3, 1));
        assertNotNull(canvas.tileAddressAtCanvasPosition(3, 2));
        assertNull(canvas.tileAddressAtCanvasPosition(3, 3));
    }

    @Test
    void focusedLevelMapsCanvasBackToGlobalTileCoordinatesAndUsesAncestorPixels() throws IOException {
        Path rootPath = temporaryFolder.resolve("0.png");
        BufferedImage root = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = root.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 256, 256);
        graphics.dispose();
        ImageIO.write(root, "png", rootPath.toFile());

        PyramidCatalog catalog = new PyramidCatalog(temporaryFolder);
        catalog.add(new TileRecord(TileAddress.fromQuadKey("0"), rootPath));
        catalog.add(new TileRecord(TileAddress.fromCoordinates(10, 480, 600), rootPath));
        catalog.add(new TileRecord(TileAddress.fromCoordinates(10, 483, 601), rootPath));
        ViewerModel model = new ViewerModel(catalog);
        for (int depth = 0; depth < 10; depth++) model.nextDepth();

        PixelSize viewport = new PixelSize(520, 260);
        LevelLayout layout = LevelLayout.choose(10, viewport, catalog.tileBoundsAt(10).orElseThrow());
        CoverageCanvas canvas = new CoverageCanvas(model, new TileImageRepository());
        canvas.setSize(520, 260);
        canvas.setLayoutDescription(layout);

        BufferedImage result = new BufferedImage(520, 260, BufferedImage.TYPE_INT_RGB);
        canvas.paint(result.createGraphics());

        assertEquals(RenderMode.SCALED, layout.mode());
        assertEquals(Color.BLUE.getRGB(), result.getRGB(10, 140));
        assertEquals(Color.RED.getRGB(), result.getRGB(200, 140));
        assertEquals(TileAddress.fromCoordinates(10, 480, 600), canvas.tileAddressAtCanvasPosition(10, 140));
        assertEquals(TileAddress.fromCoordinates(10, 483, 601), canvas.tileAddressAtCanvasPosition(500, 10));
    }

    private PyramidCatalog catalogWithBlueRootAndSouthWestChild() throws IOException {
        Path rootPath = temporaryFolder.resolve("0.png");
        BufferedImage root = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = root.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 256, 256);
        graphics.dispose();
        ImageIO.write(root, "png", rootPath.toFile());

        PyramidCatalog catalog = new PyramidCatalog(temporaryFolder);
        catalog.add(new TileRecord(TileAddress.fromQuadKey("0"), rootPath));
        catalog.add(new TileRecord(TileAddress.fromQuadKey("00"), rootPath));
        return catalog;
    }
}
