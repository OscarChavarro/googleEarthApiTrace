package planetdemviewer.io.imagery;

/**
 * Outcome of deleting an imagery subtree.
 *
 * @param keptFiles non-PNG files left in place, which also keep their directories
 * @param failures  entries that could not be read or deleted
 */
public record ImageryDeletionReport(int deletedPngs, int removedDirectories, int keptFiles, int failures) {
    public static final ImageryDeletionReport NOTHING = new ImageryDeletionReport(0, 0, 0, 0);

    public String summary() {
        String text = deletedPngs + " PNG, " + removedDirectories + " folders";
        if (keptFiles > 0) {
            text += ", kept " + keptFiles + " non-PNG files";
        }
        if (failures > 0) {
            text += ", " + failures + " FAILED";
        }
        return text;
    }
}
