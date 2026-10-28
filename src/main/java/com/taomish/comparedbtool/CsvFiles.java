package com.taomish.comparedbtool;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.commons.csv.*;

public final class CsvFiles {
    private CsvFiles() {}
    // Quoted literal "\N" differs from an unquoted NULL token; empty string stays empty string.
    public static final CSVFormat FORMAT = CSVFormat.RFC4180.builder()
        .setNullString("\\N").setQuoteMode(QuoteMode.ALL_NON_NULL).get();
    public static CSVPrinter writer(Path path, List<String> header) throws IOException {
        Files.createDirectories(path.getParent());
        CSVPrinter printer = new CSVPrinter(Files.newBufferedWriter(path, StandardCharsets.UTF_8), FORMAT);
        if (header != null) printer.printRecord(header);
        return printer;
    }
    public static CSVParser reader(Path path, boolean header) throws IOException {
        CSVFormat format = header ? FORMAT.builder().setHeader().setSkipHeaderRecord(true).get() : FORMAT;
        return format.parse(Files.newBufferedReader(path, StandardCharsets.UTF_8));
    }
    public static List<String> values(CSVRecord record) {
        List<String> result = new ArrayList<>(record.size());
        record.forEach(result::add);
        return result;
    }
    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
