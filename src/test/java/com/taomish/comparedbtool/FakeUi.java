package com.taomish.comparedbtool;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Scripted answers: a String is the answer (an item id for pick), null cancels. A pick answer that is not on screen fails the test. */
final class FakeUi implements Ui {
    final Deque<String> script = new ArrayDeque<>();
    final List<String> printed = new ArrayList<>(), titles = new ArrayList<>(), prompts = new ArrayList<>();
    final List<List<Item>> screens = new ArrayList<>();
    boolean slowOnApply;

    FakeUi say(String... answers) {
        if (answers == null) { script.add("\0cancel"); return this; }
        for (String a : answers) script.add(a == null ? "\0cancel" : a); return this; }

    private String next(String what) {
        if (script.isEmpty()) throw new AssertionError("Script ran out at: " + what + "\nPrinted so far:\n" + String.join("\n", printed));
        String a = script.poll();
        return a.equals("\0cancel") ? null : a;
    }

    @Override public String pick(String title, List<Item> items, String hint) {
        titles.add(title); screens.add(items);
        String answer = next("pick " + title);
        if (answer != null && items.stream().noneMatch(i -> i.id().equals(answer)))
            throw new AssertionError("Answer '" + answer + "' is not on screen '" + title + "': " + items.stream().map(Item::id).toList());
        return answer;
    }

    @Override public String ask(String prompt, String initial) {
        prompts.add(prompt + "[" + initial + "]");
        if (slowOnApply && prompt.contains("APPLY")) {
            try { Thread.sleep(1100); } catch (InterruptedException ignored) { /* H2 does not roll back backup-table DDL: keep stamps apart */ }
        }
        return next("ask " + prompt);
    }

    @Override public void print(String text) { printed.add(text); }

    String output() { return String.join("\n", printed); }
}
