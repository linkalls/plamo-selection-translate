package dev.poteto.plamoselect;

import java.util.List;

/**
 * Official PFML3 translation prompt builder for PLaMo 3 Translate GGUF.
 *
 * Preferred Networks documents two essential disambiguation mechanisms:
 * - `context` supplies the surrounding document or topic;
 * - preceding input/output pairs supply translation history.
 *
 * These are separate from the text actually being translated, so context
 * is never incorrectly emitted as duplicate output. The final output element
 * has an empty body as required by PFML3. llama.cpp inserts BOS itself.
 */
public final class PLaMoPrompt {
    private PLaMoPrompt() {}

    public static final class Pair {
        public final String source;
        public final String translation;

        public Pair(String source, String translation) {
            this.source = source;
            this.translation = translation;
        }
    }

    public static String build(String source, String targetLanguage,
            String sourceLanguage, String context, List<Pair> history) {
        StringBuilder result = new StringBuilder();
        result.append("<|plamo:tag|>dataset<|plamo:msg|>translation");

        // The official ordering is context, history, glossary, current input.
        if (context != null && !context.isBlank()) {
            result.append("<|plamo:tag|>context<|plamo:msg|>")
                    .append(context.trim());
        }

        if (history != null) {
            // Keep at most two previous paragraphs. Very long context/history
            // would needlessly consume a mobile device's limited KV cache.
            int begin = Math.max(0, history.size() - 2);
            for (int i = begin; i < history.size(); ++i) {
                Pair pair = history.get(i);
                if (pair.source.isBlank() || pair.translation.isBlank()) continue;
                appendInput(result, pair.source, sourceLanguage);
                appendOutput(result, pair.translation, targetLanguage);
            }
        }

        appendInput(result, source, sourceLanguage);
        appendOutput(result, "", targetLanguage);
        return result.toString();
    }

    private static void appendInput(StringBuilder result, String source,
            String sourceLanguage) {
        result.append("<|plamo:tag|>input");
        if (sourceLanguage != null && !sourceLanguage.isEmpty()) {
            result.append("<|plamo:key|>lang<|plamo:val|>")
                    .append(sourceLanguage);
        }
        result.append("<|plamo:msg|>").append(source);
    }

    private static void appendOutput(StringBuilder result, String translation,
            String targetLanguage) {
        result.append("<|plamo:tag|>output")
                .append("<|plamo:key|>lang<|plamo:val|>")
                .append(targetLanguage)
                .append("<|plamo:msg|>").append(translation);
    }
}
