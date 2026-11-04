package com.taomish.comparedbtool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jline.keymap.BindingReader;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.jline.utils.Display;
import org.jline.utils.InfoCmp.Capability;

/** Arrow-key selection with filter-as-you-type, and "/" to list commands. Falls back to PlainUi when there is no real terminal. */
final class JLineUi implements Ui, AutoCloseable {
    private enum Op { UP, DOWN, PAGE_UP, PAGE_DOWN, HOME, END, ENTER, ESC, BACKSPACE, CLEAR, INTERRUPT, CHAR }

    private final Terminal terminal;
    private final LineReader lineReader;
    private final boolean color = System.getenv("NO_COLOR") == null;

    private JLineUi(Terminal terminal) {
        this.terminal = terminal;
        this.lineReader = LineReaderBuilder.builder().terminal(terminal).build();
    }

    /** Null when stdin/stdout are not a real terminal or JLine cannot drive it. */
    static JLineUi tryCreate() {
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build();
            if (terminal.getType() == null || terminal.getType().startsWith("dumb")) { terminal.close(); return null; }
            return new JLineUi(terminal);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void bind(KeyMap<Op> keys, Op op, String... sequences) { keys.bind(op, sequences); }

    @Override public String pick(String title, List<Item> items, String hint) {
        Attributes saved = terminal.enterRawMode();
        terminal.puts(Capability.keypad_xmit);
        terminal.flush();
        Display display = new Display(terminal, false);
        try {
            KeyMap<Op> keys = new KeyMap<>();
            bind(keys, Op.UP, KeyMap.key(terminal, Capability.key_up), "\033[A", "\033OA", "\020");
            bind(keys, Op.DOWN, KeyMap.key(terminal, Capability.key_down), "\033[B", "\033OB", "\016");
            bind(keys, Op.PAGE_UP, "\033[5~");
            bind(keys, Op.PAGE_DOWN, "\033[6~");
            bind(keys, Op.HOME, "\033[H", "\033OH", "\033[1~");
            bind(keys, Op.END, "\033[F", "\033OF", "\033[4~");
            bind(keys, Op.ENTER, "\r", "\n");
            bind(keys, Op.ESC, "\033");
            bind(keys, Op.BACKSPACE, "\177", "\b");
            bind(keys, Op.CLEAR, "\025");
            bind(keys, Op.INTERRUPT, "\003", "\004");
            keys.setNomatch(Op.CHAR);
            keys.setUnicode(Op.CHAR);
            keys.setAmbiguousTimeout(120);
            BindingReader reader = new BindingReader(terminal.reader());
            StringBuilder query = new StringBuilder();
            int selected = 0, top = 0;
            while (true) {
                List<Item> shown = Ui.filter(items, query.toString());
                selected = shown.isEmpty() ? 0 : Math.max(0, Math.min(selected, shown.size() - 1));
                Size size = terminal.getSize();
                int rows = size.getRows() > 0 ? size.getRows() : 24, columns = size.getColumns() > 0 ? size.getColumns() : 80;
                display.resize(rows, columns);
                int window = Math.max(3, Math.min(12, rows - 6));
                if (selected < top) top = selected;
                if (selected >= top + window) top = selected - window + 1;
                top = Math.max(0, Math.min(top, Math.max(0, shown.size() - window)));
                List<AttributedString> lines = new ArrayList<>();
                lines.add(styled(title, AttributedStyle.BOLD));
                lines.add(styled(hint == null ? "" : hint, AttributedStyle.DEFAULT.faint()));
                lines.add(styled("> " + query, AttributedStyle.DEFAULT));
                for (int i = top; i < Math.min(shown.size(), top + window); i++) {
                    Item item = shown.get(i);
                    AttributedStringBuilder row = new AttributedStringBuilder();
                    boolean on = i == selected;
                    row.style(on ? AttributedStyle.DEFAULT.inverse() : AttributedStyle.DEFAULT).append(on ? "> " : "  ").append(item.label());
                    if (!item.detail().isEmpty()) row.style(on ? AttributedStyle.DEFAULT.inverse() : AttributedStyle.DEFAULT.faint()).append("   " + item.detail());
                    lines.add(clip(row.toAttributedString(), columns - 1));
                }
                if (shown.isEmpty()) lines.add(styled("  nothing matches", AttributedStyle.DEFAULT.faint()));
                else if (shown.size() > window) lines.add(styled("  " + (selected + 1) + " of " + shown.size() + "  (arrows, PgUp/PgDn)", AttributedStyle.DEFAULT.faint()));
                display.update(lines, size.cursorPos(2, 2 + query.length()));
                Op op = reader.readBinding(keys);
                if (op == null) return finish(display, title, null);
                switch (op) {
                    case UP -> selected = shown.isEmpty() ? 0 : (selected - 1 + shown.size()) % shown.size();
                    case DOWN -> selected = shown.isEmpty() ? 0 : (selected + 1) % shown.size();
                    case PAGE_UP -> selected = Math.max(0, selected - window);
                    case PAGE_DOWN -> selected = Math.min(Math.max(0, shown.size() - 1), selected + window);
                    case HOME -> selected = 0;
                    case END -> selected = Math.max(0, shown.size() - 1);
                    case BACKSPACE -> { if (query.length() > 0) { query.setLength(query.length() - 1); selected = 0; top = 0; } }
                    case CLEAR -> { query.setLength(0); selected = 0; top = 0; }
                    case ENTER -> { if (!shown.isEmpty()) return finish(display, title, shown.get(selected)); }
                    case ESC, INTERRUPT -> { return finish(display, title, null); }
                    case CHAR -> {
                        String typed = reader.getLastBinding();
                        if (typed != null && typed.codePoints().noneMatch(Character::isISOControl)) { query.append(typed); selected = 0; top = 0; }
                    }
                }
            }
        } finally {
            terminal.puts(Capability.keypad_local);
            terminal.setAttributes(saved);
            terminal.flush();
        }
    }

    private String finish(Display display, String title, Item chosen) {
        display.update(Collections.emptyList(), 0);
        terminal.writer().println(title + ": " + (chosen == null ? "(cancelled)" : chosen.label()));
        terminal.flush();
        return chosen == null ? null : chosen.id();
    }

    private AttributedString styled(String text, AttributedStyle style) {
        return new AttributedStringBuilder().style(color ? style : AttributedStyle.DEFAULT).append(text).toAttributedString();
    }

    private static AttributedString clip(AttributedString line, int columns) {
        return line.columnLength() > columns ? line.columnSubSequence(0, Math.max(1, columns)) : line;
    }

    @Override public String ask(String prompt, String initial) {
        try {
            return lineReader.readLine(prompt, null, initial == null ? "" : initial).strip();
        } catch (UserInterruptException | EndOfFileException e) {
            return null;
        }
    }

    @Override public void print(String text) { terminal.writer().println(text); terminal.flush(); }

    @Override public void close() {
        try { terminal.close(); } catch (Exception ignored) { /* shutting down */ }
    }
}
