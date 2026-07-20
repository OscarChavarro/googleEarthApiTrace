package planetviewer.options;

import java.util.List;

public final class CliArguments {
    private final List<String> pyramidalImageFolders;
    private final boolean offline;
    private final boolean dryRun;

    public CliArguments(List<String> pyramidalImageFolders, boolean offline, boolean dryRun) {
        this.pyramidalImageFolders = List.copyOf(pyramidalImageFolders);
        this.offline = offline;
        this.dryRun = dryRun;
    }

    public List<String> getPyramidalImageFolders() {
        return pyramidalImageFolders;
    }

    public boolean isOffline() {
        return offline;
    }

    public boolean isDryRun() {
        return dryRun;
    }
}
