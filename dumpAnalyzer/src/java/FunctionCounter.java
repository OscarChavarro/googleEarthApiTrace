package dumpanalyzer;

import java.util.LinkedHashMap;
import java.util.Map;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

public class FunctionCounter {
    private final Map<String, Integer> functionCounts = new LinkedHashMap<>();

    public void addFromContent(String normalizedContent) {
        CommonTokenStream tokenStream = new CommonTokenStream(new GlTraceLexer(CharStreams.fromString(normalizedContent)));
        tokenStream.fill();

        for (Token token : tokenStream.getTokens()) {
            if (token.getType() == GlTraceLexer.GL_FUNCTION) {
                functionCounts.merge(token.getText(), 1, Integer::sum);
            }
        }
    }

    public void printSorted() {
        functionCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> System.out.println(entry.getKey() + " => " + entry.getValue()));
    }
}
