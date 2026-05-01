package pyramidalimagebuilder.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import pyramidalimagebuilder.model.TileInstance;

public final class TraceSessionReader {
    private static final Pattern NUMERIC_DIR = Pattern.compile("\\d+");

    public List<TileInstance> readSession(Path inputRoot) {
        if (inputRoot == null || !Files.isDirectory(inputRoot)) {
            return List.of();
        }

        TileInstanceReader tileReader = new TileInstanceReader();
        List<TileInstance> all = new ArrayList<>();

        try (var stream = Files.list(inputRoot)) {
            stream
                .filter(Files::isDirectory)
                .filter(dir -> NUMERIC_DIR.matcher(dir.getFileName().toString()).matches())
                .sorted(Comparator.comparing(dir -> dir.getFileName().toString()))
                .forEach(dir -> {
                    Path frameJson = dir.resolve("frame.json");
                    if (!Files.isRegularFile(frameJson)) {
                        return;
                    }
                    try {
                        all.addAll(tileReader.read(frameJson));
                    }
                    catch (IOException ignored) {
                    }
                });
        }
        catch (IOException ignored) {
            return List.of();
        }

        return all;
    }
}
