package com.taomish.comparedbtool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What the interactive menu needs from a terminal: pick from a list, ask for text, print. */
interface Ui {
    /** {@code id} starting with "/" marks a command; the filter treats those separately. */
    record Item(String id, String label, String detail) {
        static Item of(String id, String label) { return new Item(id, label, ""); }
    }

    /** Returns the id of the chosen item, or null when the user cancelled (Esc, empty input, end of input). */
    String pick(String title, List<Item> items, String hint);

    /** Returns the text entered (the initial text can be edited), or null when cancelled. */
    String ask(String prompt, String initial);

    void print(String text);

    default boolean confirm(String question, boolean defaultYes) {
        List<Item> items = defaultYes ? List.of(Item.of("y", "Yes"), Item.of("n", "No")) : List.of(Item.of("n", "No"), Item.of("y", "Yes"));
        return "y".equals(pick(question, items, "Enter selects, Esc = no"));
    }

    /** Typing is the confirmation: used for production and for writes, where a stray Enter must not be enough. */
    default boolean typed(String message, String expected) {
        print(message);
        String answer = ask("Type " + expected + " to continue (anything else cancels): ", "");
        return answer != null && answer.strip().equals(expected);
    }

    /** Filter-as-you-type: a leading "/" lists commands; otherwise every word must occur in the label or detail. */
    static List<Item> filter(List<Item> items, String query) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<Item> out = new ArrayList<>();
        if (q.isEmpty()) return new ArrayList<>(items);
        if (q.startsWith("/")) {
            for (Item item : items) if (item.id().startsWith("/") && item.id().toLowerCase(Locale.ROOT).startsWith(q)) out.add(item);
            if (out.isEmpty()) for (Item item : items) if (item.id().startsWith("/") && item.id().toLowerCase(Locale.ROOT).contains(q.substring(1))) out.add(item);
            return out;
        }
        String[] words = q.split("\\s+");
        outer:
        for (Item item : items) {
            String hay = (item.label() + " " + item.detail()).toLowerCase(Locale.ROOT);
            for (String word : words) if (!hay.contains(word)) continue outer;
            out.add(item);
        }
        return out;
    }
}
