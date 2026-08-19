package pyramidalimagecoverage.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void selectsAndClearsAnAddressWithoutImageData() {
        PyramidCatalog catalog = new PyramidCatalog(Path.of("."));
        add(catalog, "0");
        ViewerModel model = new ViewerModel(catalog);
        TileAddress missing = TileAddress.fromCoordinates(1, 1, 1);

        model.toggleSelection(missing);

        assertTrue(model.isSelectedAt(1, 1, 1));
        assertTrue(missing.equals(model.selectedAddress()));

        model.toggleSelection(missing);

        assertFalse(model.isSelectedAt(1, 1, 1));
        assertTrue(model.selectedAddress() == null);
    }

    @Test
    void secondarySelectionIsIndependentFromPrimarySelection() {
        PyramidCatalog catalog = new PyramidCatalog(Path.of("."));
        add(catalog, "0");
        ViewerModel model = new ViewerModel(catalog);
        TileAddress primary = TileAddress.fromCoordinates(1, 0, 0);
        TileAddress secondary = TileAddress.fromCoordinates(1, 1, 1);

        model.toggleSelection(primary);
        model.toggleSecondarySelection(secondary);

        assertEquals(primary, model.selectedAddress());
        assertEquals(secondary, model.secondarySelectedAddress());
        assertTrue(model.isSecondarySelectedAt(1, 1, 1));
        assertFalse(model.isSecondarySelectedAt(1, 0, 0));

        model.clearPrimarySelection();
        assertNull(model.selectedAddress());
        assertEquals(secondary, model.secondarySelectedAddress());

        model.toggleSecondarySelection(secondary);
        assertNull(model.secondarySelectedAddress());
    }

    @Test
    void remembersTheLastTileThatWasSelected() {
        PyramidCatalog catalog = new PyramidCatalog(Path.of("."));
        add(catalog, "0");
        ViewerModel model = new ViewerModel(catalog);
        TileAddress primary = TileAddress.fromCoordinates(1, 0, 0);
        TileAddress secondary = TileAddress.fromCoordinates(1, 1, 1);

        model.toggleSelection(primary);
        assertEquals(primary, model.lastSelectedAddress());

        model.toggleSecondarySelection(secondary);
        assertEquals(secondary, model.lastSelectedAddress());

        model.toggleSecondarySelection(secondary);
        assertNull(model.lastSelectedAddress());
    }

    private static TileRecord add(PyramidCatalog catalog, String quadKey) {
        TileRecord tile = new TileRecord(TileAddress.fromQuadKey(quadKey), Path.of(quadKey + ".png"));
        catalog.add(tile);
        return tile;
    }
}
