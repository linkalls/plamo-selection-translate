package dev.poteto.plamoselect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;

/**
 * Full-screen settings, independent of the short-lived selection popup.
 * Every row either changes an actual preference or dispatches a model action.
 * Inference settings are shared with ModelService through plamo_prefs.
 */
public final class SettingsActivity extends Activity {
    static final String EXTRA_ACTION = "dev.poteto.plamoselect.settings_action";
    static final String ACTION_MODEL_IMPORT = "import";
    static final String ACTION_DOWNLOAD_Q4_0 = "download_q4_0";
    static final String ACTION_DOWNLOAD_Q4_K_M = "download_q4_k_m";

    private static final int BLUE = 0xFF0B57D0;
    private static final int INK = 0xFF1D1E20;
    private static final int MUTED = 0xFF647184;
    private static final int BG = 0xFFF7F9FC;
    private static final int BORDER = 0xFFE1E6EF;

    private SharedPreferences preferences;
    private LinearLayout content;

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("plamo_prefs", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        render();
    }

    private GradientDrawable panel(int color, int radius, int border) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (border != 0) background.setStroke(dp(1), border);
        return background;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void render() {
        ScrollView scrolling = new ScrollView(this);
        scrolling.setBackgroundColor(BG);
        content = vertical();
        content.setPadding(dp(18), dp(16), dp(18), dp(28));
        scrolling.addView(content);
        setContentView(scrolling);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹", 32, BLUE, true);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("翻訳画面に戻る");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        TextView title = label("設定", 24, INK, true);
        header.addView(title);
        content.addView(header);
        addSection("モデル管理");

        File model = new File(getFilesDir(), "plamo-translate.gguf");
        String quantization = preferences.getString("model_quantization", "既存モデル");
        addDescription(model.isFile()
                ? "PLaMo " + preferences.getInt("plamo_generation", 3)
                    + " / " + quantization + " / " + model.length() / 1_000_000 + " MB"
                : "モデル未登録");
        addRow("高速モデルを入手",
                "PLaMo 3 / Q4_0 ・約548 MB",
                () -> dispatch(ACTION_DOWNLOAD_Q4_0));
        addRow("標準モデルを入手",
                "PLaMo 3 / Q4_K_M ・約573 MB",
                () -> dispatch(ACTION_DOWNLOAD_Q4_K_M));
        addRow("GGUFファイルを読み込む",
                "すでに保存しているモデルを使用する",
                () -> dispatch(ACTION_MODEL_IMPORT));
        addChoice("モデルの世代", "plamo_generation",
                new String[]{"PLaMo 3", "PLaMo 2"},
                new int[]{3, 2}, 3, true);
        addRow("モデルを再読み込み", "モデルファイルの読み直しが必要な場合",
                () -> restartModel());
        addRow("モデルを停止してRAMを解放", "次回利用時に再読み込みする",
                () -> new AlertDialog.Builder(this)
                        .setTitle("モデルの常駐を停止する？")
                        .setMessage("メモリを解放するけど、次の翻訳は少し時間がかかるよ。")
                        .setPositiveButton("停止", (dialog, which) -> {
                            startService(new Intent(this, ModelService.class)
                                    .setAction(ModelService.ACTION_STOP));
                        })
                        .setNegativeButton("キャンセル", null).show());

        addSection("推論・パフォーマンス");
        addRow("デバイス診断・自動最適化",
                "CPU / メモリ / Vulkan対応を確認し、実モデルで2・4スレッドを比較",
                () -> startActivity(new Intent(this, DeviceDiagnosticsActivity.class)));
        addChoice("CPUスレッド", "cpu_threads",
                new String[]{"2スレッド（発熱を抑える）",
                        "4スレッド（標準）"},
                new int[]{2, 4}, 4, true);
        addChoice("コンテキスト長", "ctx_size",
                new String[]{"2048トークン（省メモリ）",
                        "3072トークン（標準）",
                        "4096トークン（長文向け）"},
                new int[]{2048, 3072, 4096}, 3072, true);
        addChoice("最大生成トークン数", "max_output_tokens",
                new String[]{"256（速い・短い訳文向け）",
                        "512（標準）", "1024", "1536"},
                new int[]{256, 512, 1024, 1536}, 512, false);

        addSection("翻訳");
        addDescription("長文のぶつ切り対策：文単位で順番に翻訳する。"
                + "段落や見出しが途中で終わっても残りを続ける。");
        addChoice("1回の翻訳単位の最大文字数", "chunk_chars",
                new String[]{"160文字（短文重視）", "220文字（標準）",
                        "360文字（長文優先）"},
                new int[]{160, 220, 360}, 220, false);
        addSwitch("入力した文章を自動翻訳",
                "入力停止後に自動で翻訳する", "auto_translate", true);

        addSection("表示・操作");
        addChoice("ポップアップ位置", "popup_position",
                new String[]{"中央（推奨）", "画面下部"},
                new int[]{0, 1}, 0, false);
        addChoice("翻訳文字の大きさ", "result_font_size",
                new String[]{"小 14sp", "標準 17sp", "大 20sp"},
                new int[]{14, 17, 20}, 17, false);
        addSwitch("原文の確認カードを表示",
                "選択メニューから翻訳したときの原文カード",
                "show_source_preview", false);
        addDescription("翻訳・設定は端末内で保存。"
                + "モデルのダウンロード時以外は外部へ原文を送信しない。");
    }

    private void addSection(String section) {
        TextView heading = label(section, 16, BLUE, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(4), dp(24), dp(4), dp(10));
        content.addView(heading, params);
    }

    private void addDescription(String description) {
        TextView text = label(description, 12, MUTED, false);
        text.setPadding(dp(11), dp(8), dp(11), dp(10));
        content.addView(text);
    }

    private void addRow(String name, String description, Runnable onClick) {
        LinearLayout card = vertical();
        card.setPadding(dp(16), dp(13), dp(16), dp(13));
        card.setBackground(panel(0xFFFFFFFF, 16, BORDER));

        TextView heading = label(name, 15, INK, true);
        card.addView(heading);
        if (description != null && !description.isEmpty()) {
            TextView detail = label(description, 12, MUTED, false);
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            detailParams.topMargin = dp(4);
            card.addView(detail, detailParams);
        }
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        content.addView(card, params);
    }

    private void addChoice(String name, String key, String[] items,
            int[] values, int defaultValue, boolean restart) {
        int selected = preferences.getInt(key, defaultValue);
        String current = items[0];
        int selectedIndex = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == selected) {
                current = items[i];
                selectedIndex = i;
                break;
            }
        }
        final int checked = selectedIndex;
        addRow(name, current, () -> new AlertDialog.Builder(this)
                .setTitle(name)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    int updated = values[which];
                    preferences.edit().putInt(key, updated).apply();
                    if (restart) restartModel();
                    dialog.dismiss();
                    render();
                })
                .setNegativeButton("キャンセル", null).show());
    }

    private void addSwitch(String name, String detail,
            String key, boolean defaultValue) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setGravity(Gravity.CENTER_VERTICAL);
        wrapper.setPadding(dp(14), dp(9), dp(10), dp(9));
        wrapper.setBackground(panel(0xFFFFFFFF, 16, BORDER));
        LinearLayout labels = vertical();
        labels.addView(label(name, 14, INK, true));
        labels.addView(label(detail, 11, MUTED, false));
        wrapper.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch toggle = new Switch(this);
        toggle.setChecked(preferences.getBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((view, checked) ->
                preferences.edit().putBoolean(key, checked).apply());
        wrapper.addView(toggle);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        content.addView(wrapper, params);
    }

    private void dispatch(String action) {
        Intent result = new Intent();
        result.putExtra(EXTRA_ACTION, action);
        setResult(RESULT_OK, result);
        finish();
    }

    private void restartModel() {
        File file = new File(getFilesDir(), "plamo-translate.gguf");
        if (file.isFile()) {
            startForegroundService(new Intent(this, ModelService.class)
                    .setAction(ModelService.ACTION_RELOAD));
        }
    }
}
