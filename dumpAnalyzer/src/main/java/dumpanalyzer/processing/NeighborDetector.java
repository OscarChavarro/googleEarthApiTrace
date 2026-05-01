package dumpanalyzer.processing;

import dumpanalyzer.model.Frame;

public final class NeighborDetector {
    private NeighborDetector() {
    }

    public static void populateNeighbors(Frame frame) {
        if (frame == null) {
            return;
        }
        // Intentionally left blank for now. Neighbor detection will be added here
        // based on geometric relations between AABB centroids.
    }
}
