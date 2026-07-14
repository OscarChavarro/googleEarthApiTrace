package pyramidalimagecoverage.model;

import java.util.ArrayList;
import java.util.List;

public final class ViewerModel {
    private final PyramidCatalog catalog;
    private final List<Runnable> listeners = new ArrayList<>();
    private int selectedDepth;

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

    private void setSelectedDepth(int depth) {
        int clamped = Math.max(0, Math.min(catalog.maxDepth(), depth));
        if (clamped == selectedDepth) {
            return;
        }
        selectedDepth = clamped;
        listeners.forEach(Runnable::run);
    }
}
