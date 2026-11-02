package planetdemviewer.options;

import planetdemviewer.config.StorageProfile;

public final class CliArguments {
    private final String demFolder;
    private final String imageryFolder;
    private final boolean offline;
    private final boolean wires;
    private final int width;
    private final int height;
    private final String output;
    private final StorageProfile storageProfile;
    private final int mcpPort;

    public CliArguments(String demFolder, String imageryFolder, boolean offline, boolean wires, int width, int height,
                        String output, StorageProfile storageProfile, int mcpPort) {
        this.demFolder = demFolder;
        this.imageryFolder = imageryFolder;
        this.offline = offline;
        this.wires = wires;
        this.width = width;
        this.height = height;
        this.output = output;
        this.storageProfile = storageProfile;
        this.mcpPort = mcpPort;
    }

    public String getDemFolder() {
        return demFolder;
    }

    public String getImageryFolder() {
        return imageryFolder;
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

    public StorageProfile getStorageProfile() {
        return storageProfile;
    }

    /** A positive port enables the local Streamable-HTTP MCP endpoint. */
    public int getMcpPort() {
        return mcpPort;
    }
}
