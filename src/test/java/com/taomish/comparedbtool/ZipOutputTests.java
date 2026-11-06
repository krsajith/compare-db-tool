package com.taomish.comparedbtool;

import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ZipOutputTests {
    @TempDir Path temp;

    @Test void zipsTheResultFolderWithoutItself() throws Exception {
        Files.writeString(temp.resolve("LOAD.md"), "summary");
        Files.writeString(temp.resolve("mult.csv"), "a,b\n1,2\n");
        Files.writeString(temp.resolve("undo-x.json"), "{}");
        Path first = Cli.zipOutput(temp, "load-x");
        Path second = Cli.zipOutput(temp, "load-x");
        assertEquals(first, second);
        try (var zip = new ZipFile(second.toFile())) {
            var names = new TreeSet<String>();
            zip.stream().forEach(e -> names.add(e.getName()));
            assertEquals(new TreeSet<>(List.of("LOAD.md", "mult.csv", "undo-x.json")), names);
        }
        assertFalse(Files.exists(temp.resolve("load-x.zip.partial")));
    }
}
