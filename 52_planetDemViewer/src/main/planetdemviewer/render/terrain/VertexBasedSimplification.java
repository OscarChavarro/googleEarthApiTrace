package planetdemviewer.render.terrain;

/**
 * The vertex attributes of one block and their per-frame update. It answers
 * {@link TerrainVertexSelection} with the "enabled" attribute.
 *
 * <p>References:</p>
 * <ul>
 * <li>[LIND1996] Lindstrom, P. et al. "Real-Time, Continuous Level of Detail
 * Rendering of Height Fields", SIGGRAPH 1996.</li>
 * </ul>
 *
 * <p>This class implements lines 10 to 20 of MAIN, and UPDATE-VERTEX, EVALUATE-VERTEX and
 * NOTIFY, from section [LIND1996].5, with the dependencies of section [LIND1996].4.3.</p>
 *
 * <p>Each edge of the block is in one of three states, see {@link BlockBoundaryConstraints}:
 * locked, with every vertex enabled; free, with only the dependencies inside the block; or
 * shared with the adjacent block. A shared edge follows section [LIND1996].4.3, which keeps
 * all the copies of a boundary vertex consistent. A corner is no base vertex here, so a copy
 * has just one other copy, and the paper's circular linked list reduces to a reference to
 * the adjacent block. A copy does not forward the enabled attribute of the other, since two
 * copies would then keep each other enabled forever. It forwards the reasons of its own
 * block, activated or a dependency, so both copies are enabled when any of those holds.</p>
 *
 * <p>Departures from the paper:</p>
 * <ul>
 * <li>Blocks of different levels do not share vertices: their common edge stays locked, with
 * every sample enabled, so that seam stitching applies as in the other schemes. The paper
 * instead suggests that locked may disable some vertices of the finer block. The cost is that
 * this detail spreads inward through the dependencies.</li>
 * <li>Line 4 of the paper's MAIN and the rest of the block-based simplification of section
 * [LIND1996].4.2 are not implemented: the block comes from the area-based tile selection, so
 * the threshold only bounds the error inside each block.</li>
 * <li>A block that is not selected keeps its attributes, rather than restarting with
 * Iu = [0, inf): the positions in the sorted vertices stay valid for any next interval.</li>
 * <li>The paper's one-frame delay after a deferred evaluation can also affect a still
 * camera here, so a repeated viewpoint is only skipped when the previous pass changed
 * nothing.</li>
 * </ul>
 */
final class VertexBasedSimplification implements TerrainVertexSelection {
    private static final int DEPENDENCIES = 0x0F;
    private static final int ACTIVATED = 0x10;
    private static final int ENABLED = 0x20;
    private static final int LOCKED = 0x40;
    /** The copy in the adjacent block has a reason to be enabled. It defers no evaluation, unlike a dependency. */
    private static final int OTHER_COPY_NEEDED = 0x80;

    private static final int LAST = TerrainVertexTree.SIDE - 1;
    private static final TerrainEdge[] EDGES = TerrainEdge.values();

    private final TerrainVertexTree tree = TerrainVertexTree.INSTANCE;
    private final VertexDeltaTable deltas;
    private final double[] positions;
    private final byte[] attributes = new byte[TerrainVertexTree.VERTEX_COUNT];
    /** A reason to be enabled from outside the paper: see {@link AdaptiveTerrainMesh}. */
    private final boolean[] requiredVertices = new boolean[TerrainVertexTree.VERTEX_COUNT];
    private final double[] meshBounds;
    private final boolean[] lockedEdges = new boolean[EDGES.length];
    private final VertexBasedSimplification[] adjacentBlocks = new VertexBasedSimplification[EDGES.length];

    /** The sorted vertices before this position have delta in I0 of earlier frames, so they are deactivated. */
    private int deactivatedEnd;
    /** The sorted vertices from this position have delta in I1 of earlier frames, so they are activated. */
    private int activatedStart;
    private ProjectedDeltaCriterion lastCriterion;
    private TerrainMeshPlacement lastPlacement;
    /** Changed in the running pass, which is what {@link #simplify} reports. */
    private boolean enabledSetChangedInPass;
    /** Changed since the last {@link #takeEnabledSetChanged}, by this block or by an adjacent one. */
    private boolean enabledSetChanged;
    private boolean unsettled = true;
    private int deltaProjections;
    private double lastDeltaLowInMeshUnits;

    VertexBasedSimplification(VertexDeltaTable deltas, double[] positions) {
        this.deltas = deltas;
        this.positions = positions;
        this.meshBounds = boundsOf(positions);
        this.activatedStart = deltas.orderedCount();
        java.util.Arrays.fill(lockedEdges, true);
        for (int vertex = 0; vertex < TerrainVertexTree.VERTEX_COUNT; vertex++) {
            if (!tree.isBase(vertex) || tree.isOnBoundary(vertex)) {
                attributes[vertex] = LOCKED | ENABLED | ACTIVATED;
            }
        }
        for (int vertex = 0; vertex < TerrainVertexTree.VERTEX_COUNT; vertex++) {
            if ((attributes[vertex] & LOCKED) != 0) {
                notifyParents(vertex, true);
            }
        }
    }

    private static double[] boundsOf(double[] positions) {
        double[] bounds = {
            Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = 0; i < positions.length; i++) {
            int axis = i % 3;
            bounds[axis] = Math.min(bounds[axis], positions[i]);
            bounds[axis + 3] = Math.max(bounds[axis + 3], positions[i]);
        }
        return bounds;
    }

    @Override
    public boolean isEnabled(int row, int column) {
        return (attributes[TerrainVertexTree.vertex(row, column)] & ENABLED) != 0;
    }

    /** Keeps an interior vertex enabled, whatever the criterion says. Its dependencies follow as usual. */
    void requireVertex(int vertex, boolean required) {
        if (requiredVertices[vertex] != required) {
            requiredVertices[vertex] = required;
            followAttributes(vertex);
        }
    }

    /**
     * Sets the state of one edge. Does nothing when it already has that state, so this can run in
     * every frame.
     *
     * @param locked whether every vertex of the edge stays enabled
     * @param adjacent the block whose opposite edge shares these vertices, or null
     */
    void constrainEdge(TerrainEdge edge, boolean locked, VertexBasedSimplification adjacent) {
        int index = edge.ordinal();
        if (adjacentBlocks[index] != null && adjacentBlocks[index] != adjacent) {
            disconnect(edge);
        }
        if (lockedEdges[index] != locked) {
            setEdgeLocked(edge, locked);
        }
        if (adjacent != null && adjacentBlocks[index] != adjacent) {
            connect(edge, adjacent);
        }
    }

    private void setEdgeLocked(TerrainEdge edge, boolean locked) {
        lockedEdges[edge.ordinal()] = locked;
        for (int i = 1; i < LAST; i++) {
            int vertex = TerrainVertexTree.edgeVertex(edge, i);
            if (locked) {
                attributes[vertex] = (byte) (attributes[vertex] | LOCKED | ACTIVATED);
                if ((attributes[vertex] & ENABLED) == 0) {
                    setEnabled(vertex, true);
                }
            }
            else {
                attributes[vertex] = (byte) (attributes[vertex] & ~LOCKED);
                updateOtherCopy(vertex);
            }
        }
        // Vertices that were locked are not where their delta says. As section [LIND1996].5
        // does for a block that starts being active, this restarts with Iu = [0, inf).
        deactivatedEnd = 0;
        activatedStart = deltas.orderedCount();
        unsettled = true;
    }

    private void connect(TerrainEdge edge, VertexBasedSimplification adjacent) {
        adjacent.disconnect(edge.opposite());
        adjacentBlocks[edge.ordinal()] = adjacent;
        adjacent.adjacentBlocks[edge.opposite().ordinal()] = this;
        for (int i = 1; i < LAST; i++) {
            updateOtherCopy(TerrainVertexTree.edgeVertex(edge, i));
            adjacent.updateOtherCopy(TerrainVertexTree.edgeVertex(edge.opposite(), i));
        }
    }

    private void disconnect(TerrainEdge edge) {
        VertexBasedSimplification adjacent = adjacentBlocks[edge.ordinal()];
        if (adjacent == null) {
            return;
        }
        adjacentBlocks[edge.ordinal()] = null;
        adjacent.adjacentBlocks[edge.opposite().ordinal()] = null;
        for (int i = 1; i < LAST; i++) {
            otherCopyChanged(TerrainVertexTree.edgeVertex(edge, i), false);
            adjacent.otherCopyChanged(TerrainVertexTree.edgeVertex(edge.opposite(), i), false);
        }
    }

    /** Sends this copy's reasons to be enabled to the other copy, if it has one. */
    private void updateOtherCopy(int vertex) {
        if (!tree.isOnBoundary(vertex)) {
            return;
        }
        TerrainEdge edge = TerrainVertexTree.edgeOf(vertex);
        VertexBasedSimplification adjacent = adjacentBlocks[edge.ordinal()];
        if (adjacent != null) {
            adjacent.otherCopyChanged(TerrainVertexTree.otherCopy(vertex),
                (attributes[vertex] & (ACTIVATED | DEPENDENCIES)) != 0);
        }
    }

    private void otherCopyChanged(int vertex, boolean needed) {
        int attribute = attributes[vertex];
        if (((attribute & OTHER_COPY_NEEDED) != 0) == needed) {
            return;
        }
        attributes[vertex] = (byte) (needed ? attribute | OTHER_COPY_NEEDED : attribute & ~OTHER_COPY_NEEDED);
        unsettled = true;
        followAttributes(vertex);
    }

    /** Lines 10 to 20 of MAIN. Returns whether the set of enabled vertices changed. */
    boolean simplify(ProjectedDeltaCriterion criterion, TerrainMeshPlacement placement) {
        deltaProjections = 0;
        if (!unsettled && criterion.equals(lastCriterion) && placement.equals(lastPlacement)) {
            return false;
        }
        lastCriterion = criterion;
        lastPlacement = placement;
        enabledSetChangedInPass = false;
        unsettled = false;

        ProjectedDeltaCriterion.UncertaintyInterval interval = criterion.uncertaintyInterval(
            placement.worldX(meshBounds[0]), placement.worldY(meshBounds[1]), placement.worldZ(meshBounds[2]),
            placement.worldX(meshBounds[3]), placement.worldY(meshBounds[4]), placement.worldZ(meshBounds[5]));
        double toMeshUnits = 1.0 / placement.verticalScale();
        lastDeltaLowInMeshUnits = interval.low() * toMeshUnits;
        int uncertainStart = deltas.positionAfter(lastDeltaLowInMeshUnits);
        int uncertainEnd = Double.isInfinite(interval.high()) ? deltas.orderedCount()
            : deltas.positionAfter(interval.high() * toMeshUnits);

        for (int position = deactivatedEnd; position < uncertainStart; position++) {
            setActivatedAndUpdate(deltas.orderedVertex(position), false);
        }
        for (int position = uncertainEnd; position < activatedStart; position++) {
            setActivatedAndUpdate(deltas.orderedVertex(position), true);
        }
        for (int position = uncertainStart; position < uncertainEnd; position++) {
            evaluateVertex(deltas.orderedVertex(position), placement);
        }
        deactivatedEnd = uncertainStart;
        activatedStart = uncertainEnd;
        return enabledSetChangedInPass;
    }

    /** Returns whether the triangles changed since the last call, by this block or by an adjacent one. */
    boolean takeEnabledSetChanged() {
        boolean changed = enabledSetChanged;
        enabledSetChanged = false;
        return changed;
    }

    /** UPDATE-VERTEX, with the assignment of activated that precedes it in MAIN. */
    private void setActivatedAndUpdate(int vertex, boolean activated) {
        int attribute = attributes[vertex];
        if ((attribute & LOCKED) != 0) {
            return;
        }
        attributes[vertex] = (byte) (activated ? attribute | ACTIVATED : attribute & ~ACTIVATED);
        followAttributes(vertex);
    }

    /** EVALUATE-VERTEX: the evaluation is deferred while the vertex has dependencies. */
    private void evaluateVertex(int vertex, TerrainMeshPlacement placement) {
        int attribute = attributes[vertex];
        if ((attribute & LOCKED) != 0 || (attribute & DEPENDENCIES) != 0) {
            return;
        }
        int p = vertex * 3;
        deltaProjections++;
        boolean activated = !lastCriterion.coalesces(deltas.delta(vertex) * placement.verticalScale(),
            placement.worldX(positions[p]), placement.worldY(positions[p + 1]), placement.worldZ(positions[p + 2]));
        attributes[vertex] = (byte) (activated ? attribute | ACTIVATED : attribute & ~ACTIVATED);
        followAttributes(vertex);
    }

    /** The relationship of section [LIND1996].4.3: enabled follows activated and the dependencies, on any copy. */
    private void followAttributes(int vertex) {
        int attribute = attributes[vertex];
        if ((attribute & LOCKED) != 0) {
            return;
        }
        boolean enabled = (attribute & (ACTIVATED | DEPENDENCIES | OTHER_COPY_NEEDED)) != 0 || requiredVertices[vertex];
        if (enabled != ((attribute & ENABLED) != 0)) {
            setEnabled(vertex, enabled);
        }
        updateOtherCopy(vertex);
    }

    private void setEnabled(int vertex, boolean enabled) {
        int attribute = attributes[vertex];
        attributes[vertex] = (byte) (enabled ? attribute | ENABLED : attribute & ~ENABLED);
        enabledSetChangedInPass = true;
        enabledSetChanged = true;
        unsettled = true;
        notifyParents(vertex, enabled);
    }

    private void notifyParents(int vertex, boolean state) {
        notifyVertex(tree.parent(vertex, 0), tree.branch(vertex, 0), state);
        notifyVertex(tree.parent(vertex, 1), tree.branch(vertex, 1), state);
    }

    /** NOTIFY. */
    private void notifyVertex(int vertex, int child, boolean state) {
        if (vertex == TerrainVertexTree.NONE) {
            return;
        }
        int attribute = attributes[vertex];
        attributes[vertex] = (byte) (state ? attribute | (1 << child) : attribute & ~(1 << child));
        followAttributes(vertex);
    }

    /** The number of evaluations of Equation [LIND1996](2) in the last pass. */
    int deltaProjections() {
        return deltaProjections;
    }

    /** The test of line 4 of MAIN, which is only reported because the block selection does not use it. */
    boolean couldUseLowerResolutionBlock() {
        return deltas.deltaMax() <= lastDeltaLowInMeshUnits;
    }
}
