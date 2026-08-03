package planetdemviewer.terrain;

import planetdemviewer.model.DemTile;
import planetdemviewer.model.QuadtreeNode;

/** Extension point for DTM triangulation; rendering remains texture-only for now. */
public interface TerrainMeshGenerator<T> {
    T generate(QuadtreeNode node, DemTile elevationsWithHalo);
}
