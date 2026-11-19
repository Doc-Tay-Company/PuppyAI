package com.puppy.util;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settles on one display name for a school subject.
 *
 * <p>Imported files often name the same subject in Vietnamese and English
 * ("Toán" and "Math"). Keeping both creates duplicate subjects in the plan, so
 * common subjects are mapped to one English name. Unknown names keep the
 * original spelling, and names that only differ by case, accents or spacing
 * share a comparison key.</p>
 */
public final class SubjectNames {

    private static final Pattern NUMBERED = Pattern.compile("^(.+?)\\s+(\\d{1,2})$");
    private static final Map<String, String> CANONICAL = canonicalNames();

    private SubjectNames() {
    }

    /** Returns the preferred display name for a subject. */
    public static String canonical(String value) {
        String cleaned = clean(value);
        if (cleaned.isEmpty()) {
            return "";
        }
        String direct = CANONICAL.get(fold(cleaned));
        if (direct != null) {
            return direct;
        }
        Matcher numbered = NUMBERED.matcher(fold(cleaned));
        if (numbered.matches()) {
            String base = CANONICAL.get(numbered.group(1));
            if (base != null) {
                return base + " " + numbered.group(2);
            }
        }
        return cleaned;
    }

    /** A comparison key that ignores case, Vietnamese accents and extra spaces. */
    public static String key(String value) {
        return fold(canonical(value));
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String fold(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'D')
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static Map<String, String> canonicalNames() {
        Map<String, String> names = new HashMap<>();
        add(names, "Math", "math", "maths", "mathematics", "toan");
        add(names, "Advanced Mathematics", "toan cao cap", "advanced math",
                "advanced mathematics");
        add(names, "Discrete Mathematics", "toan roi rac", "discrete math",
                "discrete mathematics");
        add(names, "Physics", "physics", "physic", "vat ly", "vat li", "ly", "li");
        add(names, "General Physics", "vat ly dai cuong", "general physics");
        add(names, "Chemistry", "chemistry", "chem", "hoa hoc", "hoa");
        add(names, "Organic Chemistry", "hoa huu co", "organic chemistry");
        add(names, "Inorganic Chemistry", "hoa vo co", "inorganic chemistry");
        add(names, "Biology", "biology", "bio", "sinh hoc", "sinh");
        add(names, "Literature", "literature", "ngu van", "van hoc", "van");
        add(names, "English", "english", "tieng anh", "anh van", "anh");
        add(names, "History", "history", "lich su", "su");
        add(names, "Geography", "geography", "dia ly", "dia li", "dia");
        add(names, "Computer Science", "computer science", "informatics",
                "tin hoc", "tin");
        add(names, "Technology", "technology", "cong nghe");
        add(names, "Civics", "civics", "civic education", "giao duc cong dan", "gdcd");
        add(names, "Music", "music", "am nhac");
        add(names, "Art", "art", "fine art", "fine arts", "my thuat");
        add(names, "Physical Education", "physical education", "pe", "the duc");
        add(names, "Philosophy", "philosophy", "triet hoc");
        add(names, "Economics", "economics", "kinh te hoc", "kinh te");
        add(names, "Law", "law", "phap luat");
        return Map.copyOf(names);
    }

    private static void add(Map<String, String> names, String canonical, String... aliases) {
        for (String alias : aliases) {
            names.putIfAbsent(fold(alias), canonical);
        }
    }
}
