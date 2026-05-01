package dumpanalyzer.processing;

import vsdk.toolkit.common.linealAlgebra.Vector3D;

public record NeighborProbe(int tileIndex, Vector3D center, double diagonal) {
}
