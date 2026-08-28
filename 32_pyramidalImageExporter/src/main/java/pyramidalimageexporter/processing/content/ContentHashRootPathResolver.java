package pyramidalimageexporter.processing.content;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Anchors a tile to its absolute quadtree path by content instead of by id:
 * the planet's imagery does not change, so a texture file that is a
 * byte-for-byte duplicate of an already-catalogued top-level image names the
 * same real-world cell, regardless of which capture session or frame/tile
 * numbering produced it. Catalogued images may come from this session or
 * from an explicitly supplied read-only reference pyramid.
 */
public final class ContentHashRootPathResolver {
    private final ContentHashCatalog catalog = new ContentHashCatalog();

    public void indexCataloguedImages(Map<String, String> quadPathByImagePath) {
        catalog.indexCataloguedImages(quadPathByImagePath, Map.of());
    }

    public Optional<String> resolveQuadPath(String textureFile) {
        return catalog.resolveQuadPath(textureFile);
    }
}
