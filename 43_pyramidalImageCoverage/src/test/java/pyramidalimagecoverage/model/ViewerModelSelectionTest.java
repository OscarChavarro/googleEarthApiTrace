package pyramidalimagecoverage.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ViewerModelSelectionTest {
    @Test
    void togglesSelectionRecursivelyAcrossDescendants() {
        PyramidCatalog catalog = new PyramidCatalog(Path.of("."));
        TileRecord root = add(catalog, "0");
        TileRecord child = add(catalog, "00");
        TileRecord grandchild = add(catalog, "003");
        TileRecord sibling = add(catalog, "01");
        ViewerModel model = new ViewerModel(catalog);

        model.toggleSelection(child);

        assertFalse(root.selected());
        assertTrue(child.selected());
        assertTrue(grandchild.selected());
        assertFalse(sibling.selected());

        model.toggleSelection(child);

        assertFalse(child.selected());
        assertFalse(grandchild.selected());
    }

    @Test
    void clearsAllSelectionsRecursively() {
        PyramidCatalog catalog = new PyramidCatalog(Path.of("."));
        TileRecord root = add(catalog, "0");
        TileRecord child = add(catalog, "00");
        TileRecord grandchild = add(catalog, "003");
        ViewerModel model = new ViewerModel(catalog);
        model.toggleSelection(root);

        model.clearSelection();

        assertFalse(root.selected());
        assertFalse(child.selected());
        assertFalse(grandchild.selected());
    }

    private static TileRecord add(PyramidCatalog catalog, String quadKey) {
        TileRecord tile = new TileRecord(TileAddress.fromQuadKey(quadKey), Path.of(quadKey + ".png"));
        catalog.add(tile);
        return tile;
    }
}
