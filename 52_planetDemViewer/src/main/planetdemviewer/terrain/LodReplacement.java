package planetdemviewer.terrain;

import java.util.Objects;

/** Immutable identity of a replacement selected by the LOD frontier. */
public record LodReplacement(String previousVariantKey, String nextVariantKey) {
    public LodReplacement {
        Objects.requireNonNull(previousVariantKey, "previousVariantKey");
        Objects.requireNonNull(nextVariantKey, "nextVariantKey");
        if (previousVariantKey.isBlank() || nextVariantKey.isBlank()) {
            throw new IllegalArgumentException("LOD variant keys are required");
        }
    }
}
