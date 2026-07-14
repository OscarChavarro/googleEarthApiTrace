package planetviewer.options;

import java.util.List;

public final class CliArguments {
    private final List<String> pyramidalImageFolders;
    private final boolean offline;
    private final boolean wires;
    private final int width;
    private final int height;
    private final String output;

    public CliArguments(List<String> pyramidalImageFolders, boolean offline, boolean wires, int width, int height, String output) {
        this.pyramidalImageFolders = pyramidalImageFolders;
        this.offline = offline;
        this.wires = wires;
        this.width = width;
        this.height = height;
        this.output = output;
    }

    public List<String> getPyramidalImageFolders() {
        return pyramidalImageFolders;
    }

    public boolean isOffline() {
        return offline;
    }

    public boolean isWires() {
        return wires;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public String getOutput() {
        return output;
    }
}
