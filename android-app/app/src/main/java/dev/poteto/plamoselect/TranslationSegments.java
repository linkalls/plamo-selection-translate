package dev.poteto.plamoselect;

import java.util.ArrayList;
import java.util.List;

/**
 * Context-preserving division of a document into coherent translation units.
 *
 * Prefer complete paragraphs to isolated sentences: translating each clause
 * separately loses antecedents, negation scope, referents, named entities, and
 * argument structure. Only oversized paragraphs are divided into sentences.
 * The original text is kept separately so its surrounding context can be
 * provided to the PLaMo 3 translation task for every unit.
 *
 * No content is silently discarded. Whitespace separating paragraphs is
 * normalized, while text inside each paragraph is preserved.
 */
public final class TranslationSegments {
    private TranslationSegments() {}

    /**
     * @param source Complete document to translate.
     * @param maxChars Soft upper bound for each coherent unit. Oversized
     *                 unpunctuated spans may be split at whitespace.
     */
    public static List<String> split(String source, int maxChars) {
        if (maxChars < 160) {
            throw new IllegalArgumentException("maxChars must be >= 160");
        }
        List<String> result = new ArrayList<>();
        String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
        // A newline from a web selection is usually a paragraph boundary.
        // A paragraph, not an arbitrary sentence, is the translation unit.
        String[] paragraphs = normalized.split("\\n+", -1);
        for (String paragraph : paragraphs) {
            String clean = paragraph.trim();
            if (clean.isEmpty()) continue;
            if (clean.length() <= maxChars) {
                result.add(clean);
            } else {
                splitLongParagraph(clean, maxChars, result);
            }
        }
        return result;
    }

    private static void splitLongParagraph(String paragraph, int maxChars,
            List<String> result) {
        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(paragraph.length(), start + maxChars);
            if (end < paragraph.length()) {
                // Prefer a sentence ending near the chosen boundary, but do
                // not split decimal numbers such as 5.52 or 2.5.
                int split = -1;
                int searchStart = start + (int) (maxChars * 0.55);
                for (int i = end - 1; i >= searchStart; --i) {
                    char ch = paragraph.charAt(i);
                    char previous = i > 0 ? paragraph.charAt(i - 1) : '\0';
                    char next = i + 1 < paragraph.length()
                            ? paragraph.charAt(i + 1) : '\0';
                    boolean decimal = ch == '.'
                            && Character.isDigit(previous) && Character.isDigit(next);
                    boolean japanese = ch == '。' || ch == '！' || ch == '？';
                    boolean western = (ch == '.' || ch == '!' || ch == '?')
                            && !decimal && (i + 1 == paragraph.length()
                                    || Character.isWhitespace(next));
                    if (japanese || western) {
                        split = i + 1;
                        break;
                    }
                }
                if (split < 0) {
                    // Fallback to word boundary for a very long sentence.
                    for (int i = end - 1; i > start + maxChars / 2; --i) {
                        if (Character.isWhitespace(paragraph.charAt(i))) {
                            split = i + 1;
                            break;
                        }
                    }
                }
                if (split > start) end = split;
                if (end > start && end < paragraph.length()
                        && Character.isHighSurrogate(paragraph.charAt(end - 1))
                        && Character.isLowSurrogate(paragraph.charAt(end))) {
                    --end;
                }
            }
            if (end <= start) {
                end = Math.min(start + maxChars, paragraph.length());
            }
            String unit = paragraph.substring(start, end).trim();
            if (!unit.isEmpty()) result.add(unit);
            start = end;
            while (start < paragraph.length()
                    && Character.isWhitespace(paragraph.charAt(start))) {
                ++start;
            }
        }
    }

    /**
     * Produce reference text surrounding the current unit. It is *not* part
     * of the source to translate, so PLaMo can use it for pronoun resolution
     * without duplicating translated output. Keep it short for 0.8B phones.
     */
    public static String surroundingContext(String document, List<String> units,
            int index, int maxChars) {
        if (maxChars <= 0 || units.isEmpty()) return "";
        // Short selections: full original article gives the strongest clues.
        String normalized = document.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxChars) return normalized.trim();

        // Very long articles: include title and surrounding paragraphs.
        StringBuilder context = new StringBuilder();
        String headline = units.get(0);
        appendWithinBudget(context, headline, maxChars);
        if (index > 0) {
            appendWithinBudget(context, units.get(index - 1), maxChars);
        }
        if (index + 1 < units.size()) {
            appendWithinBudget(context, units.get(index + 1), maxChars);
        }
        return context.toString();
    }

    private static void appendWithinBudget(StringBuilder output, String item,
            int maxChars) {
        int room = maxChars - output.length();
        if (room < 30) return;
        String value = item;
        if (value.length() > room - 1) {
            int boundary = room - 1;
            if (boundary > 0 && boundary < value.length()
                    && Character.isHighSurrogate(value.charAt(boundary - 1))) {
                --boundary;
            }
            value = value.substring(0, boundary);
        }
        if (output.length() > 0) output.append('\n');
        output.append(value);
    }
}
