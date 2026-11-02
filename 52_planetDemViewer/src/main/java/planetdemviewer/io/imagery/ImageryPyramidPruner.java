package planetdemviewer.io.imagery;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import planetdemviewer.spatial.TileAddress;

/**
 * Removes one imagery tile and all of its descendants from the pyramid.
 *
 * <p>A tile {@code K} lives in a directory holding {@code K.png} and the child
 * directories {@code 0..3}; the pruner only ever descends through those.  It is
 * limited to PNG pages: any other file (a DEM {@code .bin}, for instance) stays
 * in place along with the directories that contain it, and is reported.  The
 * root's {@code cache.bin} sits outside {@code 0..3} and is not even visited.</p>
 *
 * <p>Direct links under the pyramid root point at mounted volumes (see
 * {@link ImageryTileResolver}).  Their contents are pruned through the link,
 * while the link and its target directory, the volume's root, are kept.</p>
 */
final class ImageryPyramidPruner {
    private int deletedPngs;
    private int removedDirectories;
    private int keptFiles;
    private int failures;

    private ImageryPyramidPruner() { }

    static ImageryDeletionReport prune(Path tileDirectory, TileAddress address) {
        ImageryPyramidPruner pruner = new ImageryPyramidPruner();
        for (int child = 0; child < 4; child++) {
            pruner.pruneChild(tileDirectory.resolve(Integer.toString(child)));
        }
        // Children first: a run cut short still leaves this PNG, so the tile
        // remains visibly pending and pressing Delete again finishes the work.
        pruner.deleteTilePng(tileDirectory.resolve(address.quadkey() + ".png"));
        boolean ownsDirectory = address.level() > 0 && !Files.isSymbolicLink(tileDirectory);
        if (ownsDirectory) {
            pruner.removeIfEmpty(tileDirectory);
        }
        return new ImageryDeletionReport(pruner.deletedPngs, pruner.removedDirectories,
            pruner.keptFiles, pruner.failures);
    }

    private void pruneChild(Path child) {
        try {
            if (Files.isSymbolicLink(child)) {
                walk(child.toRealPath(), false);
            }
            else if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                walk(child, true);
            }
        }
        catch (IOException e) {
            failures++;
        }
    }

    /** Does not follow links, so a link found deeper down is only ever a kept file. */
    private void walk(Path start, boolean removeStart) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (isPng(file)) {
                    deletePng(file);
                }
                else {
                    keptFiles++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                failures++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException e) {
                if (e != null) {
                    failures++;
                }
                if (removeStart || !directory.equals(start)) {
                    removeIfEmpty(directory);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isPng(Path file) {
        return file.getFileName().toString().endsWith(".png");
    }

    private void deleteTilePng(Path png) {
        if (Files.exists(png, LinkOption.NOFOLLOW_LINKS)) {
            deletePng(png);
        }
    }

    private void deletePng(Path png) {
        try {
            Files.delete(png);
            deletedPngs++;
        }
        catch (IOException e) {
            failures++;
        }
    }

    private void removeIfEmpty(Path directory) {
        try {
            Files.delete(directory);
            removedDirectories++;
        }
        catch (DirectoryNotEmptyException e) {
            // Holds a kept file, or a PNG that failed; both are already counted.
        }
        catch (IOException e) {
            failures++;
        }
    }
}
