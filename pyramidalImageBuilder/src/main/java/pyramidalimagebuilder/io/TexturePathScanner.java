package pyramidalimagebuilder.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import pyramidalimagebuilder.model.PyramidalImageModel;

public final class TexturePathScanner {
    private static final Pattern TEXTURE_NAME_PATTERN =
        Pattern.compile(".*_(\\d+)\\.(dds|png)$", Pattern.CASE_INSENSITIVE);

    public void scanRecursive(Path outputRoot, PyramidalImageModel model) {
        if (outputRoot == null || model == null || !Files.isDirectory(outputRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(outputRoot)) {
            paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".png"))
                .forEach(path -> register(path, model));
        }
        catch (IOException ignored) {
        }
    }

    private static void register(Path path, PyramidalImageModel model) {
        String name = path.getFileName().toString();
        Matcher matcher = TEXTURE_NAME_PATTERN.matcher(name);
        if (!matcher.matches()) {
            return;
        }
        Path parent = path.getParent();
        if (parent == null || parent.getFileName() == null) {
            return;
        }

        int frameId;
        try {
            frameId = Integer.parseInt(parent.getFileName().toString());
        }
        catch (NumberFormatException ex) {
            return;
        }

        int textureId = Integer.parseInt(matcher.group(1));
        model.registerTexturePath(frameId, textureId, path.toAbsolutePath().toString());
    }
}
