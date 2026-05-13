package pyramidalimageexporter.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import pyramidalimageexporter.model.TopLevelTiles;

public final class TopLevelTilesReader {
    private static final ObjectMapper JSON = new ObjectMapper();

    public Optional<TopLevelTiles> read(Path outputDirectory) {
        if (outputDirectory == null) {
            System.out.println("TopLevelTilesReader: output directory is null.");
            return Optional.empty();
        }
        Path topLevelTilesPath = outputDirectory.resolve("topLevelTiles.json");
        if (!Files.isRegularFile(topLevelTilesPath) || !Files.isReadable(topLevelTilesPath)) {
            System.out.println("TopLevelTilesReader: missing/unreadable file " + topLevelTilesPath);
            return Optional.empty();
        }
        try {
            TopLevelTiles parsed = JSON.readValue(topLevelTilesPath.toFile(), TopLevelTiles.class);
            if (parsed == null || parsed.getByStripId() == null || parsed.getByStripId().isEmpty()) {
                System.out.println("TopLevelTilesReader: file read but contains no strips.");
                return Optional.empty();
            }
            int strips = parsed.getByStripId().size();
            int appearances = 0;
            Set<String> uniqueImagePaths = new HashSet<>();
            for (TopLevelTiles.TopLevelTile tile : parsed.getByStripId().values()) {
                if (tile == null || tile.getAppearances() == null) {
                    continue;
                }
                appearances += tile.getAppearances().size();
                for (TopLevelTiles.FrameAppearance appearance : tile.getAppearances()) {
                    if (appearance == null || appearance.getImagePath() == null || appearance.getImagePath().isBlank()) {
                        continue;
                    }
                    uniqueImagePaths.add(appearance.getImagePath().trim());
                }
            }
            System.out.println(
                "TopLevelTilesReader: loaded strips=" + strips
                    + ", appearances=" + appearances
                    + ", unique images=" + uniqueImagePaths.size()
                    + " from " + topLevelTilesPath
            );
            return Optional.of(parsed);
        }
        catch (IOException ex) {
            System.out.println("Unable to read " + topLevelTilesPath + ": " + ex.getMessage());
            return Optional.empty();
        }
    }
}
