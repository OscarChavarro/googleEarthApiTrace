import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QtAccessService {
    private static final String ATSPI_BUS_DESTINATION = "org.a11y.Bus";
    private static final String ATSPI_BUS_PATH = "/org/a11y/bus";
    private static final String ATSPI_REGISTRY_DESTINATION = "org.a11y.atspi.Registry";
    private static final String ATSPI_ROOT_PATH = "/org/a11y/atspi/accessible/root";
    private static final String GOOGLE_EARTH_NAME = "Google Earth Pro";
    private static final String TURTLE_FOLDER_NAME = "turtle";
    // In Google Earth's Qt tree, the folder and child visibility checkboxes share
    // this column relative to the tree's left edge (as opposed to the row center).
    private static final int CHECKBOX_CENTER_OFFSET_X = 38;
    private static final int EXPANDED_STATE_BIT = 10;
    private static final int MAX_TRAVERSED_NODES = 1_000;
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(4);

    private static final Pattern QUOTED_VALUE_PATTERN = Pattern.compile("\\(<'(.*?)'>,\\)");
    private static final Pattern STRING_VALUE_PATTERN = Pattern.compile("\\('([^']*)',\\)");
    private static final Pattern DESTINATION_PATTERN = Pattern.compile("\\('(:[0-9.]+)'");
    private static final Pattern ACCESSIBLE_PATH_PATTERN = Pattern.compile(
        "(?:objectpath )?'(/org/a11y/atspi/accessible/[^']+)'"
    );
    private static final Pattern STATE_PATTERN = Pattern.compile("uint32 (\\d+)");
    private static final Pattern EXTENTS_PATTERN = Pattern.compile(
        "\\(\\((-?\\d+), (-?\\d+), (-?\\d+), (-?\\d+)\\),\\)"
    );
    private static final Pattern MARKER_NAME_PATTERN = Pattern.compile("(?:L\\d+|z\\d{4})");

    TurtlePreparation locateTurtlePreparation() {
        String busAddress = getAtSpiBusAddress();
        String destination = findGoogleEarthDestination(busAddress);
        AccessibleTree tree = findTurtleTreeItems(busAddress, destination);
        List<AccessibleNode> treeItems = tree.items();

        int turtleIndex = indexOfNamedNode(treeItems, TURTLE_FOLDER_NAME);
        AccessibleNode turtle = treeItems.get(turtleIndex);
        if (!isExpanded(busAddress, destination, turtle.path())) {
            throw new IllegalStateException("The turtle folder is not expanded in Google Earth");
        }

        List<AccessibleNode> turtleContents = treeItems.subList(turtleIndex + 1, treeItems.size());
        if (turtleContents.size() < 2) {
            throw new IllegalStateException("The turtle folder has fewer than two items");
        }

        AccessibleNode firstPoint = turtleContents.stream()
            .filter(node -> MARKER_NAME_PATTERN.matcher(node.name()).matches())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No point was found after the turtle folder"));

        Bounds treeBounds = getBounds(busAddress, destination, tree.path());
        int checkboxCenterX = treeBounds.x() + CHECKBOX_CENTER_OFFSET_X;
        if (checkboxCenterX >= treeBounds.x() + treeBounds.width()) {
            throw new IllegalStateException("The Google Earth places tree is too narrow for its checkboxes");
        }

        LocatedPoint turtleCheckbox = locateCheckbox(
            busAddress, destination, turtle, checkboxCenterX
        );
        Bounds firstPointBounds = getBounds(busAddress, destination, firstPoint.path());
        LocatedPoint firstNavigationPoint = new LocatedPoint(
            firstPoint.name(), firstPoint.path(),
            firstPointBounds.x() + firstPointBounds.width() / 2,
            firstPointBounds.y() + firstPointBounds.height() / 2
        );

        return new TurtlePreparation(
            turtleCheckbox,
            List.of(
                toAccessibleItem(turtleContents.get(0)),
                toAccessibleItem(turtleContents.get(1))
            ),
            firstNavigationPoint
        );
    }

    private AccessibleItem toAccessibleItem(AccessibleNode node) {
        return new AccessibleItem(node.name(), node.path());
    }

    private LocatedPoint locateCheckbox(
        String busAddress,
        String destination,
        AccessibleNode node,
        int checkboxCenterX
    ) {
        Bounds bounds = getBounds(busAddress, destination, node.path());
        return new LocatedPoint(
            node.name(), node.path(), checkboxCenterX, bounds.y() + bounds.height() / 2
        );
    }

    private Bounds getBounds(String busAddress, String destination, String path) {
        String output = callAtSpi(
            busAddress,
            destination,
            path,
            "org.a11y.atspi.Component.GetExtents",
            "0"
        );
        Matcher matcher = EXTENTS_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IllegalStateException("AT-SPI did not return coordinates for " + path);
        }
        Bounds bounds = new Bounds(
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3)),
            Integer.parseInt(matcher.group(4))
        );
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            throw new IllegalStateException("AT-SPI returned invalid coordinates for " + path);
        }
        return bounds;
    }

    private String getAtSpiBusAddress() {
        String output = runCommand(List.of(
            "gdbus", "call", "--session",
            "--dest", ATSPI_BUS_DESTINATION,
            "--object-path", ATSPI_BUS_PATH,
            "--method", "org.a11y.Bus.GetAddress"
        ));
        Matcher matcher = STRING_VALUE_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IllegalStateException("AT-SPI bus address was not available");
        }
        return matcher.group(1);
    }

    private String findGoogleEarthDestination(String busAddress) {
        String output = callAtSpi(
            busAddress,
            ATSPI_REGISTRY_DESTINATION,
            ATSPI_ROOT_PATH,
            "org.a11y.atspi.Accessible.GetChildren"
        );
        Matcher matcher = DESTINATION_PATTERN.matcher(output);
        while (matcher.find()) {
            String destination = matcher.group(1);
            try {
                if (GOOGLE_EARTH_NAME.equals(getName(busAddress, destination, ATSPI_ROOT_PATH))) {
                    return destination;
                }
            } catch (RuntimeException ignored) {
                // Applications can disappear from the registry while it is being inspected.
            }
        }
        throw new IllegalStateException("Google Earth is not publishing an AT-SPI interface");
    }

    private AccessibleTree findTurtleTreeItems(String busAddress, String destination) {
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(ATSPI_ROOT_PATH);

        while (!pending.isEmpty() && visited.size() < MAX_TRAVERSED_NODES) {
            String path = pending.removeFirst();
            if (!visited.add(path)) {
                continue;
            }

            List<String> children;
            try {
                children = getChildren(busAddress, destination, path);
                if ("tree".equals(getRoleName(busAddress, destination, path))) {
                    List<AccessibleNode> items = getNamedChildren(busAddress, destination, children);
                    if (indexOfNamedNodeOrMinusOne(items, TURTLE_FOLDER_NAME) >= 0) {
                        return new AccessibleTree(path, items);
                    }
                }
            } catch (RuntimeException ignored) {
                continue;
            }
            pending.addAll(children);
        }
        throw new IllegalStateException("The turtle tree was not found in Google Earth AT-SPI");
    }

    private List<AccessibleNode> getNamedChildren(
        String busAddress,
        String destination,
        List<String> children
    ) {
        List<AccessibleNode> nodes = new ArrayList<>();
        for (String child : children) {
            try {
                nodes.add(new AccessibleNode(child, getName(busAddress, destination, child)));
            } catch (RuntimeException ignored) {
                // Keep inspecting the remaining live items if the Qt model changes mid-read.
            }
        }
        return nodes;
    }

    private String getName(String busAddress, String destination, String path) {
        String output = callAtSpi(
            busAddress,
            destination,
            path,
            "org.freedesktop.DBus.Properties.Get",
            "org.a11y.atspi.Accessible",
            "Name"
        );
        Matcher matcher = QUOTED_VALUE_PATTERN.matcher(output);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String getRoleName(String busAddress, String destination, String path) {
        String output = callAtSpi(
            busAddress,
            destination,
            path,
            "org.a11y.atspi.Accessible.GetRoleName"
        );
        Matcher matcher = STRING_VALUE_PATTERN.matcher(output);
        return matcher.find() ? matcher.group(1) : "";
    }

    private List<String> getChildren(String busAddress, String destination, String path) {
        String output = callAtSpi(
            busAddress,
            destination,
            path,
            "org.a11y.atspi.Accessible.GetChildren"
        );
        List<String> children = new ArrayList<>();
        Matcher matcher = ACCESSIBLE_PATH_PATTERN.matcher(output);
        while (matcher.find()) {
            children.add(matcher.group(1));
        }
        return children;
    }

    private boolean isExpanded(String busAddress, String destination, String path) {
        String output = callAtSpi(
            busAddress,
            destination,
            path,
            "org.a11y.atspi.Accessible.GetState"
        );
        Matcher matcher = STATE_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IllegalStateException("Could not read the turtle folder state");
        }
        long firstStateWord = Long.parseLong(matcher.group(1));
        return (firstStateWord & (1L << EXPANDED_STATE_BIT)) != 0;
    }

    private String callAtSpi(
        String busAddress,
        String destination,
        String path,
        String method,
        String... arguments
    ) {
        List<String> command = new ArrayList<>(List.of(
            "gdbus", "call",
            "--address", busAddress,
            "--dest", destination,
            "--object-path", path,
            "--method", method
        ));
        command.addAll(List.of(arguments));
        return runCommand(command);
    }

    private String runCommand(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean completed = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("Command timed out: " + command.get(0));
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                throw new IllegalStateException(output.isBlank() ? "AT-SPI command failed" : output);
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AT-SPI inspection was interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Could not execute gdbus", e);
        }
    }

    private int indexOfNamedNode(List<AccessibleNode> nodes, String name) {
        int index = indexOfNamedNodeOrMinusOne(nodes, name);
        if (index < 0) {
            throw new IllegalStateException(name + " was not found in the Google Earth tree");
        }
        return index;
    }

    private int indexOfNamedNodeOrMinusOne(List<AccessibleNode> nodes, String name) {
        for (int index = 0; index < nodes.size(); index++) {
            if (name.equals(nodes.get(index).name())) {
                return index;
            }
        }
        return -1;
    }

    record LocatedPoint(String name, String accessiblePath, int centerX, int centerY) {
    }

    record TurtlePreparation(
        LocatedPoint turtleCheckbox,
        List<AccessibleItem> initialItems,
        LocatedPoint firstNavigationPoint
    ) {
    }

    record AccessibleItem(String name, String accessiblePath) {
    }

    private record AccessibleTree(String path, List<AccessibleNode> items) {
    }

    private record AccessibleNode(String path, String name) {
    }

    private record Bounds(int x, int y, int width, int height) {
    }
}
