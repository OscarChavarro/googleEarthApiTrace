package dumpanalyzer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class Frame implements Comparable<Frame> {
    private final int id;
    private final List<TileInstance> tiles;
    private final double[] projectionMatrix;
    private final double[] modelViewMatrix;

    public Frame(int id, List<TileInstance> tiles, double[] projectionMatrix, double[] modelViewMatrix) {
        this.id = id;
        List<TileInstance> copy = new ArrayList<>(tiles);
        copy.sort(Comparator.comparingInt(TileInstance::getContentId));
        this.tiles = Collections.unmodifiableList(copy);
        this.projectionMatrix = projectionMatrix == null ? null : projectionMatrix.clone();
        this.modelViewMatrix = modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    public int getId() {
        return id;
    }

    public List<TileInstance> getTiles() {
        return tiles;
    }

    public double[] getProjectionMatrix() {
        return projectionMatrix == null ? null : projectionMatrix.clone();
    }

    public double[] getModelViewMatrix() {
        return modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    @Override
    public int compareTo(Frame other) {
        return Integer.compare(this.id, other.id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Frame frame)) return false;
        return id == frame.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
