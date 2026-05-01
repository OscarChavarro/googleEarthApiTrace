package pyramidalimagebuilder.processing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import pyramidalimagebuilder.model.TileInstance;

public final class HintGraphFromTileInstancesBuilder {
    private static final int MAX_NEIGHBOR_LINKS = 2000;

    public Graph<TileInstance, DefaultEdge> build(List<TileInstance> merged) {
        Graph<TileInstance, DefaultEdge> graph = new DefaultDirectedGraph<>(DefaultEdge.class);
        Map<Integer, TileInstance> nodeByTileId = new HashMap<>();
        int linksAdded = 0;
        for (TileInstance tile : merged) {
            graph.addVertex(tile);
            nodeByTileId.put(tile.getTileId(), tile);
        }
        for (TileInstance source : merged) {
            linksAdded += addEdgeIfPresent(graph, nodeByTileId, source, source.getSouthNeighbor(), "SOUTH");
            if (linksAdded >= MAX_NEIGHBOR_LINKS) return graph;
            linksAdded += addEdgeIfPresent(graph, nodeByTileId, source, source.getNorthNeighbor(), "NORTH");
            if (linksAdded >= MAX_NEIGHBOR_LINKS) return graph;
            linksAdded += addEdgeIfPresent(graph, nodeByTileId, source, source.getEastNeighbor(), "EAST");
            if (linksAdded >= MAX_NEIGHBOR_LINKS) return graph;
            linksAdded += addEdgeIfPresent(graph, nodeByTileId, source, source.getWestNeighbor(), "WEST");
            if (linksAdded >= MAX_NEIGHBOR_LINKS) return graph;
        }
        return graph;
    }

    private static int addEdgeIfPresent(
        Graph<TileInstance, DefaultEdge> graph,
        Map<Integer, TileInstance> nodeByTileId,
        TileInstance source,
        Integer neighborId,
        String direction
    ) {
        if (neighborId == null) {
            return 0;
        }
        TileInstance target = nodeByTileId.get(neighborId);
        if (target == null || graph.containsEdge(source, target)) {
            return 0;
        }
        graph.addEdge(source, target);
        return 1;
    }
}
