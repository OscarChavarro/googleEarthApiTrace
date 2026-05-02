package pyramidalimagebuilder.processing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jgrapht.Graph;
import org.jgrapht.alg.connectivity.ConnectivityInspector;
import org.jgrapht.graph.AsUndirectedGraph;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import pyramidalimagebuilder.model.TileInstance;
import pyramidalimagebuilder.model.TileMatrix;

public final class FrameTileMatricesBuilder {
    private final HintGraphFromTileInstancesBuilder hintsGraphBuilder = new HintGraphFromTileInstancesBuilder();
    private final HintGraphToTileMatrixConvertor graphToTileMatrixConvertor = new HintGraphToTileMatrixConvertor();

    public List<TileMatrix> build(List<TileInstance> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        Map<Integer, List<TileInstance>> byFrame = groupByFrame(source);
        List<FrameComponentSelection> selectedByFrame = new ArrayList<>();

        for (Map.Entry<Integer, List<TileInstance>> frameEntry : byFrame.entrySet()) {
            Graph<TileInstance, DefaultEdge> frameHintsGraph = hintsGraphBuilder.build(frameEntry.getValue());
            Graph<TileInstance, DefaultEdge> largestConnectedComponent = largestConnectedComponent(frameHintsGraph);
            if (largestConnectedComponent == null || largestConnectedComponent.vertexSet().size() <= 1) {
                continue;
            }
            selectedByFrame.add(new FrameComponentSelection(frameEntry.getKey(), largestConnectedComponent));
        }

        selectedByFrame.sort(Comparator.comparingInt(FrameComponentSelection::frameId));
        List<FrameComponentSelection> deDuplicated = removeConsecutiveDuplicateTileSets(selectedByFrame);

        List<TileMatrix> tileMatrices = new ArrayList<>();
        for (FrameComponentSelection frameSelection : deDuplicated) {
            tileMatrices.add(graphToTileMatrixConvertor.convert(frameSelection.component()));
        }
        return tileMatrices;
    }

    private static Map<Integer, List<TileInstance>> groupByFrame(List<TileInstance> source) {
        Map<Integer, List<TileInstance>> byFrame = new HashMap<>();
        for (TileInstance tile : source) {
            if (tile == null || tile.getTileId() < 0 || tile.getFrameId() < 0) {
                continue;
            }
            byFrame.computeIfAbsent(tile.getFrameId(), ignored -> new ArrayList<>()).add(tile);
        }
        return byFrame;
    }

    private Graph<TileInstance, DefaultEdge> largestConnectedComponent(Graph<TileInstance, DefaultEdge> graph) {
        if (graph == null || graph.vertexSet().isEmpty()) {
            return null;
        }

        ConnectivityInspector<TileInstance, DefaultEdge> inspector =
            new ConnectivityInspector<>(new AsUndirectedGraph<>(graph));
        List<Set<TileInstance>> groups = inspector.connectedSets();
        if (groups.isEmpty()) {
            return null;
        }
        Set<TileInstance> largest = groups.stream()
            .max((a, b) -> {
                int bySize = Integer.compare(a.size(), b.size());
                if (bySize != 0) {
                    return bySize;
                }
                return Integer.compare(minTileId(b), minTileId(a));
            })
            .orElse(null);
        if (largest == null) {
            return null;
        }
        return buildSubgraph(graph, largest);
    }

    private static int minTileId(Set<TileInstance> group) {
        return group.stream().mapToInt(TileInstance::getTileId).min().orElse(Integer.MAX_VALUE);
    }

    private static Graph<TileInstance, DefaultEdge> buildSubgraph(
        Graph<TileInstance, DefaultEdge> graph,
        Set<TileInstance> group
    ) {
        Graph<TileInstance, DefaultEdge> subgraph = new DefaultDirectedGraph<>(DefaultEdge.class);
        for (TileInstance vertex : group) {
            subgraph.addVertex(vertex);
        }
        for (TileInstance source : group) {
            for (DefaultEdge edge : graph.outgoingEdgesOf(source)) {
                TileInstance target = graph.getEdgeTarget(edge);
                if (group.contains(target) && !subgraph.containsEdge(source, target)) {
                    subgraph.addEdge(source, target);
                }
            }
        }
        return subgraph;
    }

    private static List<FrameComponentSelection> removeConsecutiveDuplicateTileSets(
        List<FrameComponentSelection> source
    ) {
        List<FrameComponentSelection> out = new ArrayList<>();
        Set<Integer> previousTileIds = null;
        for (FrameComponentSelection selection : source) {
            Set<Integer> currentTileIds = tileIdSet(selection.component());
            if (previousTileIds != null && previousTileIds.equals(currentTileIds)) {
                continue;
            }
            out.add(selection);
            previousTileIds = currentTileIds;
        }
        return out;
    }

    private static Set<Integer> tileIdSet(Graph<TileInstance, DefaultEdge> graph) {
        Set<Integer> out = new LinkedHashSet<>();
        if (graph == null) {
            return out;
        }
        for (TileInstance tile : graph.vertexSet()) {
            if (tile != null) {
                out.add(tile.getTileId());
            }
        }
        return out;
    }

    private record FrameComponentSelection(
        int frameId,
        Graph<TileInstance, DefaultEdge> component
    ) {
    }
}
