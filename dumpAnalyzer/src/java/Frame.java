package dumpanalyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Frame implements Comparable<Frame> {
    private final int id;
    private final List<TileInstance> tiles = new ArrayList<>();
    private final Map<Integer, TileInstance> tileByContentId = new LinkedHashMap<>();

    public Frame(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public void addTile(TileInstance tile) {
        if (tileByContentId.containsKey(tile.getContentId())) {
            return;
        }
        tiles.add(tile);
        tileByContentId.put(tile.getContentId(), tile);
    }

    public TileInstance getOrCreateTile(int contentId) {
        TileInstance existing = tileByContentId.get(contentId);
        if (existing != null) {
            return existing;
        }

        TileInstance created = new TileInstance(contentId, null, null, null, null);
        addTile(created);
        return created;
    }

    public TileInstance getTile(int contentId) {
        return tileByContentId.get(contentId);
    }

    public void sortTilesByContentId() {
        tiles.sort(Comparator.comparingInt(TileInstance::getContentId));
    }

    public List<TileInstance> getTiles() {
        return Collections.unmodifiableList(tiles);
    }

    @Override
    public int compareTo(Frame other) {
        return Integer.compare(this.id, other.id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Frame frame)) {
            return false;
        }
        return id == frame.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Frame{id=").append(id).append(", tileCount=").append(tiles.size()).append(", tiles=[");
        for (int i = 0; i < tiles.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(tiles.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }
}
