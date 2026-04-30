package dumpanalyzer.parser;

import java.util.LinkedHashMap;
import java.util.Map;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

public class FunctionCounter {
    private final Map<String, Integer> functionCounts = new LinkedHashMap<>();

    public void addFromContent(String normalizedContent) {
        Map<String, Integer> localCounts = new LinkedHashMap<>();

        CommonTokenStream tokenStream = new CommonTokenStream(new GlTraceLexer(CharStreams.fromString(normalizedContent)));
        tokenStream.fill();

        for (Token token : tokenStream.getTokens()) {
            if (token.getType() == GlTraceLexer.GL_FUNCTION) {
                localCounts.merge(token.getText(), 1, Integer::sum);
            }
        }

        mergeLocalCounts(localCounts);
    }

    private synchronized void mergeLocalCounts(Map<String, Integer> localCounts) {
        for (Map.Entry<String, Integer> entry : localCounts.entrySet()) {
            functionCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }
    }

    public synchronized void printSorted() {
        functionCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> System.out.println(entry.getKey() + " => " + entry.getValue()));
    }
}
