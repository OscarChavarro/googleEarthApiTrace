package planetdemviewer.terrain;

import planetdemviewer.model.DemTile;
import planetdemviewer.model.QuadtreeNode;

/** Extension point shared by the current and future adaptive DTM triangulations. */
public interface TerrainMeshGenerator<T> {
    T generate(QuadtreeNode node, DemTile elevationsWithHalo);
}
