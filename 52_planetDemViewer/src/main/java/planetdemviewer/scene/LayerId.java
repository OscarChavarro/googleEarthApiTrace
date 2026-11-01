package planetdemviewer.scene;

import java.util.Objects;

/** Stable scene-layer identifier, independent from its source implementation. */
public record LayerId(String value) {
    public LayerId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Layer id is required");
        }
    }

    public static LayerId source(String sourceIdentity) {
        return new LayerId("source:" + Objects.requireNonNull(sourceIdentity, "sourceIdentity"));
    }
}

