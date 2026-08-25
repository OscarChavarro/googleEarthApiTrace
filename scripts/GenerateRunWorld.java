import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

public final class GenerateRunWorld {
    private static final int MASK_WIDTH = 320;
    private static final int MASK_HEIGHT = 180;
    private static final int BLOCK_SIZE_DEGREES = 8;
    private static final int MAX_TILE_LEVEL = 5;
    private static final int MIN_LATITUDE = -60;
    private static final int MAX_LATITUDE = 60;
    private static final Path TOPLEVEL_DIRECTORY = Path.of("/samples/datasets/googleEarth/toplevel");
    private static final Path SCRIPT_OUTPUT = Path.of("scripts/runWorld.sh");
    private static final Path MATRIX_OUTPUT = Path.of("scripts/worldCoverage320x180.txt");
    private static final Path PREVIEW_OUTPUT = Path.of("scripts/worldCoveragePreview.png");
    private static final Path MASK_PREVIEW_OUTPUT = Path.of("scripts/worldCoverageMask320x180.png");

    private record Tile(Path path, String quadkey, int level, int row, int col) {}

    private record Block(int lowerLeftLat, int lowerLeftLon, int trueCount) {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        List<Tile> tiles = loadTiles();
        BufferedImage world = rasterizeWorld(tiles);
        boolean[][] mask = classifyMask(world);
        List<Block> blocks = rankBlocks(mask);
        writePreview(world, PREVIEW_OUTPUT);
        writeMaskPreview(mask, MASK_PREVIEW_OUTPUT);
        writeMatrix(mask);
        writeRunWorld(blocks);
        System.out.printf(
            Locale.ROOT,
            "Generated %s, %s, %s and %s using %d tiles.%n",
            SCRIPT_OUTPUT,
            MATRIX_OUTPUT,
            PREVIEW_OUTPUT,
            MASK_PREVIEW_OUTPUT,
            tiles.size()
        );
    }

    private static List<Tile> loadTiles() throws IOException {
        List<Tile> tiles = new ArrayList<>();
        try (var stream = Files.walk(TOPLEVEL_DIRECTORY, MAX_TILE_LEVEL + 1)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".png"))
                .forEach(path -> tiles.add(toTile(path)));
        }
        tiles.sort(Comparator.comparingInt(Tile::level));
        return tiles;
    }

    private static Tile toTile(Path path) {
        String fileName = path.getFileName().toString();
        String quadkey = fileName.substring(0, fileName.length() - 4);
        int[] rowCol = decodeQuadkey(quadkey);
        return new Tile(path, quadkey, quadkey.length() - 1, rowCol[0], rowCol[1]);
    }

    private static int[] decodeQuadkey(String quadkey) {
        if (quadkey == null || quadkey.isEmpty() || quadkey.charAt(0) != '0') {
            throw new IllegalArgumentException("Invalid quadkey: " + quadkey);
        }
        int row = 0;
        int col = 0;
        for (int i = 1; i < quadkey.length(); i++) {
            int quadrant = quadkey.charAt(i) - '0';
            boolean south = quadrant == 0 || quadrant == 1;
            boolean east = quadrant == 1 || quadrant == 2;
            row = (row << 1) | (south ? 1 : 0);
            col = (col << 1) | (east ? 1 : 0);
        }
        return new int[]{row, col};
    }

    private static BufferedImage rasterizeWorld(List<Tile> tiles) throws IOException {
        BufferedImage world = new BufferedImage(MASK_WIDTH, MASK_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = world.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        try {
            graphics.setColor(new Color(70, 110, 180));
            graphics.fillRect(0, 0, MASK_WIDTH, MASK_HEIGHT);
            for (Tile tile : tiles) {
                drawTile(graphics, tile);
            }
        }
        finally {
            graphics.dispose();
        }
        return world;
    }

    private static void drawTile(Graphics2D graphics, Tile tile) throws IOException {
        BufferedImage image = ImageIO.read(tile.path().toFile());
        if (image == null) {
            throw new IOException("Cannot decode image: " + tile.path());
        }
        int cellsPerAxis = 1 << tile.level();
        double west = -180.0 + tile.col() * (360.0 / cellsPerAxis);
        double east = -180.0 + (tile.col() + 1) * (360.0 / cellsPerAxis);
        double north = 90.0 - tile.row() * (180.0 / cellsPerAxis);
        double south = 90.0 - (tile.row() + 1) * (180.0 / cellsPerAxis);

        int x0 = projectLongitude(west);
        int x1 = projectLongitude(east);
        int y0 = projectLatitude(north);
        int y1 = projectLatitude(south);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        graphics.drawImage(image, x0, y0, x1, y1, 0, 0, image.getWidth(), image.getHeight(), null);
    }

    private static int projectLongitude(double longitude) {
        return clamp((int) Math.round((longitude + 180.0) * MASK_WIDTH / 360.0), 0, MASK_WIDTH);
    }

    private static int projectLatitude(double latitude) {
        return clamp((int) Math.round((90.0 - latitude) * MASK_HEIGHT / 180.0), 0, MASK_HEIGHT);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean[][] classifyMask(BufferedImage world) {
        boolean[][] mask = new boolean[MASK_HEIGHT][MASK_WIDTH];
        for (int y = 0; y < MASK_HEIGHT; y++) {
            double latitude = 90.0 - ((y + 0.5) * 180.0 / MASK_HEIGHT);
            for (int x = 0; x < MASK_WIDTH; x++) {
                if (latitude < MIN_LATITUDE || latitude >= MAX_LATITUDE) {
                    mask[y][x] = false;
                    continue;
                }
                int argb = world.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xff;
                int r = (argb >>> 16) & 0xff;
                int g = (argb >>> 8) & 0xff;
                int b = argb & 0xff;
                mask[y][x] = alpha >= 16 && isLandLike(r, g, b);
            }
        }
        return mask;
    }

    private static boolean isLandLike(int r, int g, int b) {
        float[] hsv = java.awt.Color.RGBtoHSB(r, g, b, null);
        float hue = hsv[0] * 360.0f;
        float saturation = hsv[1];
        float brightness = hsv[2];

        if (brightness >= 0.72f && saturation <= 0.18f) {
            return false;
        }
        if (hue >= 170.0f && hue <= 260.0f && saturation >= 0.18f) {
            return false;
        }
        if (hue >= 40.0f && hue <= 170.0f && saturation >= 0.14f) {
            return true;
        }

        int blueDistance = squaredDistance(r, g, b, 70, 110, 180);
        int whiteDistance = squaredDistance(r, g, b, 235, 235, 235);
        int greenDistance = squaredDistance(r, g, b, 95, 135, 70);
        int yellowDistance = squaredDistance(r, g, b, 185, 165, 80);
        int falseDistance = Math.min(blueDistance, whiteDistance);
        int trueDistance = Math.min(greenDistance, yellowDistance);
        return trueDistance < falseDistance;
    }

    private static int squaredDistance(int r, int g, int b, int pr, int pg, int pb) {
        int dr = r - pr;
        int dg = g - pg;
        int db = b - pb;
        return dr * dr + dg * dg + db * db;
    }

    private static List<Block> rankBlocks(boolean[][] mask) {
        List<Block> blocks = new ArrayList<>();
        for (int lowerLeftLat = MIN_LATITUDE; lowerLeftLat <= MAX_LATITUDE - BLOCK_SIZE_DEGREES; lowerLeftLat += BLOCK_SIZE_DEGREES) {
            for (int lowerLeftLon = -180; lowerLeftLon <= 180 - BLOCK_SIZE_DEGREES; lowerLeftLon += BLOCK_SIZE_DEGREES) {
                blocks.add(new Block(lowerLeftLat, lowerLeftLon, countTrues(mask, lowerLeftLat, lowerLeftLon)));
            }
        }
        blocks.sort(
            Comparator.comparingInt(Block::trueCount).reversed()
                .thenComparingInt(Block::lowerLeftLon)
                .thenComparing(Comparator.comparingInt(Block::lowerLeftLat).reversed())
        );
        return blocks;
    }

    private static int countTrues(boolean[][] mask, int lowerLeftLat, int lowerLeftLon) {
        int count = 0;
        for (int y = 0; y < MASK_HEIGHT; y++) {
            double latitude = 90.0 - ((y + 0.5) * 180.0 / MASK_HEIGHT);
            if (latitude < lowerLeftLat || latitude >= lowerLeftLat + BLOCK_SIZE_DEGREES) {
                continue;
            }
            for (int x = 0; x < MASK_WIDTH; x++) {
                double longitude = -180.0 + ((x + 0.5) * 360.0 / MASK_WIDTH);
                if (longitude >= lowerLeftLon && longitude < lowerLeftLon + BLOCK_SIZE_DEGREES && mask[y][x]) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void writeMatrix(boolean[][] mask) throws IOException {
        Files.createDirectories(MATRIX_OUTPUT.getParent());
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(MATRIX_OUTPUT, StandardCharsets.UTF_8))) {
            writer.printf("width=%d height=%d%n", MASK_WIDTH, MASK_HEIGHT);
            for (int y = 0; y < MASK_HEIGHT; y++) {
                for (int x = 0; x < MASK_WIDTH; x++) {
                    writer.print(mask[y][x] ? '1' : '0');
                }
                writer.println();
            }
        }
    }

    private static void writePreview(BufferedImage image, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        ImageIO.write(image, "png", output.toFile());
    }

    private static void writeMaskPreview(boolean[][] mask, Path output) throws IOException {
        BufferedImage image = new BufferedImage(MASK_WIDTH, MASK_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < MASK_HEIGHT; y++) {
            for (int x = 0; x < MASK_WIDTH; x++) {
                image.setRGB(x, y, mask[y][x] ? 0xff2f7d32 : 0xff1565c0);
            }
        }
        writePreview(image, output);
    }

    private static void writeRunWorld(List<Block> blocks) throws IOException {
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(SCRIPT_OUTPUT, StandardCharsets.UTF_8))) {
            writer.println("#!/usr/bin/env bash");
            writer.println();
            writer.println("set -Eeuo pipefail");
            writer.println();
            writer.println("readonly PROJECT_DIR=\"/paradigmas/master/algoritmos_basicos_3d/googleEarthApiTrace\"");
            writer.println("readonly PATH_PLANNER_DIR=\"$PROJECT_DIR/11_pathPlanner\"");
            writer.println("readonly MY_PLACES_DIR=\"$HOME/.googleearth\"");
            writer.println();
            writer.println("# Generated by scripts/GenerateRunWorld.java.");
            writer.println("# Blocks are ordered by descending land-like coverage on a 320x180 world mask.");
            writer.println("# Classification only uses quadtree levels 0..5 from /samples/datasets/googleEarth/toplevel.");
            writer.println();
            for (Block block : blocks) {
                writer.printf(
                    Locale.ROOT,
                    "# trueCount=%d lat=[%d,%d) lon=[%d,%d)%n",
                    block.trueCount(),
                    block.lowerLeftLat(),
                    block.lowerLeftLat() + BLOCK_SIZE_DEGREES,
                    block.lowerLeftLon(),
                    block.lowerLeftLon() + BLOCK_SIZE_DEGREES
                );
                writer.println("cp -- \"$MY_PLACES_DIR/myplaces.kml.bak\" \"$MY_PLACES_DIR/myplaces.kml\"");
                writer.println();
                writer.println("cd \"$PATH_PLANNER_DIR\"");
                writer.printf(
                    Locale.ROOT,
                    "./run.sh zigzag %d %d 12000 400 12000 %d %d%n",
                    block.lowerLeftLat(),
                    block.lowerLeftLon(),
                    BLOCK_SIZE_DEGREES,
                    BLOCK_SIZE_DEGREES
                );
                writer.println();
                writer.println("cd \"$PROJECT_DIR\"");
                writer.printf(
                    Locale.ROOT,
                    "./scripts/runFullProcess.sh \\\n    --route-command \"./run.sh zigzag %d %d 12000 400 12000 %d %d\"%n",
                    block.lowerLeftLat(),
                    block.lowerLeftLon(),
                    BLOCK_SIZE_DEGREES,
                    BLOCK_SIZE_DEGREES
                );
                writer.println();
            }
        }
        SCRIPT_OUTPUT.toFile().setExecutable(true, false);
    }
}
