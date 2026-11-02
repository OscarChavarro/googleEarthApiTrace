package planetdemviewer.palette;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import vsdk.toolkit.common.color.ColorRgb;
import vsdk.toolkit.io.image.RGBColorPalettePersistence;
import vsdk.toolkit.media.RGBColorPalette;

/** Loads the repository's GIMP palettes through Vitral and selects one globally. */
public final class PaletteCatalog {
    public static final String DEFAULT_PALETTE = "Topographic.gpl";

    private final List<Entry> entries;
    private final int minimumElevation;
    private final int maximumElevation;
    private int selectedIndex;
    private PaletteSnapshot snapshot;

    public PaletteCatalog(Path directory, int minimumElevation, int maximumElevation) {
        if (maximumElevation <= minimumElevation) {
            throw new IllegalArgumentException("Maximum elevation must exceed minimum elevation");
        }
        this.minimumElevation = minimumElevation;
        this.maximumElevation = maximumElevation;
        this.entries = loadPalettes(directory);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("No .gpl palettes found in " + directory.toAbsolutePath());
        }
        this.selectedIndex = indexOf(DEFAULT_PALETTE);
        if (selectedIndex < 0) {
            selectedIndex = 0;
        }
        rebuildSnapshot();
    }

    private static List<Entry> loadPalettes(Path directory) {
        List<Path> files;
        try (Stream<Path> paths = Files.list(directory)) {
            files = paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gpl"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        }
        catch (IOException ex) {
            throw new IllegalArgumentException("Could not list palette directory " + directory.toAbsolutePath(), ex);
        }

        List<Entry> loaded = new ArrayList<>(files.size());
        for (Path file : files) {
            try (Reader reader = Files.newBufferedReader(file)) {
                RGBColorPalette palette = RGBColorPalettePersistence.importGimpPalette(reader);
                if (palette == null || palette.size() == 0) {
                    throw new IOException("empty palette");
                }
                loaded.add(new Entry(file.getFileName().toString(), palette));
            }
            catch (IOException | RuntimeException ex) {
                throw new IllegalArgumentException("Could not load Vitral palette " + file.toAbsolutePath(), ex);
            }
        }
        return List.copyOf(loaded);
    }

    private int indexOf(String name) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    public synchronized void cycle(int delta) {
        selectedIndex = Math.floorMod(selectedIndex + delta, entries.size());
        rebuildSnapshot();
    }

    public synchronized String selectedName() {
        return entries.get(selectedIndex).name();
    }

    public int size() {
        return entries.size();
    }

    public int minimumElevation() {
        return minimumElevation;
    }

    public int maximumElevation() {
        return maximumElevation;
    }

    public synchronized PaletteSnapshot snapshot() {
        return snapshot;
    }

    private void rebuildSnapshot() {
        int[] lookup = new int[1 << 16];
        for (int value = Short.MIN_VALUE; value <= Short.MAX_VALUE; value++) {
            lookup[value - Short.MIN_VALUE] = calculateArgb((short) value);
        }
        snapshot = new PaletteSnapshot(entries.get(selectedIndex).name(), lookup);
    }

    /** Returns packed ARGB; NoData is transparent. */
    private int calculateArgb(short elevation) {
        if (elevation == Short.MIN_VALUE) {
            return 0;
        }
        double t = (elevation - (double) minimumElevation) / (maximumElevation - (double) minimumElevation);
        ColorRgb color = entries.get(selectedIndex).palette().evalLinear(Math.max(0.0, Math.min(1.0, t)));
        int red = channel(color.r());
        int green = channel(color.g());
        int blue = channel(color.b());
        return 0xff000000 | red << 16 | green << 8 | blue;
    }

    private static int channel(double value) {
        return (int) Math.round(255.0 * Math.max(0.0, Math.min(1.0, value)));
    }

    private record Entry(String name, RGBColorPalette palette) {
    }

    public record PaletteSnapshot(String name, int[] lookup) {
        public int argbFor(short elevation) {
            return lookup[elevation - Short.MIN_VALUE];
        }
    }
}
