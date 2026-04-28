package dumpanalyzer;

public class TileInstance {
    private final int contentId;
    private final Integer southNeighbor;
    private final Integer northNeighbor;
    private final Integer eastNeighbor;
    private final Integer westNeighbor;

    public TileInstance(
        int contentId,
        Integer southNeighbor,
        Integer northNeighbor,
        Integer eastNeighbor,
        Integer westNeighbor
    ) {
        this.contentId = contentId;
        this.southNeighbor = southNeighbor;
        this.northNeighbor = northNeighbor;
        this.eastNeighbor = eastNeighbor;
        this.westNeighbor = westNeighbor;
    }

    public int getContentId() {
        return contentId;
    }

    public Integer getSouthNeighbor() {
        return southNeighbor;
    }

    public Integer getNorthNeighbor() {
        return northNeighbor;
    }

    public Integer getEastNeighbor() {
        return eastNeighbor;
    }

    public Integer getWestNeighbor() {
        return westNeighbor;
    }

    @Override
    public String toString() {
        return "TileInstance{" +
            "contentId=" + contentId +
            ", southNeighbor=" + southNeighbor +
            ", northNeighbor=" + northNeighbor +
            ", eastNeighbor=" + eastNeighbor +
            ", westNeighbor=" + westNeighbor +
            '}';
    }
}
