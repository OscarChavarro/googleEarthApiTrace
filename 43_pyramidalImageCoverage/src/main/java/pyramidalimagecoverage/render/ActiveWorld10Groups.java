package pyramidalimagecoverage.render;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the controller-published, reviewed world10 queue; no shelling out. */
final class ActiveWorld10Groups {
    record Group(double latitude, double longitude, double latitudeSpan, double longitudeSpan) {}

    private static final Path PLAN_FILE = resolvePlanFile();
    private static final Pattern JOB = Pattern.compile("\\\"jobId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern LATITUDE = number("latitude");
    private static final Pattern LONGITUDE = number("longitude");
    private static final Pattern LATITUDE_SPAN = number("latitudeSpan");
    private static final Pattern LONGITUDE_SPAN = number("longitudeSpan");

    private ActiveWorld10Groups() {}

    static List<Group> read() {
        List<Group> groups = new ArrayList<>();
        try {
            for (String json : java.nio.file.Files.readAllLines(PLAN_FILE)) {
                Matcher job = JOB.matcher(json);
                if (!job.find() || !job.group(1).startsWith("world10-")) {
                    continue;
                }
                Double latitude = value(LATITUDE, json);
                Double longitude = value(LONGITUDE, json);
                Double latitudeSpan = value(LATITUDE_SPAN, json);
                Double longitudeSpan = value(LONGITUDE_SPAN, json);
                if (latitude != null && longitude != null && latitudeSpan != null && longitudeSpan != null) {
                    groups.add(new Group(latitude, longitude, latitudeSpan, longitudeSpan));
                }
            }
        }
        catch (IOException ignored) {
            // The plan is published atomically when a reviewed batch is started.
        }
        return groups;
    }

    private static Path resolvePlanFile() {
        String configured = System.getProperty("pyramidalimagecoverage.world10Plan");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path siblingScripts = cwd.resolve("../scripts/world10PlannedGroups.jsonl").normalize();
        return java.nio.file.Files.exists(siblingScripts)
            ? siblingScripts
            : cwd.resolve("scripts/world10PlannedGroups.jsonl").normalize();
    }

    private static Pattern number(String name) {
        return Pattern.compile("\\\"" + name + "\\\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)");
    }

    private static Double value(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? Double.valueOf(matcher.group(1)) : null;
    }
}
