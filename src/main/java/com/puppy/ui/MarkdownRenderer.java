package com.puppy.ui;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, dependency-free Markdown renderer for chat bubbles.
 *
 * <p>Supports headings, paragraphs, bold/italic/strikethrough, inline code,
 * fenced code blocks, bullet and numbered lists, block quotes, links, tables
 * and horizontal rules. It intentionally ignores raw HTML, so model output can
 * never inject arbitrary nodes into the scene graph.</p>
 */
public final class MarkdownRenderer {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern TABLE_SEPARATOR =
            Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");

    private MarkdownRenderer() {
    }

    public static VBox render(String markdown) {
        VBox body = new VBox(6);
        body.getStyleClass().add("md-body");
        body.setFillWidth(true);
        body.setMaxWidth(Double.MAX_VALUE);

        String normalized = markdown == null
                ? ""
                : markdown.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);

        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            if (line.isBlank()) {
                index++;
                continue;
            }

            String leading = line.stripLeading();
            if (leading.startsWith("```") || leading.startsWith("~~~")) {
                index = appendCodeBlock(body, lines, index, leading.substring(0, 3));
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = Math.min(3, heading.group(1).length());
                TextFlow flow = inlineFlow(heading.group(2).strip(),
                        "md-heading", "md-h" + level);
                body.getChildren().add(flow);
                index++;
                continue;
            }

            if (RULE.matcher(line).matches()) {
                Separator rule = new Separator();
                rule.getStyleClass().add("md-rule");
                body.getChildren().add(rule);
                index++;
                continue;
            }

            if (isTableStart(lines, index)) {
                index = appendTable(body, lines, index);
                continue;
            }

            Matcher listItem = LIST_ITEM.matcher(line);
            if (listItem.matches()) {
                index = appendList(body, lines, index);
                continue;
            }

            if (line.stripLeading().startsWith(">")) {
                index = appendQuote(body, lines, index);
                continue;
            }

            index = appendParagraph(body, lines, index);
        }

        return body;
    }

    private static int appendCodeBlock(VBox body, String[] lines, int start, String fence) {
        String language = lines[start].stripLeading().substring(fence.length()).strip();
        StringBuilder code = new StringBuilder();
        int index = start + 1;
        while (index < lines.length && !lines[index].stripLeading().startsWith(fence)) {
            code.append(lines[index]).append('\n');
            index++;
        }
        if (index < lines.length) {
            index++;
        }

        Label block = new Label(code.toString().stripTrailing());
        block.setWrapText(true);
        block.setMaxWidth(Double.MAX_VALUE);
        block.getStyleClass().add("md-code-block");
        if (!language.isBlank()) {
            block.setTooltip(new Tooltip(language));
        }
        body.getChildren().add(block);
        return index;
    }

    private static int appendList(VBox body, String[] lines, int start) {
        int index = start;
        while (index < lines.length) {
            Matcher item = LIST_ITEM.matcher(lines[index]);
            if (!item.matches()) {
                break;
            }
            String indent = item.group(1);
            String markerText = item.group(2);
            String content = item.group(3);

            int level = Math.min(3, indent.replace("\t", "  ").length() / 2);
            String marker = markerText.matches("\\d+[.)]")
                    ? markerText
                    : bulletFor(level);

            Label bullet = new Label(marker);
            bullet.getStyleClass().add("md-list-marker");
            bullet.setMinWidth(18);

            TextFlow flow = inlineFlow(content, "md-list-text");
            HBox.setHgrow(flow, Priority.ALWAYS);
            flow.setMaxWidth(Double.MAX_VALUE);

            HBox row = new HBox(6, bullet, flow);
            row.setPadding(new Insets(0, 0, 0, level * 16));
            row.getStyleClass().add("md-list-item");
            body.getChildren().add(row);
            index++;
        }
        return index;
    }

    private static String bulletFor(int level) {
        return switch (level % 3) {
            case 0 -> "\u2022";
            case 1 -> "\u25E6";
            default -> "\u25AA";
        };
    }

    private static int appendQuote(VBox body, String[] lines, int start) {
        VBox quote = new VBox(4);
        quote.getStyleClass().add("md-quote");
        int index = start;
        while (index < lines.length && lines[index].stripLeading().startsWith(">")) {
            String content = lines[index].stripLeading().substring(1).strip();
            if (!content.isBlank()) {
                quote.getChildren().add(inlineFlow(content, "md-quote-text"));
            }
            index++;
        }
        if (!quote.getChildren().isEmpty()) {
            body.getChildren().add(quote);
        }
        return index;
    }

    private static int appendParagraph(VBox body, String[] lines, int start) {
        StringBuilder paragraph = new StringBuilder();
        int index = start;
        while (index < lines.length && !lines[index].isBlank() && !isBlockStart(lines, index)) {
            if (paragraph.length() > 0) {
                paragraph.append('\n');
            }
            paragraph.append(lines[index].strip());
            index++;
        }
        if (paragraph.length() > 0) {
            body.getChildren().add(inlineFlow(paragraph.toString()));
        }
        return index;
    }

    private static boolean isBlockStart(String[] lines, int index) {
        String line = lines[index];
        String leading = line.stripLeading();
        return leading.startsWith("```")
                || leading.startsWith("~~~")
                || leading.startsWith(">")
                || HEADING.matcher(line).matches()
                || RULE.matcher(line).matches()
                || LIST_ITEM.matcher(line).matches()
                || isTableStart(lines, index);
    }

    private static boolean isTableStart(String[] lines, int index) {
        return index + 1 < lines.length
                && lines[index].contains("|")
                && TABLE_SEPARATOR.matcher(lines[index + 1]).matches();
    }

    private static int appendTable(VBox body, String[] lines, int start) {
        List<String> header = tableCells(lines[start]);
        int columns = Math.max(1, header.size());
        List<List<String>> rows = new ArrayList<>();
        int index = start + 2;
        while (index < lines.length && !lines[index].isBlank() && lines[index].contains("|")) {
            List<String> row = tableCells(lines[index]);
            if (row.isEmpty()) {
                break;
            }
            columns = Math.max(columns, row.size());
            rows.add(row);
            index++;
        }

        GridPane table = new GridPane();
        table.getStyleClass().add("md-table");
        for (int column = 0; column < columns; column++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setPercentWidth(100.0 / columns);
            constraints.setHgrow(Priority.ALWAYS);
            constraints.setFillWidth(true);
            table.getColumnConstraints().add(constraints);
        }

        addTableRow(table, header, 0, columns, true);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            addTableRow(table, rows.get(rowIndex), rowIndex + 1, columns, false);
        }
        body.getChildren().add(table);
        return index;
    }

    private static void addTableRow(GridPane table, List<String> cells, int row,
                                    int columns, boolean header) {
        for (int column = 0; column < columns; column++) {
            String text = column < cells.size() ? cells.get(column) : "";
            TextFlow flow = inlineFlow(text, "md-table-text");
            flow.setMaxWidth(Double.MAX_VALUE);

            StackPane cell = new StackPane(flow);
            cell.getStyleClass().add("md-table-cell");
            if (header) {
                cell.getStyleClass().add("md-table-header");
            }
            GridPane.setHgrow(cell, Priority.ALWAYS);
            table.add(cell, column, row);
        }
    }

    private static List<String> tableCells(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] parts = trimmed.split("\\|", -1);
        List<String> cells = new ArrayList<>(parts.length);
        for (String part : parts) {
            cells.add(part.strip());
        }
        return cells;
    }

    private static TextFlow inlineFlow(String text, String... extraStyles) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("md-paragraph");
        flow.getStyleClass().addAll(extraStyles);
        flow.setMaxWidth(Double.MAX_VALUE);
        flow.setLineSpacing(2);
        appendInline(flow, text, List.of(extraStyles));
        return flow;
    }

    /**
     * Parses inline Markdown recursively so bold and italic can be nested.
     */
    private static void appendInline(TextFlow flow, String text, List<String> styles) {
        StringBuilder plain = new StringBuilder();
        int index = 0;

        while (index < text.length()) {
            char current = text.charAt(index);

            if (current == '\\' && index + 1 < text.length()) {
                plain.append(text.charAt(index + 1));
                index += 2;
                continue;
            }

            if (current == '`') {
                int close = text.indexOf('`', index + 1);
                if (close > index) {
                    flushText(flow, plain, styles);
                    Label code = new Label(text.substring(index + 1, close));
                    code.getStyleClass().add("md-inline-code");
                    code.getStyleClass().addAll(styles);
                    flow.getChildren().add(code);
                    index = close + 1;
                    continue;
                }
            }

            if (current == '*' || current == '_' || current == '~') {
                boolean doubled = index + 1 < text.length()
                        && text.charAt(index + 1) == current;
                if (current == '~' && !doubled) {
                    plain.append(current);
                    index++;
                    continue;
                }
                if (current == '_' && !doubled
                        && index > 0 && Character.isLetterOrDigit(text.charAt(index - 1))
                        && index + 1 < text.length()
                        && Character.isLetterOrDigit(text.charAt(index + 1))) {
                    plain.append(current);
                    index++;
                    continue;
                }

                String marker = doubled ? "" + current + current : String.valueOf(current);
                int close = findClosing(text, index + marker.length(), marker);
                if (close >= 0) {
                    flushText(flow, plain, styles);
                    List<String> nested = new ArrayList<>(styles);
                    nested.add(current == '~' ? "md-strike" : doubled ? "md-bold" : "md-italic");
                    appendInline(flow, text.substring(index + marker.length(), close), nested);
                    index = close + marker.length();
                    continue;
                }
            }

            if (current == '[') {
                int closeLabel = text.indexOf(']', index + 1);
                if (closeLabel > 0 && closeLabel + 1 < text.length()
                        && text.charAt(closeLabel + 1) == '(') {
                    int closeUrl = text.indexOf(')', closeLabel + 2);
                    if (closeUrl > 0) {
                        flushText(flow, plain, styles);
                        String label = text.substring(index + 1, closeLabel);
                        String url = text.substring(closeLabel + 2, closeUrl).strip();
                        flow.getChildren().add(link(label, url, styles));
                        index = closeUrl + 1;
                        continue;
                    }
                }
            }

            if (current == '<') {
                int close = text.indexOf('>', index + 1);
                if (close > index) {
                    String candidate = text.substring(index + 1, close).strip();
                    if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                        flushText(flow, plain, styles);
                        flow.getChildren().add(link(candidate, candidate, styles));
                        index = close + 1;
                        continue;
                    }
                }
            }

            plain.append(current);
            index++;
        }

        flushText(flow, plain, styles);
    }

    private static int findClosing(String text, int from, String marker) {
        char markerChar = marker.charAt(0);
        for (int index = from; index + marker.length() <= text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\\') {
                index++;
                continue;
            }
            if (!text.startsWith(marker, index)) {
                continue;
            }
            if (marker.length() == 1) {
                if (index + 1 < text.length() && text.charAt(index + 1) == markerChar) {
                    index++;
                    continue;
                }
                if (markerChar == '_' && index > 0
                        && Character.isLetterOrDigit(text.charAt(index - 1))) {
                    continue;
                }
                if (markerChar == '_' && index + 1 < text.length()
                        && Character.isLetterOrDigit(text.charAt(index + 1))) {
                    continue;
                }
            }
            return index;
        }
        return -1;
    }

    private static Node link(String label, String url, List<String> styles) {
        Hyperlink hyperlink = new Hyperlink(stripInlineMarkers(label));
        hyperlink.getStyleClass().add("md-link");
        hyperlink.getStyleClass().addAll(styles);
        if (!url.isBlank()) {
            hyperlink.setTooltip(new Tooltip(url));
            hyperlink.setOnAction(event -> openLink(url));
        }
        return hyperlink;
    }

    private static String stripInlineMarkers(String label) {
        return label
                .replace("**", "")
                .replace("__", "")
                .replace("~~", "")
                .replace("`", "")
                .replace("*", "")
                .replace("_", "");
    }

    private static void flushText(TextFlow flow, StringBuilder plain, List<String> styles) {
        if (plain.isEmpty()) {
            return;
        }
        Text text = new Text(plain.toString());
        text.getStyleClass().add("md-text");
        text.getStyleClass().addAll(styles);
        flow.getChildren().add(text);
        plain.setLength(0);
    }

    private static void openLink(String url) {
        try {
            URI uri = URI.create(url.strip());
            String scheme = uri.getScheme();
            if (scheme == null) {
                return;
            }
            String normalized = scheme.toLowerCase(Locale.ROOT);
            if (!normalized.equals("http") && !normalized.equals("https")
                    && !normalized.equals("mailto")) {
                return;
            }
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            }
        } catch (Exception ignored) {
            // A dead link should never take the chat panel down.
        }
    }
}
