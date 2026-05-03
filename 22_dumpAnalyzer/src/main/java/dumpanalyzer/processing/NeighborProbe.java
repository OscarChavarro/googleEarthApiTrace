package dumpanalyzer.processing;

import vsdk.toolkit.common.linealAlgebra.Vector3D;

public record NeighborProbe(int tileIndex, int contentId, Vector3D center, double diagonal) {
}
