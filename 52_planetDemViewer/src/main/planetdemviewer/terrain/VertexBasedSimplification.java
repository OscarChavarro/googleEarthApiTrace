package planetdemviewer.terrain;

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
 * <p>Departures from the paper:</p>
 * <ul>
 * <li>Dependencies do not cross blocks. Instead, the boundary vertices of a block are locked
 * as enabled, so that adjacent blocks always meet with all their samples and the seam
 * stitching applies as in the other schemes. The cost is that this detail spreads inward
 * through the dependencies.</li>
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

    private final TerrainVertexTree tree = TerrainVertexTree.INSTANCE;
    private final VertexDeltaTable deltas;
    private final double[] positions;
    private final byte[] attributes = new byte[TerrainVertexTree.VERTEX_COUNT];
    private final double[] meshBounds;

    /** The sorted vertices before this position have delta in I0 of earlier frames, so they are deactivated. */
    private int deactivatedEnd;
    /** The sorted vertices from this position have delta in I1 of earlier frames, so they are activated. */
    private int activatedStart;
    private ProjectedDeltaCriterion lastCriterion;
    private TerrainMeshPlacement lastPlacement;
    private boolean enabledSetChanged;
    private boolean unsettled = true;
    private int deltaProjections;
    private double lastDeltaLowInMeshUnits;

    VertexBasedSimplification(VertexDeltaTable deltas, double[] positions) {
        this.deltas = deltas;
        this.positions = positions;
        this.meshBounds = boundsOf(positions);
        this.activatedStart = deltas.orderedCount();
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

    /** Lines 10 to 20 of MAIN. Returns whether the set of enabled vertices changed. */
    boolean simplify(ProjectedDeltaCriterion criterion, TerrainMeshPlacement placement) {
        deltaProjections = 0;
        if (!unsettled && criterion.equals(lastCriterion) && placement.equals(lastPlacement)) {
            return false;
        }
        lastCriterion = criterion;
        lastPlacement = placement;
        enabledSetChanged = false;

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
        unsettled = enabledSetChanged;
        return enabledSetChanged;
    }

    /** UPDATE-VERTEX, with the assignment of activated that precedes it in MAIN. */
    private void setActivatedAndUpdate(int vertex, boolean activated) {
        int attribute = attributes[vertex];
        if ((attribute & LOCKED) != 0) {
            return;
        }
        attribute = activated ? attribute | ACTIVATED : attribute & ~ACTIVATED;
        attributes[vertex] = (byte) attribute;
        followActivated(vertex, attribute);
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
        attribute = activated ? attribute | ACTIVATED : attribute & ~ACTIVATED;
        attributes[vertex] = (byte) attribute;
        followActivated(vertex, attribute);
    }

    private void followActivated(int vertex, int attribute) {
        if ((attribute & DEPENDENCIES) != 0) {
            return;
        }
        boolean activated = (attribute & ACTIVATED) != 0;
        if (activated != ((attribute & ENABLED) != 0)) {
            setEnabled(vertex, activated);
        }
    }

    private void setEnabled(int vertex, boolean enabled) {
        int attribute = attributes[vertex];
        attributes[vertex] = (byte) (enabled ? attribute | ENABLED : attribute & ~ENABLED);
        enabledSetChanged = true;
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
        attribute = state ? attribute | (1 << child) : attribute & ~(1 << child);
        attributes[vertex] = (byte) attribute;
        if ((attribute & LOCKED) != 0) {
            return;
        }
        if ((attribute & DEPENDENCIES) == 0) {
            if ((attribute & ACTIVATED) == 0) {
                setEnabled(vertex, false);
            }
        }
        else if ((attribute & ENABLED) == 0) {
            setEnabled(vertex, true);
        }
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
