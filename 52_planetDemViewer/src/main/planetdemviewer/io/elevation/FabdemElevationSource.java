package planetdemviewer.io.elevation;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import planetdemviewer.io.TileImageLoader;
import planetdemviewer.io.TileRequest;
import planetdemviewer.io.TileSource;
import planetdemviewer.model.DemTile;
import planetdemviewer.spatial.TileAddress;

/** FABDEM adapter exposing normalized {@link DemTile} payloads through TileSource. */
public final class FabdemElevationSource implements TileSource<DemTile> {
    private final FabdemTileResolver resolver;
    private final TileImageLoader loader;

    public FabdemElevationSource(Path rootDirectory, TileImageLoader loader) {
        this.resolver = new FabdemTileResolver(rootDirectory);
        if (loader == null) {
            throw new IllegalArgumentException("Tile loader is required");
        }
        this.loader = loader;
    }

    @Override
    public boolean has(TileAddress address) {
        return resolver.resolve(address).isPresent();
    }

    @Override
    public CompletionStage<DemTile> request(TileRequest request) {
        if (request == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Tile request is required"));
        }
        return resolver.resolve(request.address())
            .<CompletionStage<DemTile>>map(path -> loader.requestElevationAsync(path.toFile()))
            .orElseGet(() -> CompletableFuture.failedFuture(
                new IllegalArgumentException("FABDEM tile is unavailable: " + request.address())));
    }
}
