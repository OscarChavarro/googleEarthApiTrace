package planetviewer.render;

import java.util.List;

/**
 * Computes 1..4 viewport layouts (several styles per view count), ported
 * from the old prototype's ViewOrganizer. doLayout also normalizes which
 * view is "selected" (defaulting to the first one if none is), and can put
 * a single view full-screen (selectedForFullScreen >= 0) while keeping the
 * others inactive.
 */
public final class ViewOrganizer {
    private ViewOrganizer() {
    }

    private static void doLayout1(View view) {
        view.setActive(true);
        view.setViewportStartXPercent(0.0);
        view.setViewportStartYPercent(0.0);
        view.setViewportSizeXPercent(1.0);
        view.setViewportSizeYPercent(1.0);
    }

    private static void doLayout2(List<View> views, int style) {
        views.get(0).setActive(true);
        views.get(1).setActive(true);
        if (style == 0) {
            set(views.get(0), 0.0, 0.0, 0.5, 1.0);
            set(views.get(1), 0.5, 0.0, 0.5, 1.0);
        }
        else {
            set(views.get(0), 0.0, 0.5, 1.0, 0.5);
            set(views.get(1), 0.0, 0.0, 1.0, 0.5);
        }
    }

    private static void doLayout3(List<View> views, int style) {
        double p00 = 0;
        double p50 = 0.5;
        double p33 = 1.0 / 3.0;
        double p66 = 2.0 / 3.0;
        double p100 = 1;
        double[][] start = new double[3][2];
        double[][] size = new double[3][2];

        switch (style % 6) {
            case 0 -> {
                start[0] = new double[] {p00, p00}; start[1] = new double[] {p00, p50}; start[2] = new double[] {p50, p00};
                size[0] = new double[] {p50, p50}; size[1] = new double[] {p50, p50}; size[2] = new double[] {p50, p100};
            }
            case 1 -> {
                start[0] = new double[] {p00, p00}; start[1] = new double[] {p50, p00}; start[2] = new double[] {p50, p50};
                size[0] = new double[] {p50, p100}; size[1] = new double[] {p50, p50}; size[2] = new double[] {p50, p50};
            }
            case 2 -> {
                start[0] = new double[] {p00, p00}; start[1] = new double[] {p50, p00}; start[2] = new double[] {p00, p50};
                size[0] = new double[] {p50, p50}; size[1] = new double[] {p50, p50}; size[2] = new double[] {p100, p50};
            }
            case 3 -> {
                start[0] = new double[] {p00, p00}; start[1] = new double[] {p00, p50}; start[2] = new double[] {p50, p50};
                size[0] = new double[] {p100, p50}; size[1] = new double[] {p50, p50}; size[2] = new double[] {p50, p50};
            }
            case 4 -> {
                start[0] = new double[] {p00, p00}; start[1] = new double[] {p33, p00}; start[2] = new double[] {p66, p00};
                size[0] = new double[] {p33, p100}; size[1] = new double[] {p33, p100}; size[2] = new double[] {p33, p100};
            }
            default -> {
                start[0] = new double[] {p00, p00}; start[1] = new double[] {p00, p33}; start[2] = new double[] {p00, p66};
                size[0] = new double[] {p100, p33}; size[1] = new double[] {p100, p33}; size[2] = new double[] {p100, p33};
            }
        }
        for (int i = 0; i < 3; i++) {
            views.get(i).setActive(true);
            set(views.get(i), start[i][0], start[i][1], size[i][0], size[i][1]);
        }
    }

    private static void doLayout4(List<View> views, int style) {
        double p00 = 0;
        double p33 = 1.0 / 3.0;
        double p50 = 0.5;
        double p66 = 2.0 / 3.0;
        double p100 = 1;
        double[][] start = new double[4][2];
        double[][] size = new double[4][2];

        switch (style % 5) {
            case 0 -> {
                start[0] = new double[] {p00, p00}; start[3] = new double[] {p00, p33}; start[2] = new double[] {p00, p66}; start[1] = new double[] {p33, p00};
                size[0] = new double[] {p33, p33}; size[3] = new double[] {p33, p33}; size[2] = new double[] {p33, p33}; size[1] = new double[] {p66, p100};
            }
            case 1 -> {
                start[0] = new double[] {p00, p00}; start[1] = new double[] {p50, p00}; start[2] = new double[] {p00, p50}; start[3] = new double[] {p50, p50};
                size[0] = new double[] {p50, p50}; size[1] = new double[] {p50, p50}; size[2] = new double[] {p50, p50}; size[3] = new double[] {p50, p50};
            }
            case 2 -> {
                start[0] = new double[] {p00, p00}; start[1] = new double[] {p50, p00}; start[2] = new double[] {p50, p33}; start[3] = new double[] {p50, p66};
                size[0] = new double[] {p50, p100}; size[1] = new double[] {p50, p33}; size[2] = new double[] {p50, p33}; size[3] = new double[] {p50, p33};
            }
            case 3 -> {
                start[0] = new double[] {p00, p00}; start[1] = new double[] {p33, p00}; start[2] = new double[] {p66, p00}; start[3] = new double[] {p00, p50};
                size[0] = new double[] {p33, p50}; size[1] = new double[] {p33, p50}; size[2] = new double[] {p33, p50}; size[3] = new double[] {p100, p50};
            }
            default -> {
                start[0] = new double[] {p00, p00}; start[1] = new double[] {p00, p50}; start[2] = new double[] {p33, p50}; start[3] = new double[] {p66, p50};
                size[0] = new double[] {p100, p50}; size[1] = new double[] {p33, p50}; size[2] = new double[] {p33, p50}; size[3] = new double[] {p33, p50};
            }
        }
        for (int i = 0; i < 4; i++) {
            views.get(i).setActive(true);
            set(views.get(i), start[i][0], start[i][1], size[i][0], size[i][1]);
        }
    }

    /** @return the index of the selected view after layout. */
    public static int doLayout(List<View> views, int selectedForFullScreen, int style) {
        int selected = 0;
        if (selectedForFullScreen >= 0 && selectedForFullScreen < views.size()) {
            for (int i = 0; i < views.size(); i++) {
                View view = views.get(i);
                if (i == selectedForFullScreen) {
                    view.setActive(true);
                    view.setSelected(true);
                    doLayout1(view);
                    selected = i;
                }
                else {
                    view.setActive(false);
                    view.setSelected(false);
                }
            }
            return selected;
        }

        boolean isSelected = false;
        for (int i = 0; i < views.size(); i++) {
            View view = views.get(i);
            if (!isSelected && view.isSelected()) {
                isSelected = true;
                selected = i;
            }
            else if (isSelected) {
                view.setSelected(false);
            }
        }
        if (!isSelected && !views.isEmpty()) {
            views.get(0).setSelected(true);
            selected = 0;
        }

        switch (views.size()) {
            case 0 -> System.out.println("PlanetViewer: warning: no views to lay out.");
            case 1 -> doLayout1(views.get(0));
            case 2 -> doLayout2(views, style % 2);
            case 3 -> doLayout3(views, style % 6);
            case 4 -> doLayout4(views, style % 5);
            default -> {
                System.out.println("PlanetViewer: warning: unsupported view count, selecting the first view full-screen.");
                doLayout1(views.get(0));
            }
        }
        return selected;
    }

    private static void set(View view, double startX, double startY, double sizeX, double sizeY) {
        view.setViewportStartXPercent(startX);
        view.setViewportStartYPercent(startY);
        view.setViewportSizeXPercent(sizeX);
        view.setViewportSizeYPercent(sizeY);
    }
}
