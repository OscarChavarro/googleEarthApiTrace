package planetviewer.io;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import planetviewer.model.PyramidalImage;
import planetviewer.model.QuadtreeNode;

/**
 * Scans a pyramidal image root folder (the format written by
 * 32_pyramidalImageExporter's PyramidalImageExporter: root "0.png", child
 * folders "00"/"01"/"02"/"03" recursively, quadrant digit convention
 * 0 = south-west, 1 = south-east, 2 = north-east, 3 = north-west) and builds
 * the QuadtreeNode graph. Only the directory structure is scanned here; no
 * pixel data is loaded (tile images are loaded lazily by the renderer).
 */
public final class PyramidalImageFolderReader {
    private int tileCount;
    private int maxDepth;

    public Optional<PyramidalImage> read(Path rootDirectory) {
        if (rootDirectory == null || !Files.isDirectory(rootDirectory)) {
            return Optional.empty();
        }
        File rootTile = rootDirectory.resolve("0.png").toFile();
        if (!rootTile.isFile()) {
            return Optional.empty();
        }
        tileCount = 0;
        maxDepth = 0;
        QuadtreeNode root = scanNode("0", rootDirectory, null);
        String sourceFolder = rootDirectory.toAbsolutePath().normalize().toString();
        return Optional.of(new PyramidalImage(sourceFolder, root, tileCount, maxDepth));
    }

    private QuadtreeNode scanNode(String id, Path containerDirectory, QuadtreeNode parent) {
        File tileImage = containerDirectory.resolve(id + ".png").toFile();
        boolean hasOwnTile = tileImage.isFile();
        QuadtreeNode node = new QuadtreeNode(id, parent, hasOwnTile ? tileImage : null);
        if (hasOwnTile) {
            tileCount++;
        }
        maxDepth = Math.max(maxDepth, node.getDepth());

        QuadtreeNode[] children = new QuadtreeNode[4];
        boolean anyChild = false;
        for (int digit = 0; digit < 4; digit++) {
            String childId = id + digit;
            Path childDirectory = containerDirectory.resolve(childId);
            if (Files.isDirectory(childDirectory)) {
                children[digit] = scanNode(childId, childDirectory, node);
                anyChild = true;
            }
        }
        if (anyChild) {
            node.setChildren(children);
        }
        return node;
    }
}
