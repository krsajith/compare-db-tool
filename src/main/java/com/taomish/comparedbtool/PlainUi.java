package com.taomish.comparedbtool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

/** Numbered prompts for terminals without cursor control (pipes, plain consoles). */
final class PlainUi implements Ui {
    private final BufferedReader in;
    private final PrintStream out;

    PlainUi(BufferedReader in, PrintStream out) { this.in = in; this.out = out; }

    private String line() {
        try { return in.readLine(); } catch (IOException e) { return null; }
    }

    @Override public String pick(String title, List<Item> items, String hint) {
        List<Item> shown = items;
        while (true) {
            out.println();
            out.println(title);
            int limit = Math.min(shown.size(), 30);
            for (int i = 0; i < limit; i++) {
                Item item = shown.get(i);
                out.println(String.format("  %2d) %s%s", i + 1, item.label(), item.detail().isEmpty() ? "" : "   " + item.detail()));
            }
            if (shown.size() > limit) out.println("  ... " + (shown.size() - limit) + " more: type text to narrow the list");
            out.print("Number, text to filter, /command, or Enter to go back: ");
            out.flush();
            String answer = line();
            if (answer == null || answer.isBlank()) return null;
            answer = answer.strip();
            if (answer.matches("\\d+")) {
                int n = Integer.parseInt(answer);
                if (n >= 1 && n <= limit) return shown.get(n - 1).id();
                out.println("No item " + n + ".");
                continue;
            }
            List<Item> matches = Ui.filter(items, answer);
            if (matches.size() == 1) return matches.getFirst().id();
            if (matches.isEmpty()) { out.println("Nothing matches \"" + answer + "\"."); shown = items; }
            else shown = matches;
        }
    }

    @Override public String ask(String prompt, String initial) {
        out.print(prompt + (initial == null || initial.isEmpty() ? "" : "[" + initial + "] "));
        out.flush();
        String answer = line();
        if (answer == null) return null;
        return answer.isBlank() ? (initial == null ? "" : initial) : answer.strip();
    }

    @Override public void print(String text) { out.println(text); out.flush(); }
}
