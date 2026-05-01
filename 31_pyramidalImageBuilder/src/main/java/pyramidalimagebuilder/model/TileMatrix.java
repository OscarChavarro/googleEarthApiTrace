package pyramidalimagebuilder.model;

import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;

public final class TileMatrix {
    private final Graph<TileInstance, DefaultEdge> graph;
    private final TileInstance[][] M;

    public TileMatrix(Graph<TileInstance, DefaultEdge> graph, TileInstance[][] M) {
        this.graph = graph;
        this.M = M;
    }

    public Graph<TileInstance, DefaultEdge> getGraph() {
        return graph;
    }

    public TileInstance[][] getM() {
        return M;
    }
}
