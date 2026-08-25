package dumpanalyzer.io.parser;

import dumpanalyzer.model.replay.ReplayDraw;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class ReplayDisplayListRegistry {
    private final ConcurrentHashMap<Integer, List<ReplayDraw>> drawsByListId = new ConcurrentHashMap<>();

    public void put(int listId, List<ReplayDraw> draws) {
        if (listId < 0 || draws == null || draws.isEmpty()) {
            return;
        }
        drawsByListId.put(listId, List.copyOf(draws));
    }

    public List<ReplayDraw> get(int listId) {
        List<ReplayDraw> draws = drawsByListId.get(listId);
        return draws == null ? List.of() : draws;
    }
}
