package dev.poteto.plamoselect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A translation-first Material-inspired Android UI.
 *
 * - PROCESS_TEXT: a floating, accessible, two-card popup above the source app.
 * - Launcher: the same UI as a full-screen translation workspace.
 * - One action to translate selected text; no clipboard/overlay permission.
 * - The model belongs to ModelService, not to this short-lived Activity.
 * - Every translated byte is sent only to localhost (127.0.0.1).
 *
 * Implementation intentionally uses platform Android widgets rather than a
 * WebView, avoiding external JavaScript, fonts, telemetry, and network APIs.
 */
public class TranslateActivity extends Activity {
    private static final int REQUEST_MODEL = 2307;
    private static final int REQUEST_SETTINGS = 2308;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 512;
    private static final String MODEL_FILENAME = "plamo-translate.gguf";

    private static final int BLUE = 0xFF0B57D0;
    private static final int BLUE_LIGHT = 0xFFDCEBFE;
    private static final int BLUE_DARK = 0xFF102A56;
    private static final int BACKGROUND = 0xFFF7F9FC;
    private static final int CARD = 0xFFFFFFFF;
    private static final int INK = 0xFF1D1E20;
    private static final int MUTED = 0xFF65717F;
    private static final int STROKE = 0xFFE1E6EF;

    // mode 0: source auto-detect, mode 1: English to Japanese,
    // mode 2: Japanese to English.
    private volatile int directionMode = 0;
    private boolean suppressTextChanges;
    private boolean isProcessTextRequest;
    private boolean isReadOnlySelection;
    private volatile boolean changingModel;
    private volatile boolean destroyed;
    private volatile int translationPort = -1;
    private volatile HttpURLConnection activeRequest;
    private final AtomicInteger latestRequest = new AtomicInteger();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CountDownLatch serviceConnected = new CountDownLatch(1);
    private boolean boundToService;
    private volatile ModelService modelService;

    private ScrollView scrollView;
    private ScrollView resultScroller;
    private LinearLayout sourceCardContainer;
    private EditText sourceEdit;
    private TextView targetText;
    private TextView sourceLanguageChip;
    private TextView targetLanguageChip;
    private TextView statusText;
    private TextView modelStateText;
    private TextView characterCount;
    private TextView translateAction;
    private TextView replaceAction;
    private TextView pasteAction;
    private ImageButton copyAction;
    private ImageButton shareAction;
    private ImageButton speakAction;
    private ProgressBar progress;
    private View resultActionRow;

    private String translatedText = "";
    private String lastTarget = "ja";
    private TextToSpeech tts;
    private boolean ttsReady;
    private Runnable debounce;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder binder) {
            modelService = ((ModelService.LocalBinder) binder).getService();
            serviceConnected.countDown();
            if (!destroyed && modelService.isModelResident()) {
                setStatus("モデル準備完了・オフライン", false);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            modelService = null;
        }
    };

    protected boolean isHomeScreen() {
        return false;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // On phone hardware, launcher and Android text-selection are separate
        // entry points. Text selection always starts in a small popup.
        isProcessTextRequest = Intent.ACTION_PROCESS_TEXT.equals(getIntent().getAction());
        isReadOnlySelection = getIntent().getBooleanExtra(
                Intent.EXTRA_PROCESS_TEXT_READONLY, false);

        buildInterface();
        boundToService = bindService(new Intent(this, ModelService.class),
                connection, Context.BIND_AUTO_CREATE);

        if (modelPath().isFile()) {
            startPersistentService(ModelService.ACTION_START);
        }
        readIncomingIntent(getIntent());

        // One-time setup, similar to choosing an offline language pack in
        // Google Translate. Never download hundreds of MB without consent.
        if (!modelPath().isFile() && state == null) {
            mainHandler.postDelayed(this::showModelSetup, 400);
        }

        tts = new TextToSpeech(getApplicationContext(), status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window == null) {
            return;
        }
        if (isHomeScreen()) {
            // The main app uses the whole screen; the source app remains behind
            // only for the selection popup.
            window.setStatusBarColor(BACKGROUND);
            window.setNavigationBarColor(BACKGROUND);
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        } else {
            int width = getResources().getDisplayMetrics().widthPixels;
            int height = getResources().getDisplayMetrics().heightPixels;
            int position = getSharedPreferences("plamo_prefs", MODE_PRIVATE)
                    .getInt("popup_position", 0);
            window.setGravity(position == 0
                    ? Gravity.CENTER : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.30f);
            // Keep the source app clearly visible, even on smaller screens.
            boolean showOriginal = getSharedPreferences("plamo_prefs", MODE_PRIVATE)
                    .getBoolean("show_source_preview", false);
            window.setLayout(Math.min(dp(460), width - dp(16)),
                    Math.min(dp(showOriginal ? 550 : 445),
                            (int) (height * 0.77f)));
            window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        isProcessTextRequest = Intent.ACTION_PROCESS_TEXT.equals(intent.getAction());
        isReadOnlySelection = intent.getBooleanExtra(
                Intent.EXTRA_PROCESS_TEXT_READONLY, false);
        readIncomingIntent(intent);
        updateActionState();
    }

    private void buildInterface() {
        // All dimensions are in dp and work down to compact screens.
        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);
        scrollView.setBackgroundColor(isHomeScreen() ? BACKGROUND : Color.TRANSPARENT);

        LinearLayout outer = vertical();
        outer.setPadding(dp(13), dp(isHomeScreen() ? 26 : 11),
                dp(13), dp(14));
        scrollView.addView(outer);

        LinearLayout shell = vertical();
        shell.setPadding(dp(isHomeScreen() ? 18 : 10),
                dp(14), dp(isHomeScreen() ? 18 : 10), dp(18));
        shell.setBackground(round(CARD, isHomeScreen() ? 28 : 24, STROKE, 0));
        if (isHomeScreen()) {
            shell.setElevation(dp(2));
        }
        outer.addView(shell, fillWrap());

        buildHeader(shell);
        buildLanguageSelector(shell);
        if (isHomeScreen()) {
            // Main app: input-first, standard translation editor.
            buildSourceCard(shell);
            buildTranslationCard(shell);
            buildFooter(shell);
        } else {
            // Selection popup: result-first, compact and immediately useful.
            // The source remains editable below; it must not hide the result.
            buildTranslationCard(shell);
            buildFooter(shell);
            buildSourceCard(shell);
        }
        setContentView(scrollView);

        updateLanguageLabels();
        updateActionState();
    }

    private void buildHeader(LinearLayout root) {
        LinearLayout header = horizontal(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(6), 0, dp(3), dp(2));

        TextView logo = label("P", 17, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(BLUE, 14, BLUE, 0));
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        header.addView(logo, logoParams);

        LinearLayout titleBlock = vertical();
        titleBlock.setPadding(dp(10), 0, 0, 0);
        TextView title = label("PLaMo 翻訳", 19, INK, true);
        titleBlock.addView(title);
        TextView byline = label("ON-DEVICE TRANSLATION", 9, MUTED, false);
        byline.setLetterSpacing(0.12f);
        titleBlock.addView(byline, marginTop(1));
        header.addView(titleBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageButton settingsButton = icon(R.drawable.ic_plamo_settings,
                "モデル・翻訳設定", () -> openSettings(), MUTED, 44);
        header.addView(settingsButton, sized(44,44));

        if (!isHomeScreen()) {
            ImageButton closeButton = icon(R.drawable.ic_plamo_close,
                    "翻訳ウィンドウを閉じる", this::finish, MUTED, 44);
            header.addView(closeButton, sized(44,44));
        }
        root.addView(header, marginBottom(12));
    }

    private void buildLanguageSelector(LinearLayout root) {
        LinearLayout selector = horizontal(Gravity.CENTER_VERTICAL);
        selector.setPadding(dp(2), 0, dp(2), 0);

        sourceLanguageChip = chip("言語を検出", BLUE_DARK, 0xFFF1F5FB);
        sourceLanguageChip.setOnClickListener(this::showSourceLanguageMenu);
        selector.addView(sourceLanguageChip, new LinearLayout.LayoutParams(
                0, dp(45), 1));

        ImageButton swap = icon(R.drawable.ic_plamo_swap,
                "翻訳方向を入れ替える", this::swapLanguages, BLUE, 44);
        LinearLayout.LayoutParams swapParams = sized(44, 44);
        swapParams.setMargins(dp(3), 0, dp(3), 0);
        selector.addView(swap, swapParams);

        targetLanguageChip = chip("日本語", BLUE, BLUE_LIGHT);
        targetLanguageChip.setOnClickListener(this::showTargetLanguageMenu);
        selector.addView(targetLanguageChip, new LinearLayout.LayoutParams(
                0, dp(45), 1));

        root.addView(selector, marginBottom(13));
    }

    private void buildSourceCard(LinearLayout root) {
        LinearLayout container = vertical();
        sourceCardContainer = container;
        container.setPadding(dp(16), dp(12), dp(16), dp(10));
        container.setBackground(round(CARD, 22, STROKE, 1));

        LinearLayout labelRow = horizontal(Gravity.CENTER_VERTICAL);
        TextView label = label(
                isHomeScreen() ? "原文" : "原文（下で確認・修正）",
                12, MUTED, true);
        labelRow.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageButton clear = icon(R.drawable.ic_plamo_close,
                "入力をすべて消去", () -> sourceEdit.setText(""), MUTED, 35);
        labelRow.addView(clear, sized(35,35));
        container.addView(labelRow);

        sourceEdit = new EditText(this);
        sourceEdit.setTextSize(isHomeScreen() ? 17 : 14);
        sourceEdit.setTextColor(INK);
        sourceEdit.setHintTextColor(0xFF9AA3AE);
        sourceEdit.setHint("翻訳するテキストを入力");
        sourceEdit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        sourceEdit.setBackgroundColor(Color.TRANSPARENT);
        sourceEdit.setGravity(Gravity.TOP | Gravity.START);
        sourceEdit.setMinLines(isHomeScreen() ? 5 : 1);
        sourceEdit.setMaxLines(isHomeScreen() ? 12 : 3);
        // A selected article may be thousands of characters; never resize
        // the popup to fit all of it. The EditText scrolls internally.
        if (!isHomeScreen()) {
            sourceEdit.setVerticalScrollBarEnabled(true);
            sourceEdit.setMovementMethod(
                    android.text.method.ScrollingMovementMethod.getInstance());
            sourceEdit.setMaxHeight(dp(78));
            sourceEdit.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        }
        sourceEdit.setPadding(0, dp(8), 0, dp(10));
        sourceEdit.setSelectAllOnFocus(false);
        sourceEdit.setSingleLine(false);

        sourceEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start,
                    int count, int after) {}

            @Override public void onTextChanged(CharSequence text, int start,
                    int before, int count) {
                if (!suppressTextChanges) {
                    onSourceChanged(text.toString());
                }
            }

            @Override public void afterTextChanged(Editable text) {}
        });
        container.addView(sourceEdit, fillWrap());

        LinearLayout tools = horizontal(Gravity.CENTER_VERTICAL);
        pasteAction = label("貼り付け", 13, BLUE, true);
        pasteAction.setPadding(dp(7), dp(8), dp(10), dp(8));
        pasteAction.setCompoundDrawablePadding(dp(6));
        Drawable pasteIcon = getDrawable(R.drawable.ic_plamo_paste);
        if (pasteIcon != null) {
            pasteIcon = pasteIcon.mutate();
            pasteIcon.setTint(BLUE);
            pasteIcon.setBounds(0, 0, dp(17), dp(17));
            pasteAction.setCompoundDrawables(pasteIcon, null, null, null);
        }
        pasteAction.setOnClickListener(v -> pasteFromClipboard());
        tools.addView(pasteAction, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        characterCount = label("0文字", 11, MUTED, false);
        tools.addView(characterCount);
        container.addView(tools);

        if (!isHomeScreen() && !getSharedPreferences("plamo_prefs", MODE_PRIVATE)
                .getBoolean("show_source_preview", false)) {
            container.setVisibility(View.GONE);
        }
        root.addView(container, marginBottom(isHomeScreen() ? 12 : 3));
    }

    private void buildTranslationCard(LinearLayout root) {
        LinearLayout translatedCard = vertical();
        translatedCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        translatedCard.setBackground(round(BLUE_LIGHT, 22, BLUE_LIGHT, 0));

        LinearLayout labelRow = horizontal(Gravity.CENTER_VERTICAL);
        TextView label = label("翻訳", 12, BLUE_DARK, true);
        labelRow.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleSmall);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(BLUE));
        progress.setVisibility(View.GONE);
        labelRow.addView(progress, sized(19, 19));
        translatedCard.addView(labelRow);

        int resultFont = getSharedPreferences("plamo_prefs", MODE_PRIVATE)
                .getInt("result_font_size", 17);
        targetText = label("ここに翻訳結果が表示される",
                isHomeScreen() ? Math.max(16, resultFont) : resultFont,
                MUTED, false);
        targetText.setTextIsSelectable(true);
        targetText.setGravity(Gravity.TOP | Gravity.START);
        targetText.setPadding(0, dp(isHomeScreen() ? 17 : 10),
                0, dp(isHomeScreen() ? 18 : 10));
        if (isHomeScreen()) {
            translatedCard.addView(targetText, fillWrap());
        } else {
            // Inner scrolling keeps copy/share/speak/replace reachable even
            // when translating a long fictional article or an entire paragraph.
            resultScroller = new ScrollView(this);
            resultScroller.setFillViewport(false);
            resultScroller.setVerticalScrollBarEnabled(true);
            resultScroller.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            resultScroller.addView(targetText, fillWrap());
            LinearLayout.LayoutParams scrollingParams = fillWrap();
            // Dynamic height below starts small rather than wasting the
            // whole popup for a translated headline.
            scrollingParams.height = dp(96);
            translatedCard.addView(resultScroller, scrollingParams);
        }

        statusText = label("端末内で翻訳・通信不要", 11, MUTED, false);
        translatedCard.addView(statusText, marginBottom(7));

        resultActionRow = horizontal(Gravity.CENTER_VERTICAL);
        copyAction = icon(R.drawable.ic_plamo_copy,
                "翻訳結果をコピー", this::copyTranslation, BLUE_DARK, 44);
        ((LinearLayout) resultActionRow).addView(copyAction, sized(44,44));

        shareAction = icon(R.drawable.ic_plamo_share,
                "翻訳結果を共有", this::shareTranslation, BLUE_DARK, 44);
        ((LinearLayout) resultActionRow).addView(shareAction, sized(44,44));

        speakAction = icon(R.drawable.ic_plamo_volume,
                "翻訳結果をオフラインで読み上げる",
                this::speakTranslation, BLUE_DARK, 44);
        ((LinearLayout) resultActionRow).addView(speakAction, sized(44,44));

        View spacer = new View(this);
        ((LinearLayout) resultActionRow).addView(spacer,
                new LinearLayout.LayoutParams(0, dp(1), 1));

        replaceAction = roundedButton("置換", BLUE, Color.WHITE, 13);
        replaceAction.setOnClickListener(v -> replaceSelection());
        LinearLayout.LayoutParams replaceParams = new LinearLayout.LayoutParams(
                dp(94), dp(43));
        ((LinearLayout) resultActionRow).addView(replaceAction, replaceParams);
        translatedCard.addView(resultActionRow);
        root.addView(translatedCard, marginBottom(13));
    }

    private void updateResultHeight() {
        if (resultScroller == null) return;
        resultScroller.post(() -> {
            if (destroyed || resultScroller == null) return;
            int contentHeight = targetText.getLayout() == null
                    ? dp(96) : targetText.getLayout().getHeight()
                        + targetText.getPaddingTop() + targetText.getPaddingBottom();
            int requested = Math.min(dp(225), Math.max(dp(92), contentHeight));
            ViewGroup.LayoutParams layout = resultScroller.getLayoutParams();
            if (layout != null && layout.height != requested) {
                layout.height = requested;
                resultScroller.setLayoutParams(layout);
            }
        });
    }

    private void displayTranslation(String content, int color) {
        targetText.setText(content);
        targetText.setTextColor(color);
        updateResultHeight();
    }

    private void buildFooter(LinearLayout root) {
        LinearLayout row = horizontal(Gravity.CENTER_VERTICAL);

        modelStateText = label("完全オフライン", 11, MUTED, false);
        row.addView(modelStateText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        translateAction = roundedButton("翻訳する", BLUE, Color.WHITE, 13);
        translateAction.setOnClickListener(v -> startTranslation(true));
        row.addView(translateAction, sized(108, 42));
        root.addView(row);
        if (!isHomeScreen()) {
            TextView toggle = label("原文を表示・編集", 12, BLUE, true);
            toggle.setPadding(dp(8), dp(10), dp(8), dp(8));
            toggle.setOnClickListener(v -> {
                if (sourceCardContainer == null) return;
                boolean open = sourceCardContainer.getVisibility() != View.VISIBLE;
                sourceCardContainer.setVisibility(open ? View.VISIBLE : View.GONE);
                toggle.setText(open ? "原文を閉じる" : "原文を表示・編集");
            });
            root.addView(toggle);
        }
    }

    private void onSourceChanged(String text) {
        cancelPendingTranslation();
        characterCount.setText(text.codePointCount(0, text.length()) + "文字");
        if (directionMode == 0) {
            updateLanguageLabels();
        }
        translatedText = "";
        displayTranslation(text.isEmpty()
                ? "ここに翻訳結果が表示される" : "翻訳を準備中…", MUTED);
        setStatus("端末内で翻訳・通信不要", false);
        updateActionState();

        if (!text.trim().isEmpty() && modelPath().isFile()
                && getSharedPreferences("plamo_prefs", MODE_PRIVATE)
                    .getBoolean("auto_translate", true)) {
            // Debounce text entry so slow on-device models are not invoked on
            // every keystroke, while the text selection path remains instant.
            debounce = () -> startTranslation(false);
            mainHandler.postDelayed(debounce, 750);
        }
    }

    private void showSourceLanguageMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 0, 0, "言語を自動検出");
        menu.getMenu().add(0, 1, 1, "英語");
        menu.getMenu().add(0, 2, 2, "日本語");
        menu.setOnMenuItemClickListener(item -> {
            directionMode = item.getItemId();
            updateLanguageLabels();
            if (!sourceEdit.getText().toString().trim().isEmpty()) {
                startTranslation(true);
            }
            return true;
        });
        menu.show();
    }

    private void showTargetLanguageMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "日本語");
        menu.getMenu().add(0, 2, 1, "英語");
        menu.setOnMenuItemClickListener(item -> {
            directionMode = item.getItemId();
            updateLanguageLabels();
            if (!sourceEdit.getText().toString().trim().isEmpty()) {
                startTranslation(true);
            }
            return true;
        });
        menu.show();
    }

    private void swapLanguages() {
        String source = sourceEdit.getText().toString();
        String result = translatedText;
        String oldTarget = selectedTargetLanguage(source);
        directionMode = oldTarget.equals("ja") ? 2 : 1;
        lastTarget = selectedTargetLanguage(result.isEmpty() ? source : result);

        // If a translation is already available, exchanging both texts gives
        // instant Google-Translate-style feedback, without loading/inferencing.
        if (!result.isEmpty()) {
            cancelPendingTranslation();
            setSourceTextSilently(result);
            translatedText = source;
            targetText.setText(source);
            targetText.setTextColor(INK);
            setStatus("言語を入れ替えた", false);
        } else if (!source.trim().isEmpty()) {
            startTranslation(true);
        }
        updateLanguageLabels();
        updateActionState();
    }

    private void updateLanguageLabels() {
        if (sourceLanguageChip == null || targetLanguageChip == null) return;

        String src = directionMode == 0 ? "言語を検出"
                : directionMode == 1 ? "英語" : "日本語";
        String target = selectedTargetLanguage(
                sourceEdit == null ? "" : sourceEdit.getText().toString());
        sourceLanguageChip.setText(src + " ▾");
        targetLanguageChip.setText((target.equals("ja") ? "日本語" : "英語") + " ▾");
    }

    private String selectedTargetLanguage(String text) {
        if (directionMode == 1) return "ja";
        if (directionMode == 2) return "en";
        return inferTarget(text);
    }

    private String inferTarget(String text) {
        int japanese = 0;
        int latin = 0;
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HAN) {
                japanese++;
            } else if (script == Character.UnicodeScript.LATIN
                    && Character.isLetter(codePoint)) {
                latin++;
            }
        }
        return japanese > 0 && (latin == 0 || japanese * 3 >= latin) ? "en" : "ja";
    }

    private void setSourceTextSilently(String value) {
        suppressTextChanges = true;
        try {
            sourceEdit.setText(value);
            sourceEdit.setSelection(sourceEdit.length());
            characterCount.setText(value.codePointCount(0, value.length()) + "文字");
        } finally {
            suppressTextChanges = false;
        }
    }

    private void readIncomingIntent(Intent intent) {
        if (intent == null) return;
        CharSequence selection = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (Intent.ACTION_PROCESS_TEXT.equals(intent.getAction()) && selection != null) {
            isProcessTextRequest = true;
            setSourceTextSilently(selection.toString());
            translatedText = "";
            displayTranslation("翻訳を準備中…", MUTED);
            updateLanguageLabels();
            updateActionState();

            if (!isHomeScreen()) {
                sourceEdit.clearFocus();
                scrollView.post(() -> scrollView.scrollTo(0, 0));
            }
            if (modelPath().isFile()) {
                startTranslation(true);
            } else {
                setStatus("まず翻訳モデルを登録してね（右上の設定）", true);
            }
        }
    }

    private File modelPath() {
        return new File(getFilesDir(), MODEL_FILENAME);
    }

    private void openSettings() {
        startActivityForResult(new Intent(this, SettingsActivity.class),
                REQUEST_SETTINGS);
    }

    private void showCpuThreadSettings() {
        int current = getSharedPreferences("plamo_prefs", MODE_PRIVATE)
                .getInt("cpu_threads", 4);
        // Thread count cannot be assumed faster simply because it is larger.
        // Different ARM big.LITTLE schedulers may prefer different values.
        new AlertDialog.Builder(this)
                .setTitle("CPU推論のスレッド数")
                .setMessage("Pixelなどのbig.LITTLE端末では4スレッドより"
                        + "2スレッドの方が速い場合もあるよ。"
                        + "\n切り替えると一度だけモデルが再起動する。")
                .setSingleChoiceItems(new String[]{
                        "2スレッド（発熱控えめ）", "4スレッド（標準）"
                }, current == 2 ? 0 : 1, (dialog, choice) -> {
                    int threads = choice == 0 ? 2 : 4;
                    getSharedPreferences("plamo_prefs", MODE_PRIVATE).edit()
                            .putInt("cpu_threads", threads).apply();
                    ModelService service = modelService;
                    if (service != null) service.requestModelReload();
                    setStatus("CPU " + threads + "スレッドに変更。モデル再起動中", false);
                    dialog.dismiss();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void showModelSetup() {
        if (destroyed || modelPath().isFile()) return;
        new AlertDialog.Builder(this)
                .setTitle("オフライン翻訳の準備")
                .setMessage("PLaMo 3 Translateの軽量モデルを最初に1回だけ"
                        + "用意するよ。翻訳時は通信不要。"
                        + "\n\nおすすめ：0.8B / Q4_0（約548 MB・スマホ向け）"
                        + "\nダウンロードするか、保存済みのGGUFを使える。")
                .setPositiveButton("ダウンロード", (dialog, which) ->
                        confirmModelDownload("Q4_0"))
                .setNeutralButton("ファイルから選択", (dialog, which) -> openModelPicker())
                .setNegativeButton("あとで", null)
                .show();
    }

    private void confirmModelDownload(String quantization) {
        final boolean fastModel = "Q4_0".equals(quantization);
        final String variant = fastModel ? "Q4_0（約548 MB）" : "Q4_K_M（約573 MB）";
        new AlertDialog.Builder(this)
                .setTitle("PLaMo 3モデルを入手")
                .setMessage("Preferred Networks公式Hugging Faceから "
                        + variant + " をダウンロードして端末に保存するよ。"
                        + "\nQ4_0は公式がスマホ向けに推奨する形式。"
                        + "\nただしTensor G2上の速度差は実機で未計測。"
                        + "\nWi-Fi推奨。モデルにはPLaMo Community Licenseが適用される。")
                .setPositiveButton("ダウンロード開始",
                        (dialog, which) -> downloadOfficialModel(quantization))
                .setNeutralButton("モデルの詳細", (dialog, which) -> {
                    Intent browser = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://huggingface.co/pfnet/"
                                    + "plamo-3-translate-0.8b-GGUF"));
                    startActivity(browser);
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void downloadOfficialModel(String quantization) {
        if (changingModel) return;
        if (getFilesDir().getUsableSpace() < 800_000_000L) {
            setStatus("空き容量が不足している（800 MB以上推奨）", true);
            return;
        }
        changingModel = true;
        cancelPendingTranslation();
        setStatus("公式PLaMo 3モデルをダウンロード中…", false);
        updateActionState();

        executor.execute(() -> {
            File temporary = new File(getFilesDir(), MODEL_FILENAME + ".partial");
            HttpURLConnection download = null;
            try {
                URL source = new URL("https://huggingface.co/pfnet/"
                        + "plamo-3-translate-0.8b-GGUF/resolve/main/"
                        + "plamo-3-translate-0.8b-" + quantization
                        + ".gguf?download=true");
                download = (HttpURLConnection) source.openConnection();
                download.setConnectTimeout(20_000);
                download.setReadTimeout(60_000);
                download.setInstanceFollowRedirects(true);
                download.setRequestProperty("User-Agent", "PLaMoSelectAndroid/0.3");
                int statusCode = download.getResponseCode();
                if (statusCode < 200 || statusCode >= 300) {
                    throw new IOException("モデル取得HTTP " + statusCode);
                }
                long length = download.getContentLengthLong();
                long transferred = 0;
                long nextProgress = 0;

                try (InputStream input = download.getInputStream();
                        OutputStream output = new FileOutputStream(temporary)) {
                    byte[] buffer = new byte[256 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("ダウンロードを中断した");
                        }
                        output.write(buffer, 0, count);
                        transferred += count;
                        if (transferred >= nextProgress) {
                            long captured = transferred;
                            nextProgress = transferred + 8_000_000L;
                            mainHandler.post(() -> setStatus(
                                    "ダウンロード中：" + captured / 1_000_000
                                            + " / " + (length > 0
                                            ? length / 1_000_000 + " MB"
                                            : "サイズ不明"), false));
                        }
                    }
                    output.flush();
                }
                verifyGGUF(temporary);
                finishModelImport(temporary, 3, quantization);
            } catch (Exception error) {
                temporary.delete();
                mainHandler.post(() -> {
                    changingModel = false;
                    setStatus("ダウンロード失敗：" + error.getMessage(), true);
                    updateActionState();
                });
            } finally {
                if (download != null) download.disconnect();
            }
        });
    }

    private void verifyGGUF(File file) throws IOException {
        if (file.length() < 20_000_000) {
            throw new IOException("GGUFのサイズが不自然に小さい");
        }
        try (FileInputStream reader = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            if (reader.read(magic) != 4 || magic[0] != 'G'
                    || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
                throw new IOException("GGUFではないファイルだよ");
            }
        }
    }

    private void finishModelImport(File temporary, int generation,
            String quantization) throws IOException {
        java.nio.file.Files.move(temporary.toPath(), modelPath().toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        getSharedPreferences("plamo_prefs", MODE_PRIVATE).edit()
                .putInt("plamo_generation", generation)
                .putString("model_quantization", quantization).apply();
        mainHandler.post(() -> {
            changingModel = false;
            ModelService service = modelService;
            if (service != null) {
                service.requestModelReload();
                startPersistentService(ModelService.ACTION_START);
            } else {
                startPersistentService(ModelService.ACTION_RELOAD);
            }
            setStatus("モデルを登録した。起動中…", false);
            updateActionState();
            if (!sourceEdit.getText().toString().trim().isEmpty()) {
                startTranslation(true);
            }
        });
    }

    private void openModelPicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(picker, REQUEST_MODEL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SETTINGS) {
            if (resultCode == RESULT_OK && data != null) {
                String action = data.getStringExtra(SettingsActivity.EXTRA_ACTION);
                if (SettingsActivity.ACTION_MODEL_IMPORT.equals(action)) {
                    openModelPicker();
                } else if (SettingsActivity.ACTION_DOWNLOAD_Q4_0.equals(action)) {
                    confirmModelDownload("Q4_0");
                } else if (SettingsActivity.ACTION_DOWNLOAD_Q4_K_M.equals(action)) {
                    confirmModelDownload("Q4_K_M");
                }
            }
            return;
        }
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        changingModel = true;
        cancelPendingTranslation();
        setStatus("モデルを取り込み中…", false);
        updateActionState();

        String displayName = "";
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                displayName = cursor.getString(0);
            }
        } catch (Exception ignored) {
            // Filename is best effort; the GGUF content magic is authoritative.
        }
        String chosenName = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        final int modelGeneration = chosenName.contains("plamo-2") ? 2 : 3;

        executor.execute(() -> {
            File temporary = new File(getFilesDir(), MODEL_FILENAME + ".partial");
            try (InputStream input = getContentResolver().openInputStream(uri);
                    OutputStream output = new FileOutputStream(temporary)) {
                if (input == null) throw new IOException("ファイルを開けない");
                byte[] block = new byte[256 * 1024];
                int count;
                long total = 0;
                while ((count = input.read(block)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("モデル取り込みを中断した");
                    }
                    output.write(block, 0, count);
                    total += count;
                    if (total % (32 * 1024 * 1024L) < count) {
                        final long copied = total;
                        mainHandler.post(() -> setStatus(
                                "モデルを取り込み中：" + copied / 1_000_000 + " MB", false));
                    }
                }
                output.flush();
                verifyGGUF(temporary);
                finishModelImport(temporary, modelGeneration, "手動インポート");
            } catch (Exception error) {
                temporary.delete();
                mainHandler.post(() -> {
                    changingModel = false;
                    setStatus("モデル取り込み失敗：" + error.getMessage(), true);
                    updateActionState();
                });
            }
        });
    }

    private void stopResidentService() {
        ModelService service = modelService;
        if (service != null) {
            service.stopAndRelease();
        } else {
            stopService(new Intent(this, ModelService.class));
        }
        setStatus("メモリを解放した。次の翻訳時に再ロードする", false);
    }

    private void startPersistentService(String action) {
        try {
            startForegroundService(new Intent(this, ModelService.class).setAction(action));
        } catch (RuntimeException error) {
            setStatus("モデルサービスを開始できない：" + error.getMessage(), true);
        }
    }

    private int awaitModelPort() throws Exception {
        if (!serviceConnected.await(12, TimeUnit.SECONDS)) {
            throw new IOException("翻訳サービスに接続できない");
        }
        ModelService service = modelService;
        if (service == null) {
            throw new IOException("翻訳サービスとの接続が切れた");
        }
        return service.awaitReadyPort();
    }

    private void cancelPendingTranslation() {
        latestRequest.incrementAndGet();
        if (debounce != null) {
            mainHandler.removeCallbacks(debounce);
            debounce = null;
        }
        HttpURLConnection running = activeRequest;
        if (running != null) {
            running.disconnect();
        }
        if (progress != null) progress.setVisibility(View.GONE);
    }

    private void startTranslation(boolean userInitiated) {
        if (changingModel || destroyed) return;
        final String original = sourceEdit.getText().toString();
        if (original.trim().isEmpty()) {
            if (userInitiated) setStatus("先に原文を入力してね", true);
            return;
        }
        if (!modelPath().isFile()) {
            setStatus("まずGGUFモデルを登録してね（右上の設定）", true);
            return;
        }
        cancelPendingTranslation();
        final int request = latestRequest.get();
        final String destination = selectedTargetLanguage(original);
        final int modelGeneration = getSharedPreferences("plamo_prefs", MODE_PRIVATE).getInt("plamo_generation", 3);
        final int capturedDirectionMode = directionMode;
        final int maxTokens = getSharedPreferences("plamo_prefs", MODE_PRIVATE)
                .getInt("max_output_tokens", DEFAULT_MAX_OUTPUT_TOKENS);
        lastTarget = destination;
        translatedText = "";
        displayTranslation("翻訳中…", MUTED);
        setStatus("モデルを準備中…", false);
        progress.setVisibility(View.VISIBLE);
        updateActionState();

        // Start foreground service only when the user is looking at our app.
        ModelService current = modelService;
        if (current != null) current.resumeForUserAction();
        startPersistentService(ModelService.ACTION_START);

        executor.execute(() -> {
            try {
                final int port = awaitModelPort();
                translationPort = port;
                if (isStale(request)) return;
                int maxChars = getSharedPreferences("plamo_prefs", MODE_PRIVATE)
                        .getInt("chunk_chars", 220);
                final List<String> chunks = TranslationSegments.split(original, maxChars);
                if (chunks.isEmpty()) {
                    throw new IOException("翻訳できる文章が見つからない");
                }
                final long startedAt = android.os.SystemClock.elapsedRealtime();
                StringBuilder translated = new StringBuilder();

                for (int i = 0; i < chunks.size(); i++) {
                    if (isStale(request)) return;
                    final int currentIndex = i + 1;
                    mainHandler.post(() -> {
                        if (!isStale(request)) setStatus("翻訳中 " + currentIndex
                                + " / " + chunks.size(), false);
                    });
                    // Stream partial tokens directly into the result card. On
                    // a slow phone the user sees translation immediately after
                    // the first generated token instead of waiting for all.
                    final String completedChunks = translated.toString();
                    String result = callLocalTranslate(chunks.get(i), destination,
                            modelGeneration, capturedDirectionMode, maxTokens, partial -> {
                                if (isStale(request)) return;
                                final String visible = completedChunks
                                        + (completedChunks.isEmpty() ? "" : "\n\n")
                                        + partial;
                                mainHandler.post(() -> {
                                    if (isStale(request)) return;
                                    targetText.setText(visible);
                                    targetText.setTextColor(INK);
                                    long seconds = (android.os.SystemClock
                                            .elapsedRealtime() - startedAt) / 1000;
                                    setStatus("生成中 · " + seconds + "秒経過", false);
                                });
                            });
                    if (result.trim().isEmpty()) {
                        throw new IOException("空の翻訳結果が返った");
                    }
                    if (isStale(request)) return;
                    if (translated.length() > 0) translated.append("\n\n");
                    translated.append(result.trim());
                    final String preview = translated.toString();
                    mainHandler.post(() -> {
                        if (!isStale(request)) {
                            displayTranslation(preview, INK);
                        }
                    });
                }

                mainHandler.post(() -> {
                    if (isStale(request)) return;
                    translatedText = translated.toString();
                    progress.setVisibility(View.GONE);
                    displayTranslation(translatedText, INK);
                    long elapsed = android.os.SystemClock.elapsedRealtime() - startedAt;
                    setStatus(String.format(java.util.Locale.JAPAN,
                            "オフライン翻訳完了 · %.1f秒", elapsed / 1000.0), false);
                    updateActionState();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (isStale(request)) return;
                    progress.setVisibility(View.GONE);
                    displayTranslation("翻訳できなかった", MUTED);
                    setStatus(error.getMessage() == null
                            ? "翻訳エラー" : error.getMessage(), true);
                    updateActionState();
                });
            } finally {
                activeRequest = null;
            }
        });
    }

    private boolean isStale(int request) {
        return destroyed || request != latestRequest.get();
    }

    private List<String> splitIntoChunks(String text, int limit) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + limit);
            if (end < text.length()) {
                int lowerBound = start + limit * 2 / 3;
                for (int position = end - 1; position >= lowerBound; position--) {
                    char ch = text.charAt(position);
                    if (ch == '\n' || ch == '。' || ch == '.' || ch == '!' ||
                            ch == '?' || ch == '！' || ch == '？' || ch == ' ') {
                        end = position + 1;
                        break;
                    }
                }
            }
            if (end < text.length() && end > start
                    && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) chunks.add(chunk);
            start = end;
        }
        return chunks;
    }

    /**
     * PLaMo 3: use Preferred Networks' documented PFML3 translation task
     * through llama.cpp's native /completion endpoint.
     *
     * This fixes the English->Japanese request being treated as an English
     * paraphrasing task. The output language is part of the *token prefix*,
     * rather than an option that depends on the GGUF chat template.
     *
     * PLaMo 2: retain the original JA:/EN: chat instruction.
     */
    private String callLocalTranslate(String source, String destination,
            int modelGeneration, int sourceDirection, int maxTokens,
            java.util.function.Consumer<String> onPartial) throws Exception {
        boolean isPlamo3 = modelGeneration == 3;
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + translationPort
                        + (isPlamo3 ? "/completion" : "/v1/chat/completions"))
                .openConnection();
        activeRequest = connection;
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(240_000);
            connection.setRequestProperty("Content-Type",
                    "application/json; charset=utf-8");
            connection.setRequestProperty("Accept",
                    isPlamo3 ? "text/event-stream" : "application/json");

            JSONObject request = new JSONObject();
            request.put("temperature", 0);
            // llama.cpp /completion emits incremental SSE events when enabled.
            // Legacy PLaMo 2 remains non-streaming for compatibility.
            request.put("stream", isPlamo3);
            request.put("stop", new JSONArray()
                    .put("<|plamo:tag|>")
                    .put("<|plamo:bos|>"));

            if (isPlamo3) {
                // The official model card explicitly recommends direct PFML3
                // for production instead of chat_template_kwargs.
                StringBuilder prompt = new StringBuilder();
                prompt.append("<|plamo:tag|>dataset<|plamo:msg|>translation");
                prompt.append("<|plamo:tag|>input");
                if (sourceDirection != 0) {
                    // The source language is optional, but accurate when
                    // explicitly selected; auto mode detects from the text.
                    prompt.append("<|plamo:key|>lang<|plamo:val|>")
                            .append(destination.equals("ja") ? "en" : "ja");
                }
                prompt.append("<|plamo:msg|>");
                prompt.append(source);
                prompt.append("<|plamo:tag|>output");
                prompt.append("<|plamo:key|>lang<|plamo:val|>")
                        .append(destination);
                prompt.append("<|plamo:msg|>");

                request.put("prompt", prompt.toString());
                request.put("n_predict", maxTokens);
                request.put("cache_prompt", false);
                // llama.cpp inserts the BOS token; do not add it here.
            } else {
                request.put("model", "plamo");
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject()
                        .put("role", "user")
                        .put("content", (destination.equals("ja")
                                ? "JA: " : "EN: ") + source));
                request.put("messages", messages);
                request.put("max_tokens", maxTokens);
            }

            try (OutputStream output = connection.getOutputStream()) {
                output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }

            int statusCode = connection.getResponseCode();
            if (statusCode < 200 || statusCode >= 300) {
                StringBuilder failure = new StringBuilder();
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            failure.append(line);
                            if (failure.length() >= 300) break;
                        }
                    }
                }
                throw new IOException("推論HTTP " + statusCode + "："
                        + failure.substring(0, Math.min(200, failure.length())));
            }

            if (isPlamo3) {
                StringBuilder streamed = new StringBuilder();
                long lastUpdate = android.os.SystemClock.elapsedRealtime();
                boolean receivedFinalEvent = false;
                String completionStopReason = "";

                // Native llama.cpp SSE format: data: {"content":"..."}
                // followed by data: [DONE]. Decode UTF-8 text incrementally.
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(),
                                StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (Thread.currentThread().isInterrupted() || destroyed) {
                            throw new IOException("翻訳を中断した");
                        }
                        if (!line.startsWith("data: ")) continue;
                        String jsonLine = line.substring(6).trim();
                        if ("[DONE]".equals(jsonLine)) break;
                        if (jsonLine.isEmpty()) continue;
                        JSONObject event = new JSONObject(jsonLine);
                        if (event.has("error")) {
                            throw new IOException("推論エラー：" + event.get("error"));
                        }
                        if (event.optBoolean("stop", false)) {
                            receivedFinalEvent = true;
                            completionStopReason = event.optString("stop_type", "");
                        }
                        String token = event.optString("content", "");
                        if (token.isEmpty()) continue;
                        streamed.append(token);

                        // Throttle UI updates; do not post a Runnable per token.
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (now - lastUpdate >= 90) {
                            onPartial.accept(streamed.toString());
                            lastUpdate = now;
                        }
                    }
                }
                if (!receivedFinalEvent) {
                    throw new IOException("推論ストリームが最終応答なしで終了した。"
                            + "途中までの訳文は未確定。再試行してね");
                }
                if ("limit".equals(completionStopReason)) {
                    throw new IOException("生成上限に達したため翻訳が途中終了。"
                            + "設定で最大生成トークン数を増やしてね");
                }
                String result = streamed.toString().replace("<|plamo:bos|>", "")
                        .replace("<|plamo:tag|>", "").trim();
                if (!result.isEmpty()) onPartial.accept(result);
                return result;
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            return new JSONObject(response.toString())
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "").trim();
        } finally {
            activeRequest = null;
            connection.disconnect();
        }
    }

    private void pasteFromClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(
                Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            toast("クリップボードにテキストがないよ");
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence value = clip.getItemAt(0).coerceToText(this);
        if (value != null) {
            sourceEdit.setText(value);
            sourceEdit.setSelection(sourceEdit.length());
            hideKeyboard();
            startTranslation(true);
        }
    }

    private void copyTranslation() {
        if (translatedText.isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(
                Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(
                    ClipData.newPlainText("PLaMo 翻訳", translatedText));
            toast("翻訳結果をコピーしたよ");
        }
    }

    private void shareTranslation() {
        if (translatedText.isEmpty()) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, translatedText);
        startActivity(Intent.createChooser(send, "翻訳結果を共有"));
    }

    private void speakTranslation() {
        if (translatedText.isEmpty()) return;
        if (!ttsReady || tts == null) {
            toast("オフライン音声エンジンを利用できない");
            return;
        }
        Locale language = lastTarget.equals("ja")
                ? Locale.JAPANESE : Locale.ENGLISH;

        // Respect on-device privacy: never select a TTS voice that requires
        // network access. Users may need to install an offline voice first.
        android.speech.tts.Voice offline = null;
        if (tts.getVoices() != null) {
            for (android.speech.tts.Voice voice : tts.getVoices()) {
                if (!voice.isNetworkConnectionRequired()
                        && voice.getLocale().getLanguage().equals(
                                language.getLanguage())) {
                    offline = voice;
                    break;
                }
            }
        }
        if (offline == null) {
            toast("この言語のオフライン音声をAndroidの設定から追加してね");
            return;
        }
        tts.setVoice(offline);
        tts.speak(translatedText, TextToSpeech.QUEUE_FLUSH,
                null, "plamo-translation");
    }

    private void replaceSelection() {
        if (translatedText.isEmpty()) return;
        if (isProcessTextRequest && !isReadOnlySelection) {
            Intent data = new Intent();
            data.putExtra(Intent.EXTRA_PROCESS_TEXT, translatedText);
            setResult(RESULT_OK, data);
            finish();
        }
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null) {
            InputMethodManager manager = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
            focus.clearFocus();
        }
    }

    private void updateActionState() {
        if (copyAction == null) return;
        boolean ready = !translatedText.isEmpty();
        copyAction.setEnabled(ready);
        shareAction.setEnabled(ready);
        speakAction.setEnabled(ready);
        copyAction.setAlpha(ready ? 1f : 0.35f);
        shareAction.setAlpha(ready ? 1f : 0.35f);
        speakAction.setAlpha(ready ? 1f : 0.35f);
        replaceAction.setVisibility(isProcessTextRequest && !isReadOnlySelection
                ? View.VISIBLE : View.GONE);
        replaceAction.setEnabled(ready);
        replaceAction.setAlpha(ready ? 1f : 0.45f);
        translateAction.setEnabled(!changingModel);
        translateAction.setAlpha(changingModel ? 0.5f : 1f);
        if (modelStateText != null) {
            boolean modelPresent = modelPath().isFile();
            modelStateText.setText(modelPresent
                    ? "● 端末内のみ ・ PLaMo" : "● モデル未設定");
        }
    }

    private void setStatus(String text, boolean error) {
        if (statusText == null || destroyed) return;
        statusText.setText(text);
        statusText.setTextColor(error ? 0xFFB3261E : MUTED);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ---- Native Android UI primitives, with Material-style touch targets ----

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal(int gravity) {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(gravity);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout.LayoutParams fillWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams sized(int widthDp, int heightDp) {
        return new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp));
    }

    private LinearLayout.LayoutParams marginBottom(int bottomDp) {
        LinearLayout.LayoutParams p = fillWrap();
        p.bottomMargin = dp(bottomDp);
        return p;
    }

    private LinearLayout.LayoutParams marginTop(int topDp) {
        LinearLayout.LayoutParams p = fillWrap();
        p.topMargin = dp(topDp);
        return p;
    }

    private Drawable round(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) shape.setStroke(dp(strokeDp), strokeColor);
        return shape;
    }

    private Drawable ripple(int color, int radiusDp) {
        Drawable normal = round(color, radiusDp, color, 0);
        return new RippleDrawable(
                ColorStateList.valueOf(0x230B57D0), normal, null);
    }

    private TextView label(String text, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(true);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView chip(String text, int foreground, int background) {
        TextView chip = label(text, 14, foreground, true);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setPadding(dp(9), 0, dp(9), 0);
        chip.setBackground(ripple(background, 18));
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    private TextView roundedButton(String text, int background,
            int foreground, int textSizeSp) {
        TextView button = label(text, textSizeSp, foreground, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), dp(8), dp(10), dp(8));
        button.setBackground(ripple(background, 24));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private ImageButton icon(int resource, String accessibility,
            Runnable action, int tint, int size) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(resource);
        button.setColorFilter(tint);
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(size >= 44 ? 11 : 9),
                dp(size >= 44 ? 11 : 9),
                dp(size >= 44 ? 11 : 9),
                dp(size >= 44 ? 11 : 9));
        button.setBackground(ripple(Color.TRANSPARENT, size / 2));
        button.setContentDescription(accessibility);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        cancelPendingTranslation();
        executor.shutdownNow();
        if (boundToService) {
            unbindService(connection);
            boundToService = false;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        // Foreground ModelService intentionally outlives this Activity.
        super.onDestroy();
    }
}
