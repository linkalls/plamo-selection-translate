package dev.poteto.plamoselect;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a document into sentence-sized translation requests.
 *
 * PLaMo 3 Translate is task-oriented. A single PFML3 request containing
 * multiple sentences or a headline followed by several paragraphs can emit
 * just the first translated sentence and then stop normally. Therefore the
 * Android UI must not treat an entire selected article as one translation
 * unit, even if it fits within the context-window token budget.
 *
 * No source content is discarded. Each unit is nonempty and never longer than
 * maxChars, except for Unicode surrogate pairs, which are never split.
 */
public final class TranslationSegments {
    private TranslationSegments() {}

    public static List<String> split(String source, int maxChars) {
        if (maxChars < 80) throw new IllegalArgumentException("maxChars < 80");
        List<String> result = new ArrayList<>();
        String text = source.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder segment = new StringBuilder();
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            int codePointLength = Character.charCount(codePoint);
            int nextIndex = i + codePointLength;
            int next = nextIndex < text.length() ? text.codePointAt(nextIndex) : -1;
            int prev = i > 0 ? text.codePointBefore(i) : -1;
            segment.appendCodePoint(codePoint);

            boolean paragraphBreak = codePoint == '\n';
            boolean japanesePeriod = codePoint == '。'
                    || codePoint == '！' || codePoint == '？';
            boolean asciiPunctuation = codePoint == '.' || codePoint == '?'
                    || codePoint == '!';
            boolean decimalPoint = codePoint == '.'
                    && Character.isDigit(prev) && Character.isDigit(next);
            // End on a sentence mark only when the following character is a
            // separator/end. This avoids splitting decimals and domain names.
            boolean englishSentenceEnd = asciiPunctuation && !decimalPoint
                    && (next == -1 || Character.isWhitespace(next)
                        || next == '"' || next == '\''
                        || next == '\u201d' || next == '\u2019');
            boolean overBudget = segment.length() >= maxChars;

            if (paragraphBreak || japanesePeriod || englishSentenceEnd
                    || overBudget) {
                String candidate = segment.toString().trim();
                if (!candidate.isEmpty()) result.add(candidate);
                segment.setLength(0);
            }
            i = nextIndex;
        }
        String tail = segment.toString().trim();
        if (!tail.isEmpty()) result.add(tail);
        return result;
    }
}
