package dev.poteto.plamoselect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Keeps the PLaMo model loaded independently of the translation popup.
 *
 * A foreground service owns the native llama.cpp process, so closing the
 * ACTION_PROCESS_TEXT Activity no longer destroys the server or unloads GGUF.
 * The user can explicitly release memory using the notification or popup.
 *
 * Android may still stop a foreground service under resource pressure or
 * device reboot. In those cases the next launch must load the model again.
 */
public final class ModelService extends Service {
    static final String ACTION_START = "dev.poteto.plamoselect.START";
    static final String ACTION_RELOAD = "dev.poteto.plamoselect.RELOAD";
    static final String ACTION_STOP = "dev.poteto.plamoselect.STOP";
    private static final String CHANNEL_ID = "plamo-model-resident";
    private static final int NOTIFICATION_ID = 1401;
    private static final String MODEL_NAME = "plamo-translate.gguf";
    private static final String SERVER_NAME = "libplamo_server.so";

    private final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
    private final Object stateLock = new Object();
    private final StringBuilder recentLogs = new StringBuilder();
    private final LocalBinder binder = new LocalBinder();

    private CompletableFuture<Integer> startup;
    private volatile Process serverProcess;
    private volatile int port = -1;
    private volatile boolean stopping;

    final class LocalBinder extends Binder {
        ModelService getService() {
            return ModelService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopAndRelease();
            return START_NOT_STICKY;
        }

        // A user opening the app again can explicitly restart after Stop.
        stopping = false;

        if (!getModelFile().isFile()) {
            // Do not run a memory-resident foreground service without a GGUF.
            stopSelf();
            return START_NOT_STICKY;
        }

        // A visible, ongoing notification makes memory residency explicit.
        Notification notification = createNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        if (ACTION_RELOAD.equals(action)) {
            // Model file has been replaced. Restart exactly once, serialized.
            reloadModel();
        } else {
            startModelIfNeeded();
        }
        return START_NOT_STICKY;
    }

    void stopAndRelease() {
        stopping = true;
        synchronized (stateLock) {
            if (startup != null && !startup.isDone()) {
                startup.completeExceptionally(new IOException("モデル常駐を停止した"));
            }
        }
        destroyNativeServer();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private File getModelFile() {
        return new File(getFilesDir(), MODEL_NAME);
    }

    private Notification createNotification() {
        Intent openIntent = new Intent(this, HomeActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stopIntent = new Intent(this, ModelService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("PLaMo 翻訳")
                .setContentText("モデルをメモリに保持中・タップして翻訳")
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "モデルを停止", stop).build())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "PLaMoモデル常駐", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("選択メニューから高速に翻訳できるようモデルをメモリに維持");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startModelIfNeeded() {
        synchronized (stateLock) {
            if (stopping) {
                return;
            }
            if (startup != null) {
                // Retain the existing server and the model already loaded in RAM.
                if (!startup.isDone()
                        || (!startup.isCompletedExceptionally()
                        && serverProcess != null && serverProcess.isAlive())) {
                    return;
                }
            }
            CompletableFuture<Integer> requested = new CompletableFuture<>();
            startup = requested;
            serverExecutor.execute(() -> startNativeServer(requested, false));
        }
    }

    // A deliberate new translation can restart the model after the user
    // previously stopped residency, without relying on main-thread timing.
    void resumeForUserAction() {
        stopping = false;
        startModelIfNeeded();
    }

    // The Activity may request a model change while this service is already bound.
    void requestModelReload() {
        reloadModel();
    }

    private void reloadModel() {
        synchronized (stateLock) {
            if (stopping) {
                return;
            }
            CompletableFuture<Integer> requested = new CompletableFuture<>();
            startup = requested;
            serverExecutor.execute(() -> startNativeServer(requested, true));
        }
    }

    /**
     * Called on an Activity worker thread, never on the Android main thread.
     * Multiple popup openings share the same future and native server.
     */
    int awaitReadyPort() throws Exception {
        startModelIfNeeded();
        CompletableFuture<Integer> pending;
        synchronized (stateLock) {
            pending = startup;
        }
        if (pending == null) {
            throw new IOException("モデルの開始処理がまだない");
        }
        try {
            return pending.get(155, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            throw new IOException(cause == null ? error.toString() : cause.getMessage(), cause);
        } catch (TimeoutException error) {
            throw new IOException("モデル起動がタイムアウトした", error);
        }
    }

    boolean isModelResident() {
        Process current = serverProcess;
        CompletableFuture<Integer> ready;
        synchronized (stateLock) {
            ready = startup;
        }
        return !stopping && current != null && current.isAlive()
                && ready != null && ready.isDone() && !ready.isCompletedExceptionally();
    }

    private void startNativeServer(CompletableFuture<Integer> requested, boolean forceReload) {
        try {
            // The only place where a native process can be restarted.
            if (forceReload || serverProcess == null || !serverProcess.isAlive()) {
                destroyNativeServer();
                int newPort = launchAndWait();
                if (stopping) {
                    throw new IOException("モデル停止中");
                }
                requested.complete(newPort);
            } else {
                requested.complete(port);
            }
        } catch (Exception error) {
            destroyNativeServer();
            requested.completeExceptionally(error);
        }
    }

    private int launchAndWait() throws Exception {
        File nativeDirectory = new File(getApplicationInfo().nativeLibraryDir);
        File executable = new File(nativeDirectory, SERVER_NAME);
        if (!executable.isFile() || !executable.canExecute()) {
            throw new IOException("APK内のllama-serverを実行できない：" + executable);
        }
        if (!getModelFile().isFile()) {
            throw new IOException("PLaMo GGUFが設定されていない");
        }

        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }

        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.add("-m");
        command.add(getModelFile().getAbsolutePath());
        // Keep the model identifier stable across arbitrary imported GGUF names.
        // The chat-completions request uses model="plamo".
        command.add("--alias");
        command.add("plamo");
        command.add("--host");
        command.add("127.0.0.1");
        command.add("--port");
        command.add(Integer.toString(port));
        command.add("-c");
        // Keep the context-window size user-configurable for different
        // memory budgets; server restarts only when this setting changes.
        int context = getSharedPreferences("plamo_prefs", MODE_PRIVATE)
                .getInt("ctx_size", 3072);
        context = context == 2048 || context == 4096 ? context : 3072;
        command.add(Integer.toString(context));
        int cpuThreads = getSharedPreferences("plamo_prefs", MODE_PRIVATE)
                .getInt("cpu_threads", 4);
        cpuThreads = cpuThreads == 2 ? 2 : 4;
        command.add("-t");
        command.add(Integer.toString(cpuThreads));
        command.add("-tb");
        command.add(Integer.toString(cpuThreads));
        command.add("-ngl");
        command.add("0");
        command.add("-np");
        command.add("1");
        // PLaMo 3's GGUF includes a Jinja chat template which interprets
        // chat_template_kwargs.target_lang. Keep legacy PLaMo 2 behavior.
        if (getSharedPreferences("plamo_prefs", MODE_PRIVATE)
                .getInt("plamo_generation", 3) == 3) {
            command.add("--jinja");
        }
        command.add("--no-prefill-assistant");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.environment().put("LD_LIBRARY_PATH", nativeDirectory.getAbsolutePath());
        synchronized (recentLogs) {
            recentLogs.setLength(0);
        }

        Process started = builder.start();
        serverProcess = started;
        Thread collector = new Thread(() -> collectLogs(started), "plamo-server-logs");
        collector.setDaemon(true);
        collector.start();

        long until = System.currentTimeMillis() + 135_000L;
        while (System.currentTimeMillis() < until && !stopping) {
            if (!started.isAlive()) {
                throw new IOException("推論サーバー停止：" + started.exitValue() + " " + getRecentLogs());
            }
            if (isServerHealthy(port)) {
                return port;
            }
            Thread.sleep(650);
        }
        throw new IOException("モデルロード失敗またはタイムアウト：" + getRecentLogs());
    }

    private boolean isServerHealthy(int candidatePort) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + candidatePort + "/health").openConnection();
            connection.setConnectTimeout(500);
            connection.setReadTimeout(500);
            return connection.getResponseCode() == 200;
        } catch (IOException ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void collectLogs(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (recentLogs) {
                    recentLogs.append(line).append('\n');
                    if (recentLogs.length() > 4000) {
                        recentLogs.delete(0, recentLogs.length() - 2500);
                    }
                }
            }
        } catch (IOException ignored) {
            // Native process termination closes the stream.
        }
    }

    private String getRecentLogs() {
        synchronized (recentLogs) {
            return recentLogs.substring(Math.max(0, recentLogs.length() - 500));
        }
    }

    private void destroyNativeServer() {
        Process previous = serverProcess;
        serverProcess = null;
        port = -1;
        if (previous != null) {
            previous.destroy();
            try {
                if (!previous.waitFor(500, TimeUnit.MILLISECONDS)) {
                    previous.destroyForcibly();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                previous.destroyForcibly();
            }
        }
    }

    @Override
    public void onDestroy() {
        stopping = true;
        synchronized (stateLock) {
            if (startup != null && !startup.isDone()) {
                startup.completeExceptionally(new IOException("モデル常駐サービスを停止した"));
            }
        }
        destroyNativeServer();
        serverExecutor.shutdownNow();
        super.onDestroy();
    }
}
