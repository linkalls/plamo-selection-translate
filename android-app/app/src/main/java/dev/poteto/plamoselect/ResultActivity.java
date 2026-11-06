package dev.poteto.plamoselect;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Shows the actual complete translation in a separate full-screen view.
 * The floating result card is height-limited for readability, but it must
 * never give the impression that the backend only generated the visible text.
 */
public final class ResultActivity extends Activity {
    public static final String EXTRA_TRANSLATED_TEXT = "plamo.complete_translation";
    private static final int BLUE = 0xFF0B57D0;
    private static final int INK = 0xFF1D1E20;

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView action(String text, Runnable listener) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(BLUE);
        view.setPadding(dp(11), dp(12), dp(11), dp(12));
        view.setGravity(Gravity.CENTER);
        view.setOnClickListener(v -> listener.run());
        return view;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFFF7F9FC);
        getWindow().setNavigationBarColor(0xFFF7F9FC);
        getWindow().getDecorView().setSystemUiVisibility(0x00002000 | 0x00000010);
        String result = getIntent().getStringExtra(EXTRA_TRANSLATED_TEXT);
        if (result == null) result = "";
        final String text = result;

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFFF7F9FC);
        page.setPadding(dp(14), dp(12), dp(14), dp(14));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(action("‹ 戻る", this::finish));
        TextView heading = new TextView(this);
        heading.setText("翻訳全文");
        heading.setTextSize(22);
        heading.setTextColor(INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(heading, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        header.addView(action("コピー", () -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(
                    Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("PLaMo 翻訳全文", text));
                Toast.makeText(this, "翻訳全文をコピーしたよ", Toast.LENGTH_SHORT).show();
            }
        }));
        page.addView(header);

        TextView count = new TextView(this);
        count.setText("全文 " + text.codePointCount(0, text.length()) + "文字・端末内翻訳");
        count.setTextSize(12);
        count.setTextColor(0xFF647184);
        count.setPadding(dp(10), dp(4), dp(10), dp(12));
        page.addView(count);

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(false);
        scroller.setVerticalScrollBarEnabled(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFDCEBFE);
        background.setCornerRadius(dp(20));
        scroller.setBackground(background);
        scroller.setPadding(dp(18), dp(18), dp(18), dp(18));
        TextView translated = new TextView(this);
        translated.setText(text);
        translated.setTextSize(getSharedPreferences("plamo_prefs", MODE_PRIVATE)
                .getInt("result_font_size", 17));
        translated.setTextColor(INK);
        translated.setTextIsSelectable(true);
        translated.setLineSpacing(dp(2), 1f);
        scroller.addView(translated);
        page.addView(scroller, scrollParams);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.addView(action("共有", () -> {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(intent, "翻訳全文を共有"));
        }));
        footer.addView(action("閉じる", this::finish));
        page.addView(footer);
        setContentView(page);
    }
}
