package frametexturenormalizer.util;

import java.nio.file.Path;

public final class ScopedTileIds {
    private ScopedTileIds() {
    }

    public static String format(int frameId, int tileId) {
        if (frameId < 0 || tileId < 0) {
            return null;
        }
        return String.format("%05d_%d", frameId, tileId);
    }

    public static String formatFromTextureFile(String textureFile, int fallbackFrameId, int tileId) {
        if (tileId < 0) {
            return null;
        }
        Integer frameId = canonicalFrameIdFromTextureFile(textureFile);
        return format(frameId == null ? fallbackFrameId : frameId, tileId);
    }

    public static Integer canonicalFrameIdFromTextureFile(String textureFile) {
        if (textureFile == null || textureFile.isBlank()) {
            return null;
        }
        try {
            Path texturePath = Path.of(textureFile);
            Path parent = texturePath.getParent();
            if (parent == null || parent.getFileName() == null) {
                return null;
            }
            String raw = parent.getFileName().toString();
            if (!raw.matches("\\d+")) {
                return null;
            }
            return Integer.parseInt(raw);
        }
        catch (RuntimeException ignored) {
            return null;
        }
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isBlank()) {
            return null;
        }
        int separator = value.indexOf('_');
        if (separator <= 0 || separator >= value.length() - 1) {
            return value;
        }
        try {
            int frameId = Integer.parseInt(value.substring(0, separator));
            int tileId = Integer.parseInt(value.substring(separator + 1));
            return format(frameId, tileId);
        }
        catch (NumberFormatException ex) {
            return value;
        }
    }
}
