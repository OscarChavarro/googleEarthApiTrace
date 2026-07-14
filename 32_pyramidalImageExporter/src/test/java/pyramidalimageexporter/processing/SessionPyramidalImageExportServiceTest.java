package pyramidalimageexporter.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.model.state.PyramidalImageExporterState;
import pyramidalimageexporter.processing.uncles.TileRootPathResolver;
import pyramidalimageexporter.processing.uncles.ToUncleRelationship;
import pyramidalimageexporter.processing.uncles.UncleDirections;

final class SessionPyramidalImageExportServiceTest {
    @Test
    void keepsDirectlyReadTileWhenAnUncleDerivedTileClaimsTheSameFullPath() throws Exception {
        MatrixLayerTile direct = tile("0211212", 0, 0);
        MatrixLayerTile derived = tile("derived", 0, 1);
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

    private static MatrixLayerTile tile(String id, int i, int j) {
        MatrixLayerTile tile = new MatrixLayerTile();
        tile.setId(id);
        tile.setI(i);
        tile.setJ(j);
        tile.setTextureFile("/tmp/" + id + ".png");
        return tile;
    }
}
