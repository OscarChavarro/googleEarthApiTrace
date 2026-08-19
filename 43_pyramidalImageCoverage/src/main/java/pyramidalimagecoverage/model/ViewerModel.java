package pyramidalimagecoverage.model;

import java.util.ArrayList;
import java.util.List;

public final class ViewerModel {
    private final PyramidCatalog catalog;
    private final List<Runnable> listeners = new ArrayList<>();
    private int selectedDepth;
    private TileAddress selectedAddress;
    private TileAddress secondarySelectedAddress;
    private TileAddress lastSelectedAddress;

    public ViewerModel(PyramidCatalog catalog) {
        this.catalog = catalog;
    }

    public PyramidCatalog catalog() {
        return catalog;
    }

    public int selectedDepth() {
        return selectedDepth;
    }

    public void previousDepth() {
        setSelectedDepth(selectedDepth - 1);
    }

    public void nextDepth() {
        setSelectedDepth(selectedDepth + 1);
    }

    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Notifies the view after a background catalog batch has become available. */
    public void catalogChanged() {
        notifyListeners();
    }

    public void toggleSelection(TileRecord tile) {
        if (tile != null) {
            toggleSelection(tile.address());
        }
    }

    public void toggleSelection(TileAddress address) {
        if (address == null || !address.hasGeographicCoverage()) {
            return;
        }
        TileRecord tile = catalog.tileAt(address.depth(), address.column(), address.southRow());
        boolean selecting = tile == null
            ? !address.equals(selectedAddress)
            : !tile.selected();
        boolean changed = tile != null && catalog.setSelectionRecursively(tile, selecting);
        TileAddress newSelectedAddress = selecting ? address : null;
        if (!java.util.Objects.equals(selectedAddress, newSelectedAddress)) {
            selectedAddress = newSelectedAddress;
            changed = true;
        }
        updateLastSelectedAddress(address, selecting);
        if (changed) {
            notifyListeners();
        }
    }

    public TileAddress selectedAddress() {
        return selectedAddress;
    }

    public TileAddress lastSelectedAddress() {
        return lastSelectedAddress;
    }

    public void toggleSecondarySelection(TileAddress address) {
        if (address == null || !address.hasGeographicCoverage()) {
            return;
        }
        TileAddress newAddress = address.equals(secondarySelectedAddress) ? null : address;
        if (!java.util.Objects.equals(secondarySelectedAddress, newAddress)) {
            secondarySelectedAddress = newAddress;
            updateLastSelectedAddress(address, newAddress != null);
            notifyListeners();
        }
    }

    public TileAddress secondarySelectedAddress() {
        return secondarySelectedAddress;
    }

    public boolean isSecondarySelectedAt(int depth, int column, int southRow) {
        return secondarySelectedAddress != null
            && secondarySelectedAddress.depth() == depth
            && secondarySelectedAddress.column() == column
            && secondarySelectedAddress.southRow() == southRow;
    }

    public boolean isSelectedAt(int depth, int column, int southRow) {
        TileRecord tile = catalog.tileAt(depth, column, southRow);
        if (tile != null && tile.selected()) {
            return true;
        }
        return selectedAddress != null
            && selectedAddress.depth() == depth
            && selectedAddress.column() == column
            && selectedAddress.southRow() == southRow;
    }

    public void clearSelection() {
        boolean changed = clearPrimarySelectionWithoutNotification();
        if (secondarySelectedAddress != null) {
            secondarySelectedAddress = null;
            changed = true;
        }
        if (changed) {
            lastSelectedAddress = null;
            notifyListeners();
        }
    }

    public void clearPrimarySelection() {
        if (clearPrimarySelectionWithoutNotification()) {
            notifyListeners();
        }
    }

    public void clearSecondarySelection() {
        if (secondarySelectedAddress != null) {
            secondarySelectedAddress = null;
            if (!isAddressSelected(lastSelectedAddress)) {
                lastSelectedAddress = null;
            }
            notifyListeners();
        }
    }

    private boolean clearPrimarySelectionWithoutNotification() {
        boolean changed = catalog.clearSelection();
        if (selectedAddress != null) {
            selectedAddress = null;
            changed = true;
        }
        if (!isAddressSelected(lastSelectedAddress)) {
            lastSelectedAddress = null;
        }
        return changed;
    }

    private void updateLastSelectedAddress(TileAddress address, boolean selecting) {
        if (selecting) {
            lastSelectedAddress = address;
            return;
        }
        if (java.util.Objects.equals(lastSelectedAddress, address) && !isAddressSelected(address)) {
            lastSelectedAddress = null;
        }
    }

    private boolean isAddressSelected(TileAddress address) {
        if (address == null) {
            return false;
        }
        return java.util.Objects.equals(selectedAddress, address)
            || java.util.Objects.equals(secondarySelectedAddress, address);
    }

    private void setSelectedDepth(int depth) {
        int clamped = Math.max(0, Math.min(catalog.maxDepth(), depth));
        if (clamped == selectedDepth) {
            return;
        }
        selectedDepth = clamped;
        notifyListeners();
    }

    private void notifyListeners() {
        listeners.forEach(Runnable::run);
    }
}
