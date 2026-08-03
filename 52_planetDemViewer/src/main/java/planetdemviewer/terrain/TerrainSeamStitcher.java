package planetdemviewer.terrain;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import planetdemviewer.io.TileImageLoader;
import planetdemviewer.model.DemTile;
import planetdemviewer.model.QuadtreeNode;
import planetdemviewer.processing.DrawCommand;
import vsdk.toolkit.environment.geometry.surface.TriangleMesh;

/**
 * Post-culling LOD seam analysis and parallel mesh preparation.  Relationship
 * plans are cached independently from generated CPU/GPU meshes, since camera
 * movement commonly revisits the same quadtree frontier.
 */
public final class TerrainSeamStitcher {
    private static final int RELATION_CACHE_ENTRIES = 128;
    private static final int PAIR_CACHE_ENTRIES = 65_536;
    private static final double EPSILON = 1e-12;

    private final BasicTerrainMeshGenerator meshGenerator = new BasicTerrainMeshGenerator();
    private final ExecutorService workers;
    private final Map<String, List<TerrainTilePlan>> relationCache =
        new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<TerrainTilePlan>> eldest) {
                return size() > RELATION_CACHE_ENTRIES;
            }
        };
    private final Map<String, CompletableFuture<TriangleMesh>> pendingMeshes = new LinkedHashMap<>();
    private final Map<String, Optional<TerrainEdge>> pairCache =
        new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Optional<TerrainEdge>> eldest) {
                return size() > PAIR_CACHE_ENTRIES;
            }
        };
    private long pairCacheHits;

    public TerrainSeamStitcher() {
        int available = Runtime.getRuntime().availableProcessors();
        int workerCount = Math.max(1, Math.min(8, available - 1));
        workers = Executors.newFixedThreadPool(workerCount, runnable -> {
            Thread thread = new Thread(runnable, "planet-dem-seam-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public List<TerrainTilePlan> plan(List<DrawCommand> commands) {
        List<DrawCommand> drawable = commands.stream()
            .filter(command -> command.node().getTileFile() != null)
            .toList();
        String topologyKey = topologyKey(drawable);
        synchronized (relationCache) {
            List<TerrainTilePlan> cached = relationCache.get(topologyKey);
            if (cached != null) {
                return cached;
            }
        }

        List<TerrainTilePlan> result = CompletableFuture
            .supplyAsync(() -> buildPlan(drawable), workers)
            .join();
        synchronized (relationCache) {
            relationCache.put(topologyKey, result);
        }
        return result;
    }

    private List<TerrainTilePlan> buildPlan(List<DrawCommand> drawable) {
        Map<QuadtreeNode, List<TerrainTilePlan.Neighbour>> neighbours = new LinkedHashMap<>();
        for (DrawCommand command : drawable) {
            neighbours.put(command.node(), new ArrayList<>());
        }
        for (int i = 0; i < drawable.size(); i++) {
            QuadtreeNode a = drawable.get(i).node();
            for (int j = i + 1; j < drawable.size(); j++) {
                QuadtreeNode b = drawable.get(j).node();
                if (a.getDepth() == b.getDepth()) {
                    continue;
                }
                QuadtreeNode fine = a.getDepth() > b.getDepth() ? a : b;
                QuadtreeNode coarse = fine == a ? b : a;
                TerrainEdge fineEdge = cachedTouchingEdge(fine, coarse);
                if (fineEdge != null) {
                    neighbours.get(fine).add(new TerrainTilePlan.Neighbour(fineEdge, coarse));
                }
            }
        }

        List<TerrainTilePlan> result = new ArrayList<>(drawable.size());
        for (DrawCommand command : drawable) {
            List<TerrainTilePlan.Neighbour> constraints = neighbours.get(command.node());
            constraints.sort(Comparator
                .comparing((TerrainTilePlan.Neighbour n) -> n.fineEdge().ordinal())
                .thenComparing(n -> n.coarseNode().getId()));
            result.add(new TerrainTilePlan(command, constraints, variantKey(command.node(), constraints)));
        }
        result = List.copyOf(result);
        return result;
    }

    private TerrainEdge cachedTouchingEdge(QuadtreeNode fine, QuadtreeNode coarse) {
        String key = fine.getTileFile().getAbsolutePath() + '#' + fine.getId()
            + '>' + coarse.getTileFile().getAbsolutePath() + '#' + coarse.getId();
        synchronized (pairCache) {
            Optional<TerrainEdge> cached = pairCache.get(key);
            if (cached != null) {
                pairCacheHits++;
                return cached.orElse(null);
            }
        }
        TerrainEdge result = touchingEdge(fine, coarse);
        synchronized (pairCache) {
            pairCache.put(key, Optional.ofNullable(result));
        }
        return result;
    }

    /** Returns null while any required DEM is still being loaded. */
    public CompletableFuture<TriangleMesh> prepare(TerrainTilePlan plan, TileImageLoader loader) {
        File fineFile = plan.command().node().getTileFile();
        DemTile fineTile = loader.peekElevation(fineFile);
        boolean missingElevation = fineTile == null;
        if (missingElevation) {
            loader.requestElevation(fineFile);
        }
        List<TerrainSeamConstraint> constraints = new ArrayList<>();
        for (TerrainTilePlan.Neighbour neighbour : plan.coarseNeighbours()) {
            File coarseFile = neighbour.coarseNode().getTileFile();
            DemTile coarseTile = loader.peekElevation(coarseFile);
            if (coarseTile == null) {
                loader.requestElevation(coarseFile);
                missingElevation = true;
                continue;
            }
            constraints.add(new TerrainSeamConstraint(
                neighbour.fineEdge(), neighbour.coarseNode(), coarseTile));
        }
        if (missingElevation) {
            return null;
        }

        synchronized (pendingMeshes) {
            return pendingMeshes.computeIfAbsent(plan.variantKey(), ignored ->
                CompletableFuture.supplyAsync(
                    () -> meshGenerator.generate(plan.command().node(), fineTile, constraints), workers));
        }
    }

    /** CPU mesh may be discarded after upload; the relation plan remains cached. */
    public void releasePreparedMesh(String variantKey) {
        synchronized (pendingMeshes) {
            pendingMeshes.remove(variantKey);
        }
    }

    public void shutdown() {
        workers.shutdownNow();
        synchronized (pendingMeshes) {
            pendingMeshes.clear();
        }
        synchronized (relationCache) {
            relationCache.clear();
        }
        synchronized (pairCache) {
            pairCache.clear();
        }
    }

    int relationCacheSize() {
        synchronized (relationCache) {
            return relationCache.size();
        }
    }

    long pairCacheHits() {
        synchronized (pairCache) {
            return pairCacheHits;
        }
    }

    private static TerrainEdge touchingEdge(QuadtreeNode fine, QuadtreeNode coarse) {
        if (equal(fine.getX1(), coarse.getX0()) && overlaps(
            fine.getY0(), fine.getY1(), coarse.getY0(), coarse.getY1())) {
            return TerrainEdge.EAST;
        }
        if (equal(fine.getX0(), coarse.getX1()) && overlaps(
            fine.getY0(), fine.getY1(), coarse.getY0(), coarse.getY1())) {
            return TerrainEdge.WEST;
        }
        if (equal(fine.getY1(), coarse.getY0()) && overlaps(
            fine.getX0(), fine.getX1(), coarse.getX0(), coarse.getX1())) {
            return TerrainEdge.NORTH;
        }
        if (equal(fine.getY0(), coarse.getY1()) && overlaps(
            fine.getX0(), fine.getX1(), coarse.getX0(), coarse.getX1())) {
            return TerrainEdge.SOUTH;
        }
        return null;
    }

    private static boolean equal(double a, double b) {
        return Math.abs(a - b) <= EPSILON;
    }

    private static boolean overlaps(double a0, double a1, double b0, double b1) {
        return Math.min(a1, b1) - Math.max(a0, b0) > EPSILON;
    }

    private static String topologyKey(List<DrawCommand> commands) {
        StringBuilder key = new StringBuilder();
        for (DrawCommand command : commands) {
            QuadtreeNode node = command.node();
            key.append(node.getTileFile().getAbsolutePath()).append('#').append(node.getId()).append(';');
        }
        return key.toString();
    }

    private static String variantKey(QuadtreeNode node, List<TerrainTilePlan.Neighbour> neighbours) {
        StringBuilder key = new StringBuilder(node.getTileFile().getAbsolutePath());
        for (TerrainTilePlan.Neighbour neighbour : neighbours) {
            key.append('|').append(neighbour.fineEdge()).append(':')
                .append(neighbour.coarseNode().getTileFile().getAbsolutePath()).append('#')
                .append(neighbour.coarseNode().getId());
        }
        return key.toString();
    }
}
