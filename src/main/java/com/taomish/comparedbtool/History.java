package com.taomish.comparedbtool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Recent runs, favourites and the last parameter values per rule, in out/.history.json. Never holds credentials. */
final class History {
    static final int KEEP = 200;
    private static final JsonMapper JSON = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    public static final class Run {
        public String rule, kind, mode, export, started, verdict, dir;
        public Map<String, String> params = new LinkedHashMap<>();
        public long limit;
        public int parallel, exit;
    }

    public static final class Data {
        public List<String> favorites = new ArrayList<>();
        public List<Run> runs = new ArrayList<>();
    }

    private final Path file;
    private Data data = new Data();

    History(Path file) {
        this.file = file;
        if (!Files.isRegularFile(file)) return;
        try {
            Data loaded = JSON.readValue(Files.readString(file), Data.class);
            if (loaded != null) data = loaded;
            if (data.favorites == null) data.favorites = new ArrayList<>();
            if (data.runs == null) data.runs = new ArrayList<>();
        } catch (Exception e) {
            try { Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException ignored) { /* start fresh either way */ }
            data = new Data();
        }
    }

    List<Run> runs() { return List.copyOf(data.runs); }
    Set<String> favorites() { return new LinkedHashSet<>(data.favorites); }
    boolean isFavorite(String rule) { return data.favorites.contains(rule); }

    boolean toggleFavorite(String rule) {
        boolean now = !data.favorites.remove(rule);
        if (now) data.favorites.add(rule);
        save();
        return now;
    }

    void add(Run run) {
        data.runs.add(run);
        while (data.runs.size() > KEEP) data.runs.removeFirst();
        save();
    }

    /** Newest first; the same rule + mode + parameters appears once. */
    List<Run> recent(int max) {
        List<Run> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = data.runs.size() - 1; i >= 0 && out.size() < max; i--) {
            Run r = data.runs.get(i);
            if (seen.add(r.rule + "|" + r.mode + "|" + r.params)) out.add(r);
        }
        return out;
    }

    /** Values of the most recent run of this rule that had any parameters. */
    Map<String, String> lastParams(String rule) {
        for (int i = data.runs.size() - 1; i >= 0; i--) {
            Run r = data.runs.get(i);
            if (rule.equals(r.rule) && r.params != null && !r.params.isEmpty()) return new LinkedHashMap<>(r.params);
        }
        return new LinkedHashMap<>();
    }

    private void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(data));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { /* history is a convenience: a read-only folder must not stop a run */ }
    }
}
