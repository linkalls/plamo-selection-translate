import dev.poteto.plamoselect.PLaMoPrompt;
import java.util.List;

public final class PLaMoPromptTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String last = "President Donald Trump said the House may vote to impeach the secretary next week.";
        List<PLaMoPrompt.Pair> previous = List.of(
                new PLaMoPrompt.Pair("He tried to avoid impeachment.", "彼は弾劾を免れようとした。"),
                new PLaMoPrompt.Pair("His critics disagreed.", "批判派は反対した。"));
        String prompt = PLaMoPrompt.build(last, "ja", "en",
                "A news report about a U.S. defense secretary facing impeachment.", previous);
        check(prompt.startsWith("<|plamo:tag|>dataset<|plamo:msg|>translation"),
                "Wrong dataset header");
        check(prompt.contains("<|plamo:tag|>context<|plamo:msg|>A news report"),
                "Reference context missing");
        check(prompt.contains("<|plamo:msg|>彼は弾劾を免れようとした。"),
                "Previous translation missing");
        check(prompt.contains("<|plamo:msg|>批判派は反対した。"),
                "Previous pair missing");
        check(prompt.endsWith("<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ja<|plamo:msg|>"),
                "Final output prompt must be empty and in Japanese");
        check(prompt.lastIndexOf(last) > prompt.indexOf("批判派"),
                "Current source must follow translation history");
        System.out.println("PASS: PFML3 context, previous 2 aligned pairs, and final request ordering");
    }
}
