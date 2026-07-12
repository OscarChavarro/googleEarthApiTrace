package pyramidalimageexporter.common.statistics;

/**
 * Accumulates outcome counters for an additive-destructive pyramidal image
 * export: each tile written to an existing output directory is either new,
 * ignored because an identical image was already present, or used to
 * overwrite a pre-existing but different image.
 */
public final class PyramidalImageAdditionStatistics {
    private int newImages = 0;
    private int ignoredExistingImages = 0;
    private int updatedImages = 0;

    public void incrementNewImages() {
        newImages++;
    }

    public void incrementIgnoredExistingImages() {
        ignoredExistingImages++;
    }

    public void incrementUpdatedImages() {
        updatedImages++;
    }

    public int getNewImages() {
        return newImages;
    }

    public int getIgnoredExistingImages() {
        return ignoredExistingImages;
    }

    public int getUpdatedImages() {
        return updatedImages;
    }

    @Override
    public String toString() {
        return "PyramidalImageAdditionStatistics{"
            + "new=" + newImages
            + ", ignoredExisting=" + ignoredExistingImages
            + ", updated=" + updatedImages
            + '}';
    }
}
