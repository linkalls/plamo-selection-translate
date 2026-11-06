package dev.poteto.plamoselect;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * On-device diagnostic for the actually bundled inference backend.
 *
 * GPU capability != GPU inference support. This APK bundles the official
 * llama.cpp Android CPU runtime; therefore the diagnostic only benchmarks
 * CPU thread counts (two vs four) using the user's installed GGUF.
 *
 * Results apply to this model/device/thermal state. No cloud benchmark,
 * remote data collection, or silently enabled Vulkan backend is involved.
 */
public final class DeviceDiagnosticsActivity extends Activity {
    private static final int INK = 0xFF1D1E20;
    private static final int BLUE = 0xFF0B57D0;
    private static final int MUTED = 0xFF647184;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final CountDownLatch connected = new CountDownLatch(1);
    private ModelService modelService;
    private boolean isBound;
    private volatile boolean destroyed;
    private volatile boolean running;
    private TextView diagnostics;
    private TextView progress;
    private TextView runButton;
    private SharedPreferences prefs;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            modelService = ((ModelService.LocalBinder) binder).getService();
            connected.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            modelService = null;
        }
    };

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextColor(color);
        label.setTextSize(size);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("plamo_prefs", MODE_PRIVATE);
        getWindow().setStatusBarColor(0xFFF7F9FC);
        getWindow().setNavigationBarColor(0xFFF7F9FC);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFF7F9FC);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(20));
        scroll.addView(content);
        setContentView(scroll);

        TextView back = text("‹ 設定に戻る", 15, BLUE, true);
        back.setPadding(0, dp(8), 0, dp(16));
        back.setOnClickListener(v -> finish());
        content.addView(back);
        TextView title = text("デバイス診断", 26, INK, true);
        content.addView(title);
        TextView explanation = text(
                "実際の端末・モデルで2/4スレッドの翻訳速度を比較する。"
                + "途中でモデルを2回再読み込みするため、診断中は翻訳しないでね。",
                13, MUTED, false);
        explanation.setPadding(0, dp(12), 0, dp(18));
        content.addView(explanation);

        diagnostics = text(deviceInformation(), 14, INK, false);
        diagnostics.setPadding(dp(15), dp(14), dp(15), dp(14));
        diagnostics.setBackgroundColor(0xFFDCEBFE);
        content.addView(diagnostics);

        progress = text("モデルを読み込んでから診断を開始してね。", 14, INK, false);
        progress.setPadding(0, dp(18), 0, dp(18));
        content.addView(progress);

        runButton = text("実モデルで診断して最適なCPU設定を適用", 15,
                Color.WHITE, true);
        runButton.setGravity(Gravity.CENTER);
        runButton.setPadding(dp(14), dp(16), dp(14), dp(16));
        runButton.setBackgroundColor(BLUE);
        runButton.setOnClickListener(v -> runDiagnostics());
        content.addView(runButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = text("GPUについて：このAPKはCPU版のllama.cppを同梱している。"
                + "Vulkan対応端末でもGPU推論の実測や自動有効化はできない。"
                + "GPU利用には別途GPU対応ランタイムと端末ごとの安定性検証が必要。",
                12, MUTED, false);
        note.setPadding(0, dp(20), 0, 0);
        content.addView(note);

        isBound = bindService(new Intent(this, ModelService.class), connection,
                Context.BIND_AUTO_CREATE);
    }

    private String deviceInformation() {
        ActivityManager manager = (ActivityManager)
                getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        String ram = "不明";
        String gl = "不明";
        if (manager != null) {
            manager.getMemoryInfo(memory);
            ram = String.format(java.util.Locale.JAPAN, "%.1f GB",
                    memory.totalMem / 1_073_741_824.0);
            gl = manager.getDeviceConfigurationInfo().getGlEsVersion();
        }
        PackageManager pm = getPackageManager();
        boolean vulkan = pm.hasSystemFeature(
                PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL);
        File model = new File(getFilesDir(), "plamo-translate.gguf");
        return "端末: " + android.os.Build.MANUFACTURER + " "
                + android.os.Build.MODEL + "\n"
                + "利用可能な論理CPU: " + Runtime.getRuntime().availableProcessors()
                + " コア\n"
                + "RAM: " + ram + "\n"
                + "OpenGL ES: " + gl + "\n"
                + "Vulkan端末対応: " + (vulkan ? "あり" : "検出なし") + "\n"
                + "アプリの推論バックエンド: CPU版（GPU無効）\n"
                + "モデル: " + (model.isFile()
                        ? model.length() / 1_000_000 + " MB" : "未登録") + "\n"
                + "現在: " + prefs.getInt("cpu_threads", 4) + " CPUスレッド";
    }

    private void report(String message) {
        ui.post(() -> {
            if (!destroyed) progress.setText(message);
        });
    }

    private void runDiagnostics() {
        if (running) return;
        File model = new File(getFilesDir(), "plamo-translate.gguf");
        if (!model.isFile()) {
            report("まず設定の『モデル管理』からGGUFを登録してね。");
            return;
        }
        if (!isBound) {
            report("モデルサービスに接続できないため診断を開始できない。");
            return;
        }
        running = true;
        runButton.setEnabled(false);
        runButton.setAlpha(0.45f);
        final int initialThreads = prefs.getInt("cpu_threads", 4);
        report("モデルを準備中…");

        // The user explicitly initiated this action while the activity is
        // foregrounded, satisfying Android foreground-service restrictions.
        try {
            startForegroundService(new Intent(this, ModelService.class)
                    .setAction(ModelService.ACTION_START));
        } catch (RuntimeException error) {
            report("診断を開始できない: " + error.getMessage());
            running = false;
            runButton.setEnabled(true);
            runButton.setAlpha(1f);
            return;
        }

        worker.execute(() -> {
            int best = initialThreads;
            boolean success = false;
            try {
                if (!connected.await(12, TimeUnit.SECONDS)) {
                    throw new IOException("モデルサービスへの接続がタイムアウトした");
                }
                ModelService service = modelService;
                if (service == null) {
                    throw new IOException("モデルサービスが停止している");
                }

                double[] measured = new double[2];
                final int[] candidates = {2, 4};
                for (int i = 0; i < candidates.length; i++) {
                    if (Thread.currentThread().isInterrupted() || destroyed) {
                        throw new IOException("診断が中断された");
                    }
                    int threads = candidates[i];
                    report(threads + "スレッドでモデルを読み込み中…");
                    prefs.edit().putInt("cpu_threads", threads).commit();
                    service.requestModelReload();
                    int port = service.awaitReadyPort();

                    // Warm up caches before timing. Each timed run uses the
                    // same prompt, PLaMo generation mode, and token limit.
                    translateSample(port);
                    double[] trials = new double[2];
                    for (int trial = 0; trial < trials.length; trial++) {
                        report(threads + "スレッドを計測中 "
                                + (trial + 1) + "/" + trials.length);
                        long started = SystemClock.elapsedRealtimeNanos();
                        translateSample(port);
                        trials[trial] = (SystemClock.elapsedRealtimeNanos()
                                - started) / 1_000_000.0;
                    }
                    Arrays.sort(trials);
                    measured[i] = (trials[0] + trials[1]) / 2.0;
                    report(threads + "スレッド: "
                            + Math.round(measured[i]) + " ms");
                }
                // Within a 10% margin, prefer the lower thread count to
                // reduce heat and power usage. This is only a short benchmark.
                best = measured[1] < measured[0] * 0.90 ? 4 : 2;
                success = true;
                report("診断完了\n2スレッド: " + Math.round(measured[0])
                        + " ms\n4スレッド: " + Math.round(measured[1])
                        + " ms\n推奨: " + best + "スレッド\n"
                        + "※短文ベンチマーク。長文・端末温度で変わる場合あり");
            } catch (Exception error) {
                report("診断を完了できなかった: " + error.getMessage()
                        + "\n元の設定を維持するよ。");
            } finally {
                int selected = success ? best : initialThreads;
                prefs.edit().putInt("cpu_threads", selected).commit();
                ModelService service = modelService;
                if (service != null && !destroyed) {
                    // Restore normal resident translation with selected value.
                    service.requestModelReload();
                }
                ui.post(() -> {
                    if (destroyed) return;
                    diagnostics.setText(deviceInformation());
                    runButton.setEnabled(true);
                    runButton.setAlpha(1f);
                    running = false;
                });
            }
        });
    }

    private void translateSample(int port) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + "/completion").openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(3_000);
            connection.setReadTimeout(120_000);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            boolean plamo3 = prefs.getInt("plamo_generation", 3) == 3;
            String text = "Global markets declined after the announcement.";
            String prompt = plamo3
                    ? "<|plamo:tag|>dataset<|plamo:msg|>translation"
                    + "<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>en"
                    + "<|plamo:msg|>" + text
                    + "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ja"
                    + "<|plamo:msg|>"
                    : "JA: " + text;
            JSONObject body = new JSONObject();
            body.put("temperature", 0);
            body.put("stream", false);
            body.put("stop", new JSONArray().put("<|plamo:tag|>")
                    .put("<|plamo:bos|>"));
            if (plamo3) {
                body.put("prompt", prompt);
                body.put("n_predict", 64);
                body.put("cache_prompt", false);
            } else {
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "user")
                        .put("content", prompt));
                body.put("model", "plamo");
                body.put("messages", messages);
                body.put("max_tokens", 64);
            }
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() != 200) {
                throw new IOException("診断用推論HTTP " + connection.getResponseCode());
            }
            StringBuilder result = new StringBuilder();
            try (InputStream input = connection.getInputStream();
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) result.append(line);
            }
            JSONObject response = new JSONObject(result.toString());
            String content = plamo3 ? response.optString("content", "")
                    : response.getJSONArray("choices").getJSONObject(0)
                            .getJSONObject("message").optString("content", "");
            if (content.trim().isEmpty()) {
                throw new IOException("診断用翻訳が空だった");
            }
        } finally {
            connection.disconnect();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
        super.onDestroy();
    }
}
