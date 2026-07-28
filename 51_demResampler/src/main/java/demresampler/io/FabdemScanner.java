package demresampler.io;

import demresampler.model.TileAddress;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class FabdemScanner {
    private static final Pattern FILE_PATTERN =
        Pattern.compile("([NS])(\\d{2})([EW])(\\d{3})_FABDEM_V1-2\\.tif");

    private FabdemScanner() {
    }

    public static List<FabdemSourceTile> scan(Path inputRoot) throws IOException {
        List<FabdemSourceTile> result = new ArrayList<>();
        Map<Long, Path> coordinates = new HashMap<>();
        try (Stream<Path> paths = Files.walk(inputRoot)) {
            paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".tif"))
                .forEach(path -> {
                    FabdemSourceTile tile = parse(path);
                    long key = TileAddress.pack(tile.southLatitude(), tile.westLongitude());
                    Path previous = coordinates.putIfAbsent(key, path);
                    if (previous != null) {
                        throw new IllegalArgumentException(
                            "Duplicate FABDEM coordinate: " + previous + " and " + path);
                    }
                    result.add(tile);
                });
        }
        result.sort(Comparator.comparing(tile -> tile.path().toString()));
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No FABDEM V1.2 .tif files found below " + inputRoot);
        }
        return List.copyOf(result);
    }

    public static Set<Long> candidateTiles(List<FabdemSourceTile> sources, int level) {
        int side = 1 << level;
        double tileDegrees = 360.0 / side;
        Set<Long> result = new HashSet<>();
        for (FabdemSourceTile source : sources) {
            int firstColumn = clamp(
                floorIndex((source.westLongitude() + 180.0) / tileDegrees), 0, side - 1);
            int lastColumn = clamp(
                ceilIndex((source.eastLongitude() + 180.0) / tileDegrees) - 1, 0, side - 1);
            int firstRow = clamp(
                floorIndex((180.0 - source.northLatitude()) / tileDegrees), 0, side - 1);
            int lastRow = clamp(
                ceilIndex((180.0 - source.southLatitude()) / tileDegrees) - 1, 0, side - 1);
            for (int row = firstRow; row <= lastRow; row++) {
                for (int column = firstColumn; column <= lastColumn; column++) {
                    result.add(TileAddress.pack(row, column));
                }
            }
        }
        return result;
    }

    private static FabdemSourceTile parse(Path path) {
        Matcher matcher = FILE_PATTERN.matcher(path.getFileName().toString());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unexpected FABDEM filename: " + path);
        }
        int latitude = Integer.parseInt(matcher.group(2));
        if (matcher.group(1).equals("S")) {
            latitude = -latitude;
        }
        int longitude = Integer.parseInt(matcher.group(4));
        if (matcher.group(3).equals("W")) {
            longitude = -longitude;
        }
        return new FabdemSourceTile(path.toAbsolutePath(), latitude, longitude);
    }

    private static int floorIndex(double value) {
        return (int) Math.floor(value + 1e-12);
    }

    private static int ceilIndex(double value) {
        return (int) Math.ceil(value - 1e-12);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
