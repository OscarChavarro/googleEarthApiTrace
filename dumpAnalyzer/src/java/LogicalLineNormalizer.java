package dumpanalyzer;

import java.util.ArrayList;
import java.util.List;

public final class LogicalLineNormalizer {
    private LogicalLineNormalizer() {
    }

    public static String normalize(String rawContent) {
        String[] lines = rawContent.split("\\R", -1);
        List<String> logicalLines = new ArrayList<>();

        StringBuilder current = new StringBuilder();
        int parenDepth = 0;
        int braceDepth = 0;
        boolean inString = false;
        boolean escaping = false;
        boolean accumulating = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (!accumulating) {
                if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                    logicalLines.add(line);
                    continue;
                }

                current.setLength(0);
                current.append(line);
                State state = updateState(line, parenDepth, braceDepth, inString, escaping);
                parenDepth = state.parenDepth;
                braceDepth = state.braceDepth;
                inString = state.inString;
                escaping = state.escaping;
                accumulating = true;
            } else {
                current.append("\\n").append(line);
                State state = updateState(line, parenDepth, braceDepth, inString, escaping);
                parenDepth = state.parenDepth;
                braceDepth = state.braceDepth;
                inString = state.inString;
                escaping = state.escaping;
            }

            if (parenDepth <= 0 && braceDepth <= 0 && !inString) {
                logicalLines.add(current.toString());
                accumulating = false;
                parenDepth = 0;
                braceDepth = 0;
                inString = false;
                escaping = false;
            }
        }

        if (accumulating) {
            logicalLines.add(current.toString());
        }

        return String.join("\n", logicalLines);
    }

    private static State updateState(String line, int parenDepth, int braceDepth, boolean inString, boolean escaping) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (escaping) {
                escaping = false;
                continue;
            }

            if (inString && c == '\\') {
                escaping = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (inString) {
                continue;
            }

            if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                parenDepth--;
            } else if (c == '{') {
                braceDepth++;
            } else if (c == '}') {
                braceDepth--;
            }
        }

        return new State(parenDepth, braceDepth, inString, escaping);
    }

    private record State(int parenDepth, int braceDepth, boolean inString, boolean escaping) {
    }
}
