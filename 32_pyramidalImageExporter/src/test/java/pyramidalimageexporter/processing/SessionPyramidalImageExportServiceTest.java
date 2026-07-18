package pyramidalimageexporter.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.model.state.PyramidalImageExporterState;
import pyramidalimageexporter.processing.uncles.TileRootPathResolver;
import pyramidalimageexporter.processing.uncles.ToUncleRelationship;
import pyramidalimageexporter.processing.uncles.UncleDirections;

final class SessionPyramidalImageExportServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void keepsDirectlyReadTileWhenAnUncleDerivedTileClaimsTheSameFullPath() throws Exception {
        MatrixLayerTile direct = tile("0211212", 0, 0, nativeImage("direct.png", 256, 256));
        MatrixLayerTile derived = tile("derived", 0, 0, nativeImage("derived.png", 256, 256));
        derived.setUncles(List.of(new ToUncleRelationship(UncleDirections.SOUTH_EAST, "uncle")));

        MatrixLayer layer = new MatrixLayer();
        layer.setSourceFolderName("matrix_1");
        layer.setTiles(List.of(direct, derived));

        PyramidalImageExporterState model = new PyramidalImageExporterState();
        model.setMatrixLayers(List.of(layer));

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            model.getMatrixLayers(),
            Map.of("uncle", "021122"),
            Map.of()
        );

        Method buildManifest = SessionPyramidalImageExportService.class.getDeclaredMethod(
            "buildExportManifest",
            PyramidalImageExporterState.class,
            TileRootPathResolver.Resolution.class
        );
        buildManifest.setAccessible(true);
        Object manifest = buildManifest.invoke(null, model, resolution);
        assertNotNull(manifest);

        Method entriesAccessor = manifest.getClass().getDeclaredMethod("entries");
        entriesAccessor.setAccessible(true);
        List<?> entries = (List<?>) entriesAccessor.invoke(manifest);
        assertEquals(1, entries.size());

        Object keptEntry = entries.get(0);
        Method tileAccessor = keptEntry.getClass().getDeclaredMethod("tile");
        tileAccessor.setAccessible(true);
        MatrixLayerTile keptTile = (MatrixLayerTile) tileAccessor.invoke(keptEntry);
        assertEquals("0211212", keptTile.getId());
    }

    @Test
    void collapsesDifferentCaptureIdsWhenTheyClaimTheSamePathWithIdenticalContent() throws Exception {
        String sharedContent = nativeImage("shared-content.png", 256, 256);
        MatrixLayerTile first = tile("capture-a", 0, 0, sharedContent);
        MatrixLayerTile second = tile("capture-b", 0, 0, sharedContent);

        MatrixLayer firstLayer = new MatrixLayer();
        firstLayer.setSourceFolderName("matrix_1");
        firstLayer.setTiles(List.of(first));
        MatrixLayer secondLayer = new MatrixLayer();
        secondLayer.setSourceFolderName("matrix_2");
        secondLayer.setTiles(List.of(second));

        PyramidalImageExporterState model = new PyramidalImageExporterState();
        model.setMatrixLayers(List.of(firstLayer, secondLayer));
        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            model.getMatrixLayers(),
            Map.of("capture-a", "021", "capture-b", "021"),
            Map.of()
        );

        assertEquals(1, manifestEntries(model, resolution).size());
    }

    @Test
    void rejectsImagesThatAreNotNatively256Square() throws Exception {
        MatrixLayerTile incomplete = tile("0", 0, 0, nativeImage("incomplete.png", 128, 256));
        MatrixLayer layer = new MatrixLayer();
        layer.setSourceFolderName("topLevel_matrix_00");
        layer.setTiles(List.of(incomplete));
        PyramidalImageExporterState model = new PyramidalImageExporterState();
        model.setMatrixLayers(List.of(layer));

        List<?> entries = manifestEntries(model, new TileRootPathResolver().resolve(model.getMatrixLayers(), Map.of(), Map.of()));

        assertEquals(0, entries.size());
    }

    @Test
    void rejectsAncestorSubRectanglesInsteadOfUpscalingThem() throws Exception {
        MatrixLayerTile derived = tile("00", 0, 0, nativeImage("ancestor.png", 256, 256));
        derived.setTextureSubRect(0.0, 0.0, 0.5, 0.5);
        MatrixLayer layer = new MatrixLayer();
        layer.setSourceFolderName("topLevel_matrix_01");
        layer.setTiles(List.of(derived));
        PyramidalImageExporterState model = new PyramidalImageExporterState();
        model.setMatrixLayers(List.of(layer));

        List<?> entries = manifestEntries(model, new TileRootPathResolver().resolve(model.getMatrixLayers(), Map.of(), Map.of()));

        assertEquals(0, entries.size());
    }

    @Test
    void exportsNativeTopCatalogWithoutDependingOnVisualizationLayers() throws Exception {
        String rootImage = nativeImage("native-root.png", 256, 256);
        PyramidalImageExporterState model = new PyramidalImageExporterState();
        model.setMatrixLayers(List.of());
        model.setCataloguedQuadPathsByImagePath(Map.of(rootImage, "0"));

        List<?> entries = manifestEntries(
            model,
            new TileRootPathResolver().resolve(model.getMatrixLayers(), Map.of(), Map.of())
        );

        assertEquals(1, entries.size());
    }

    @Test
    void writesNativeTopCatalogImageAtItsQuadkeyWithoutResampling() throws Exception {
        String imagePath = nativeImage("native-level-one.png", 256, 256);
        PyramidalImageExporterState model = new PyramidalImageExporterState();
        model.setCataloguedQuadPathsByImagePath(Map.of(imagePath, "03"));
        Path exportRoot = tempDir.resolve("pyramid");
        model.setSessionPyramidalImageExportPath(exportRoot.toString());

        new SessionPyramidalImageExportService().export(model);

        Path written = exportRoot.resolve("3/03.png");
        assertTrue(written.toFile().isFile());
        BufferedImage image = ImageIO.read(written.toFile());
        assertEquals(256, image.getWidth());
        assertEquals(256, image.getHeight());
        assertEquals(-1L, java.nio.file.Files.mismatch(Path.of(imagePath), written));
    }

    @Test
    void usesVisualizationOnlyTileForUnclePlacementWithoutExportingIt() throws Exception {
        MatrixLayerTile visualizationSeed = tile("03", 0, 0, nativeImage("visual-seed.png", 256, 256));
        visualizationSeed.setTextureSubRect(0.0, 0.0, 0.5, 0.5);
        MatrixLayerTile nativeChild = tile("native-child", 0, 1, nativeImage("native-child.png", 256, 256));
        nativeChild.setUncles(List.of(new ToUncleRelationship(UncleDirections.WEST_NORTH, "03")));
        MatrixLayer layer = new MatrixLayer();
        layer.setSourceFolderName("mixed_visualization_and_native");
        layer.setTiles(List.of(visualizationSeed, nativeChild));
        PyramidalImageExporterState model = new PyramidalImageExporterState();
        model.setMatrixLayers(List.of(layer));
        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(model.getMatrixLayers());

        List<?> entries = manifestEntries(model, resolution);

        assertEquals(1, entries.size());
        assertTrue(resolution.pathById().containsKey("native-child"));
    }

    private static List<?> manifestEntries(
        PyramidalImageExporterState model,
        TileRootPathResolver.Resolution resolution
    ) throws Exception {
        Method buildManifest = SessionPyramidalImageExportService.class.getDeclaredMethod(
            "buildExportManifest",
            PyramidalImageExporterState.class,
            TileRootPathResolver.Resolution.class
        );
        buildManifest.setAccessible(true);
        Object manifest = buildManifest.invoke(null, model, resolution);
        Method entriesAccessor = manifest.getClass().getDeclaredMethod("entries");
        entriesAccessor.setAccessible(true);
        return (List<?>) entriesAccessor.invoke(manifest);
    }

    private String nativeImage(String name, int width, int height) throws Exception {
        Path path = tempDir.resolve(name);
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", path.toFile());
        return path.toString();
    }

    private static MatrixLayerTile tile(String id, int i, int j, String textureFile) {
        MatrixLayerTile tile = new MatrixLayerTile();
        tile.setId(id);
        tile.setI(i);
        tile.setJ(j);
        tile.setTextureFile(textureFile);
        return tile;
    }
}
