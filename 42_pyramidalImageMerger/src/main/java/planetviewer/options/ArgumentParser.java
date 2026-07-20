package planetviewer.options;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ArgumentParser {
    private ArgumentParser() {
    }

    public static Optional<CliArguments> parse(String[] args) {
        if (args == null) {
            return Optional.empty();
        }
        boolean offline = false;
        boolean dryRun = false;
        List<String> folders = new ArrayList<>();
        for (String arg : args) {
            if ("--offline".equals(arg) || "--ofline".equals(arg)) {
                offline = true;
            }
            else if ("--dry-run".equals(arg)) {
                dryRun = true;
                offline = true;
            }
            else if (arg.startsWith("--")) {
                return Optional.empty();
            }
            else {
                folders.add(arg);
            }
        }
        if (folders.size() != 2) {
            return Optional.empty();
        }
        return Optional.of(new CliArguments(folders, offline, dryRun));
    }

    public static void printUsage() {
        System.err.println(
            "Usage: gradle run --args=\"[--offline|--dry-run] <destinationPyramidalImageFolder> <deltaPyramidalImageFolder>\""
        );
        System.err.println(
            "  destinationPyramidalImageFolder: root folder of the destination pyramidal image tree."
        );
        System.err.println(
            "  deltaPyramidalImageFolder: root folder of the delta pyramidal image tree to validate for merge."
        );
        System.err.println(
            "  --offline: run the same validation/merge operation as the interactive m key without opening JOGL."
        );
        System.err.println(
            "  --dry-run: validate and print conflict details without copying or replacing destination tiles."
        );
    }
}
