package dumpanalyzer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class Frame implements Comparable<Frame> {
    private final int id;
    private final List<TileInstance> tiles;
    private final List<AxisAlignedBoundingBox> axisAlignedBoundingBoxes;
    private final double[] projectionMatrix;
    private final double[] modelViewMatrix;

    public Frame(int id, List<TileInstance> tiles, double[] projectionMatrix, double[] modelViewMatrix) {
        this.id = id;
        List<TileInstance> copy = new ArrayList<>(tiles);
        copy.sort(Comparator.comparingInt(TileInstance::getContentId));
        this.tiles = Collections.unmodifiableList(copy);
        this.axisAlignedBoundingBoxes = Collections.unmodifiableList(buildAabbsFromTiles(copy));
        this.projectionMatrix = projectionMatrix == null ? null : projectionMatrix.clone();
        this.modelViewMatrix = modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    public int getId() {
        return id;
    }

    public List<TileInstance> getTiles() {
        return tiles;
    }

    public List<AxisAlignedBoundingBox> getAxisAlignedBoundingBoxes() {
        return axisAlignedBoundingBoxes;
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

    private static List<AxisAlignedBoundingBox> buildAabbsFromTiles(List<TileInstance> tiles) {
        List<AxisAlignedBoundingBox> out = new ArrayList<>(tiles.size());
        for (int i = 0; i < tiles.size(); i++) {
            TileInstance tile = tiles.get(i);
            if (tile == null || tile.getMin() == null || tile.getMax() == null) {
                continue;
            }
            out.add(new AxisAlignedBoundingBox(
                tile.getMin(),
                tile.getMax(),
                tile.getContentId(),
                i,
                tile.getModelViewMatrix(),
                tile.getPoints()
            ));
        }
        return out;
    }
}
