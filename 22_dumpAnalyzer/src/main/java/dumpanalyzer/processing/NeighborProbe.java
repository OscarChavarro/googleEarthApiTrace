package dumpanalyzer.processing;

import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

public record NeighborProbe(int tileIndex, int contentId, Vector3Dd center, double diagonal) {
}
