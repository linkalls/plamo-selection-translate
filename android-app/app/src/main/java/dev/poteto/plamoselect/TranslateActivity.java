package dev.poteto.plamoselect;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Gboardに一切変更を加えず、Android標準の「テキストを処理」から翻訳するアプリ。
 *
 * 推論サーバーは、このAPKに同梱した公式llama.cppのAndroid ARM64バイナリ。
 * サーバーは127.0.0.1にしかバインドしないため、文章を外部送信しない。
 * GGUFは初回にユーザーがファイルピッカーから選び、専用領域にコピーする。
 */
public final class TranslateActivity extends Activity {
    private static final int REQUEST_MODEL = 2307;
    private static final int CHUNK_LENGTH = 1050;
    private static final int MAX_OUTPUT_TOKENS = 1536;
    private static final String MODEL_FILENAME = "plamo-translate.gguf";

    // The service, not this popup, owns llama-server and the memory-resident GGUF.
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CountDownLatch serviceConnected = new CountDownLatch(1);

    private volatile ModelService modelService;
    private boolean boundToService;
    private volatile int translationPort = -1;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            modelService = ((ModelService.LocalBinder) binder).getService();
            serviceConnected.countDown();
            if (modelService.isModelResident() && statusView != null && !translating) {
                setStatus("モデル常駐中。次の翻訳は再ロード不要！");
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            modelService = null;
        }
    };

    private EditText inputEdit;
    private TextView statusView;
    private TextView modelView;
    private TextView translatedView;
    private Spinner directionSpinner;
    private Button translateButton;
    private Button replaceButton;
    private Button copyButton;
    private Button modelButton;
    private Button stopModelButton;

    private volatile boolean destroyed;
    private volatile boolean translating;
    private boolean isProcessTextRequest;
    private boolean isReadOnlySelection;
    private String translatedText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildInterface();
        // Binding alone does not keep an idle service running; the foreground
        // service below retains the model after this popup is dismissed.
        boundToService = bindService(new Intent(this, ModelService.class),
                connection, Context.BIND_AUTO_CREATE);
        if (modelPath().isFile()) {
            startPersistentService(ModelService.ACTION_START);
        }
        readIncomingIntent(getIntent());
        updateModelLabel();
        updateModelControls();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // A true floating Dialog-themed Activity over the originating app.
        // No SYSTEM_ALERT_WINDOW permission and no Gboard modification needed.
        Window window = getWindow();
        if (window != null) {
            int displayWidth = getResources().getDisplayMetrics().widthPixels;
            int displayHeight = getResources().getDisplayMetrics().heightPixels;
            window.setGravity(Gravity.CENTER);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.4f);
            window.setLayout(Math.min(dp(430), displayWidth - dp(24)),
                    Math.min(dp(550), (int) (displayHeight * 0.72f)));
            window.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void startPersistentService(String action) {
        Intent serviceIntent = new Intent(this, ModelService.class).setAction(action);
        try {
            startForegroundService(serviceIntent);
        } catch (RuntimeException error) {
            setStatus("モデル常駐の開始に失敗：" + error.getMessage());
        }
    }

    private void stopResidentService() {
        ModelService current = modelService;
        if (current != null) {
            current.stopAndRelease();
        } else {
            stopService(new Intent(this, ModelService.class));
        }
        setStatus("モデルをRAMから解放した。次回の利用時に再ロードする。");
    }

    private int awaitResidentModelPort() throws Exception {
        if (!serviceConnected.await(12, TimeUnit.SECONDS)) {
            throw new IOException("モデル常駐サービスに接続できない");
        }
        ModelService current = modelService;
        if (current == null) {
            throw new IOException("モデル常駐サービスが切断された");
        }
        return current.awaitReadyPort();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIncomingIntent(intent);
        updateModelControls();
    }

    // Androidの標準ウィジェットだけで構成し、追加ライブラリやネットワークUIを不要にする。
    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(0xFFFFFFFF);
        panel.setCornerRadius(dp(20));
        root.setBackground(panel);
        scroll.setFillViewport(false);
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("PLaMo 翻訳");
        title.setTextSize(20);
        title.setTextColor(0xFF15334D);
        title.setTypeface(null, 1);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button close = new Button(this);
        close.setText("✕");
        close.setContentDescription("ポップアップを閉じる");
        close.setOnClickListener(v -> finish());
        header.addView(close);
        root.addView(header);

        TextView subtitle = new TextView(this);
        subtitle.setText("選択した文章を端末内で翻訳。モデルは次回も保持するよ。");
        subtitle.setTextSize(12);
        root.addView(subtitle, marginTop(4));

        modelView = new TextView(this);
        modelView.setTextSize(14);
        root.addView(modelView, marginTop(7));

        modelButton = new Button(this);
        modelButton.setText("モデルを取り込む・変更");
        modelButton.setOnClickListener(v -> openModelPicker());
        root.addView(modelButton, marginTop(3));

        stopModelButton = new Button(this);
        stopModelButton.setText("常駐を停止してRAMを解放");
        stopModelButton.setOnClickListener(v -> stopResidentService());
        root.addView(stopModelButton, marginTop(1));

        TextView inputTitle = new TextView(this);
        inputTitle.setText("翻訳する文章");
        inputTitle.setTextSize(16);
        root.addView(inputTitle, marginTop(5));

        inputEdit = new EditText(this);
        inputEdit.setTextSize(15);
        inputEdit.setMinLines(2);
        inputEdit.setMaxLines(5);
        inputEdit.setGravity(android.view.Gravity.TOP);
        inputEdit.setHint("ここに文章を貼り付けてもOK。選択メニューから呼び出せば自動入力される。 ");
        inputEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(inputEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        directionSpinner = new Spinner(this);
        String[] directions = {
                "自動判定（推奨）",
                "英語 → 日本語",
                "日本語 → 英語"
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, directions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        directionSpinner.setAdapter(adapter);
        root.addView(directionSpinner, marginTop(7));

        translateButton = new Button(this);
        translateButton.setText("🔁 オフライン翻訳");
        translateButton.setOnClickListener(v -> startTranslation());
        root.addView(translateButton, marginTop(8));

        statusView = new TextView(this);
        statusView.setText("GGUFを取り込んでから文章を翻訳できるよ。");
        statusView.setTextSize(14);
        root.addView(statusView, marginTop(12));

        TextView resultTitle = new TextView(this);
        resultTitle.setText("翻訳結果");
        resultTitle.setTextSize(16);
        root.addView(resultTitle, marginTop(8));

        translatedView = new TextView(this);
        translatedView.setTextIsSelectable(true);
        translatedView.setTextSize(16);
        translatedView.setPadding(dp(10), dp(13), dp(10), dp(13));
        translatedView.setBackgroundColor(0xFFEAF3F6);
        translatedView.setMinHeight(dp(45));
        root.addView(translatedView, marginTop(8));

        replaceButton = new Button(this);
        replaceButton.setText("選択した文章を翻訳結果で置換");
        replaceButton.setEnabled(false);
        replaceButton.setOnClickListener(v -> replaceSelection());
        root.addView(replaceButton, marginTop(10));

        copyButton = new Button(this);
        copyButton.setText("翻訳結果をコピー");
        copyButton.setEnabled(false);
        copyButton.setOnClickListener(v -> copyTranslation());
        root.addView(copyButton);

        TextView footnote = new TextView(this);
        footnote.setText("長文は約1,050文字ずつ自動分割して翻訳する。" +
                "結果は編集可能なテキスト欄なら置換できる。" +
                "閲覧のみの文章では置換できないのでコピーしてね。");
        footnote.setTextSize(12);
        root.addView(footnote, marginTop(5));

        setContentView(scroll);
    }

    private LinearLayout.LayoutParams marginTop(int dip) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(dip);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ACTION_PROCESS_TEXTのテキストは呼び出し元アプリの選択領域から取得する。
    private void readIncomingIntent(Intent intent) {
        isProcessTextRequest = Intent.ACTION_PROCESS_TEXT.equals(intent.getAction());
        isReadOnlySelection = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false);
        if (isProcessTextRequest && modelPath().isFile()) {
            startPersistentService(ModelService.ACTION_START);
        }
        CharSequence selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (isProcessTextRequest && selected != null) {
            inputEdit.setText(selected);
            translatedText = "";
            translatedView.setText("");
            copyButton.setEnabled(false);
            replaceButton.setEnabled(false);
            if (modelPath().isFile()) {
                // 文章選択 → 翻訳を極力ワンタップに近づける。
                startTranslation();
            } else {
                setStatus("先にGGUFを取り込んでね。文章は保持しているよ。");
            }
        }
    }

    private File modelPath() {
        return new File(getFilesDir(), MODEL_FILENAME);
    }

    private void updateModelLabel() {
        File model = modelPath();
        if (model.isFile() && model.length() > 20_000_000) {
            modelView.setText("モデル：取り込み済み（" + (model.length() / 1_000_000) + " MB）");
        } else {
            modelView.setText("モデル：未設定。PLaMo GGUFを先に選んでね。");
        }
    }

    private void updateModelControls() {
        // Keep the context-menu popup compact; settings remain in the launcher.
        boolean compact = isProcessTextRequest && modelPath().isFile();
        modelButton.setVisibility(compact ? View.GONE : View.VISIBLE);
        stopModelButton.setVisibility(compact ? View.GONE : View.VISIBLE);
        modelView.setVisibility(compact ? View.GONE : View.VISIBLE);
    }

    private void openModelPicker() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("*/*");
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(pick, REQUEST_MODEL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            setStatus("ファイルを取得できなかった。");
            return;
        }
        setStatus("GGUFを取り込み中。コピーが終わるまでアプリを閉じないでね。");
        translateButton.setEnabled(false);

        // SAFのcontent:// URIをllama.cppへ直接渡せないため、アプリ専用領域にコピーする。
        executor.execute(() -> {
            File temporary = new File(getFilesDir(), MODEL_FILENAME + ".partial");
            try (InputStream input = getContentResolver().openInputStream(uri);
                 OutputStream output = new FileOutputStream(temporary)) {
                if (input == null) {
                    throw new IOException("コンテンツを読み込めない");
                }
                byte[] buffer = new byte[256 * 1024];
                long length = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    length += count;
                    if ((length / (32 * 1024 * 1024)) !=
                            ((length - count) / (32 * 1024 * 1024))) {
                        long mb = length / 1_000_000;
                        mainHandler.post(() -> setStatus("GGUF取り込み中：" + mb + " MB"));
                    }
                }
                output.flush();

                // 破損ZIPや別種のファイルを誤ってモデルとして登録しない。
                if (length < 20_000_000) {
                    throw new IOException("GGUFのサイズが不自然に小さい");
                }
                try (FileInputStream verify = new FileInputStream(temporary)) {
                    byte[] magic = new byte[4];
                    if (verify.read(magic) != 4 || magic[0] != 'G' || magic[1] != 'G'
                            || magic[2] != 'U' || magic[3] != 'F') {
                        throw new IOException("GGUFではないよ。ZIPを解凍してGGUFを選択してね。");
                    }
                }
                // コピー完了後の置換のみ許可し、途中の壊れたモデルを使わない。
                File current = modelPath();
                java.nio.file.Files.move(temporary.toPath(), current.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                mainHandler.post(() -> {
                    updateModelLabel();
                    updateModelControls();
                    // Only changing/importing the GGUF reloads the native server.
                    // Reopening a popup never causes a model reload.
                    ModelService currentService = modelService;
                    if (currentService != null) {
                        currentService.requestModelReload();
                        startPersistentService(ModelService.ACTION_START);
                    } else {
                        startPersistentService(ModelService.ACTION_RELOAD);
                    }
                    setStatus("モデルを登録した。常駐ロード開始中…");
                    translateButton.setEnabled(true);
                    if (isProcessTextRequest && inputEdit.length() > 0) {
                        startTranslation();
                    }
                });
            } catch (Exception error) {
                temporary.delete();
                mainHandler.post(() -> {
                    setStatus("モデル取り込み失敗：" + error.getMessage());
                    translateButton.setEnabled(true);
                });
            }
        });
    }

    private void startTranslation() {
        if (translating) {
            return;
        }
        String source = inputEdit.getText().toString();
        if (source.trim().isEmpty()) {
            setStatus("翻訳する文章を入力してね。");
            return;
        }
        if (!modelPath().isFile()) {
            setStatus("先にGGUFを取り込んでね。");
            return;
        }

        // Restart only if the user stopped the service or Android killed it.
        ModelService activeModel = modelService;
        if (activeModel != null) {
            activeModel.resumeForUserAction();
        }
        startPersistentService(ModelService.ACTION_START);

        int mode = directionSpinner.getSelectedItemPosition();
        final String targetLanguage = mode == 1 ? "ja" : mode == 2 ? "en" : inferTarget(source);
        final List<String> chunks = splitIntoChunks(source, CHUNK_LENGTH);
        translating = true;
        translateButton.setEnabled(false);
        replaceButton.setEnabled(false);
        copyButton.setEnabled(false);
        translatedText = "";
        translatedView.setText("");
        setStatus("翻訳を開始。" + chunks.size() + "分割・翻訳先：" +
                (targetLanguage.equals("ja") ? "日本語" : "英語"));

        executor.execute(() -> {
            try {
                translationPort = awaitResidentModelPort();
                StringBuilder translated = new StringBuilder();
                for (int index = 0; index < chunks.size(); index++) {
                    if (Thread.currentThread().isInterrupted() || destroyed) {
                        throw new InterruptedException("翻訳が中断された");
                    }
                    final int current = index + 1;
                    mainHandler.post(() -> setStatus("翻訳中：" + current + " / " + chunks.size()));
                    // 全文を一度だけ言語判定し、分割後の断片で誤判定しないよう方向を固定。
                    String prefix = targetLanguage.equals("ja") ? "JA: " : "EN: ";
                    String part = requestTranslation(prefix + chunks.get(index));
                    if (part.trim().isEmpty()) {
                        throw new IOException("空の翻訳結果が返った（" + current + " / " + chunks.size() + "）");
                    }
                    if (translated.length() > 0) {
                        translated.append("\n\n");
                    }
                    translated.append(part.trim());
                    String partial = translated.toString();
                    mainHandler.post(() -> translatedView.setText(partial));
                }
                translatedText = translated.toString();
                mainHandler.post(() -> {
                    setStatus("翻訳完了！どのアプリにもコピーできるよ。");
                    translateButton.setEnabled(true);
                    copyButton.setEnabled(true);
                    replaceButton.setEnabled(isProcessTextRequest && !isReadOnlySelection);
                });
            } catch (Exception error) {
                String details = error.getMessage();
                mainHandler.post(() -> {
                    setStatus("翻訳エラー：" + details);
                    translateButton.setEnabled(true);
                });
            } finally {
                translating = false;
            }
        });
    }

    /**
     * Unicode Scriptによる簡易言語判定。記事中の「江戸」1語だけで、
     * 英文全体が日本語扱いにならないよう文字数を比較する。
     */
    private String inferTarget(String content) {
        int japanese = 0;
        int latin = 0;
        for (int i = 0; i < content.length(); ) {
            int point = content.codePointAt(i);
            i += Character.charCount(point);
            Character.UnicodeScript script = Character.UnicodeScript.of(point);
            if (script == Character.UnicodeScript.HIRAGANA ||
                    script == Character.UnicodeScript.KATAKANA ||
                    script == Character.UnicodeScript.HAN) {
                japanese++;
            } else if (script == Character.UnicodeScript.LATIN && Character.isLetter(point)) {
                latin++;
            }
        }
        return japanese > 0 && (latin == 0 || japanese * 3 >= latin) ? "en" : "ja";
    }

    /**
     * コンテキストと推論速度を抑えるため分割。句点・英語ピリオド・改行・空白を優先。
     * Androidのテキスト選択は数千文字に達するため、一括での推論を避ける。
     */
    private List<String> splitIntoChunks(String input, int limit) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < input.length()) {
            int end = Math.min(input.length(), start + limit);
            if (end < input.length()) {
                int minEnd = start + limit * 2 / 3;
                for (int at = end - 1; at >= minEnd; at--) {
                    char ch = input.charAt(at);
                    if (ch == '\n' || ch == '。' || ch == '！' || ch == '？' ||
                            ch == '.' || ch == '!' || ch == '?' || ch == ' ') {
                        end = at + 1;
                        break;
                    }
                }
            }
            // サロゲートペアの途中で切らない。emojiなどを壊さないための保険。
            if (end < input.length() && end > start &&
                    Character.isHighSurrogate(input.charAt(end - 1)) &&
                    Character.isLowSurrogate(input.charAt(end))) {
                end--;
            }
            String candidate = input.substring(start, end).trim();
            if (!candidate.isEmpty()) {
                chunks.add(candidate);
            }
            start = end;
        }
        return chunks;
    }

    private String requestTranslation(String source) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + translationPort + "/v1/chat/completions").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(3500);
            connection.setReadTimeout(240_000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JSONObject request = new JSONObject();
            request.put("model", "plamo");
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", source));
            request.put("messages", messages);
            request.put("temperature", 0);
            request.put("max_tokens", MAX_OUTPUT_TOKENS);
            request.put("stop", new JSONArray().put("<|plamo:tag|>").put("<|plamo:bos|>"));
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            StringBuilder reply = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    reply.append(line);
                }
            }
            if (status < 200 || status >= 300) {
                throw new IOException("推論HTTP " + status + "：" + reply.substring(0, Math.min(500, reply.length())));
            }
            JSONObject response = new JSONObject(reply.toString());
            return response.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").optString("content", "");
        } finally {
            connection.disconnect();
        }
    }

    private void replaceSelection() {
        if (translatedText.isEmpty()) {
            return;
        }
        if (isProcessTextRequest && !isReadOnlySelection) {
            // AndroidはRESULT_OKにEXTRA_PROCESS_TEXTを設定すると選択部分を置換する。
            Intent reply = new Intent();
            reply.putExtra(Intent.EXTRA_PROCESS_TEXT, translatedText);
            setResult(RESULT_OK, reply);
            finish();
        } else {
            copyTranslation();
        }
    }

    private void copyTranslation() {
        if (translatedText.isEmpty()) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("PLaMo翻訳", translatedText));
        Toast.makeText(this, "翻訳結果をコピーしたよ", Toast.LENGTH_SHORT).show();
    }

    private void setStatus(String message) {
        statusView.setText(message);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        if (boundToService) {
            unbindService(connection);
            boundToService = false;
        }
        // Deliberately do not stop ModelService: the next popup reuses GGUF in RAM.
        super.onDestroy();
    }
}
