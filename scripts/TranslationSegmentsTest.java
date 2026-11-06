import dev.poteto.plamoselect.TranslationSegments;
import java.util.List;

/**
 * Fictional, newly authored regression text.
 * It tests paragraph coherence and coreference without embedding news content.
 */
public final class TranslationSegmentsTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String fictionalArticle =
                "In the fictional country of Avenor, Interior Minister Mara Ellis "
                + "faced impeachment over a disputed surveillance contract. "
                + "Her opponents argued that her recent speech had weakened "
                + "her defense.\n\n"
                + "On Tuesday, Ellis told supporters that her department had "
                + "followed every rule. Nevertheless, analysts questioned "
                + "whether the coalition would continue defending her "
                + "after the election.\n\n"
                + "Prime Minister Rowan Hale praised the minister's work "
                + "at a press conference, even as members of the lower house "
                + "suggested that they might vote to impeach her next week.";
        List<String> paragraphs = TranslationSegments.split(fictionalArticle, 420);
        check(paragraphs.size() == 3,
                "Expected 3 whole paragraphs, got " + paragraphs.size());
        check(paragraphs.get(0).contains("impeachment"),
                "Impeachment topic was lost");
        check(paragraphs.get(2).contains("vote to impeach her next week."),
                "Voting time was separated from its clause");
        check(paragraphs.get(2).contains("lower house"),
                "Lower house reference disappeared");
        check(TranslationSegments.surroundingContext(
                fictionalArticle, paragraphs, 2, 1200).contains("surveillance contract"),
                "Previous context not passed to the final paragraph");

        String longParagraph = "The market fell by 2.5% on Monday. "
                + "The index recovered to 5.52% on Tuesday. "
                + "Investors watched what would happen next week. ".repeat(12);
        List<String> parts = TranslationSegments.split(longParagraph, 360);
        check(parts.size() >= 2, "Oversized text was not divided");
        for (String part : parts) {
            check(part.length() <= 360, "Oversized segment");
            check(!part.startsWith("5%"), "Decimal number split");
        }
        String emojis = "🚀".repeat(150);
        for (String part : TranslationSegments.split(emojis, 161)) {
            check(!Character.isLowSurrogate(part.charAt(0)), "Broken surrogate start");
            check(!Character.isHighSurrogate(part.charAt(part.length() - 1)),
                    "Broken surrogate end");
        }
        System.out.println("PASS: fictional 3-paragraph context and syntax checks");
        for (int i = 0; i < paragraphs.size(); i++) {
            System.out.println("SEGMENT " + (i + 1) + ": " + paragraphs.get(i));
        }
    }
}
