package com.farproc.switchfiletransfer;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class TransferService extends Service {
    /**
     * Bind to this service, create it if it is not started yet.
     *
     * @param context Android context.
     * @param conn    See {@link Context#bindService(Intent, ServiceConnection, int)}.
     *                <p>
     *                The {@link IBinder} parameter of {@link ServiceConnection#onServiceConnected(ComponentName, IBinder)}
     *                will be a {@link Binder} instance.
     */
    public static void bind(@NonNull final Context context, @NonNull final ServiceConnection conn) {
        if (!context.bindService(new Intent(context, TransferService.class), conn, BIND_AUTO_CREATE)) {
            throw new IllegalStateException();
        }
    }

    /**
     * The State of this service.
     */
    public enum State {
        /**
         * Doing nothing. Waiting for {@link #connect(String, String)}.
         */
        Idle,
        /**
         * Connecting to Wi-Fi.
         */
        Connecting,
        Downloading,
    }

    private State state = State.Idle;

    /**
     * Change to {@code state} and call {@link Listener#onStateChanged(State)} on all
     * listeners if current state is not the same.
     *
     * @param state The new state to change to.
     */
    private void changeToState(State state) {
        if (state.equals(this.state)) {
            return;
        }
        this.state = state;
        for (Listener listener : listeners) {
            listener.onStateChanged(this.state);
        }
    }

    public interface Listener {
        void onRemoveWifiNetworkError(String ssid);

        void onAddWifiNetworkError();

        void onDisconnectWifiError();

        void onEnableWifiNetworkError();

        /**
         * Called when the service can't parse the task file(data.json).
         */
        void onParseTasksError();

        void onCreateFileError();

        void onDownloadItemStateChanged(int pos);

        void onDownloadCompleted();

        void onStateChanged(State state);

    }

    private final Set<Listener> listeners = new HashSet<>();

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new Binder();
    }

    public class Binder extends android.os.Binder {
        public void addListener(Listener listener) {
            Objects.requireNonNull(listener);
            if (listeners.contains(listener)) {
                throw new IllegalArgumentException("already added");
            }
            listeners.add(listener);
            listener.onStateChanged(state);
        }

        public void removeListener(Listener listener) {
            Objects.requireNonNull(listener);
            listeners.remove(listener);
        }

        // Try to connect to the WiFi network.
        public void connect(final String ssid, final String password) {
            final Context context = getApplicationContext();
            final Intent intent = new Intent(context, TransferService.class);
            intent.putExtra("ssid", Objects.requireNonNull(ssid));
            intent.putExtra("password", Objects.requireNonNull(password));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        }

        // Try to disconnect the WiFi previously connected by this service.
        // It's safe to call this method if not connected.
        public void disconnect() {
            TransferService.this.disconnect();
        }

        public DownloadState getDownloadState() {
            return downloadState;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (state != State.Idle) {
            throw new IllegalStateException("Service is already running");
        }
        final String ssid = Objects.requireNonNull(intent.getStringExtra("ssid"));
        final String password = Objects.requireNonNull(intent.getStringExtra("password"));

        startForegroundWithNotification();

        connect(ssid, password);

        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        Log.i("TransferService", "Service onCreate");
        super.onCreate();
        downloadState = readDownloadState(this);
        createNotificationChannel();
        changeToState(State.Idle);
    }

    @Override
    public void onDestroy() {
        Log.i("TransferService", "Service onDestroy");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(FOREGROUND_NOTIFICATION_ID);
        super.onDestroy();
    }

    private static final String NOTIFICATION_CH = "transfer";

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CH,
                    getString(R.string.notification_channel_default),
                    NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void showDownloadCompletedNotification() {
        Objects.requireNonNull(downloadState);
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CH)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(downloadState.consoleName)
                .setContentText(getString(R.string.download_completed))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSilent(true)
                .setContentIntent(PendingIntent.getActivity(getApplicationContext(),
                        0,
                        new Intent(getApplicationContext(), DownloadActivity.class),
                        0));
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(DOWNLOAD_COMPLETED_NOTIFICATION_ID, builder.build());
    }

    private static final int FOREGROUND_NOTIFICATION_ID = 1;
    public static final int DOWNLOAD_COMPLETED_NOTIFICATION_ID = 2;

    private void startForegroundWithNotification() {
        startForegroundWithNotification(getString(R.string.connecting));
    }

    private void startForegroundWithNotification(final String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CH)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setSilent(true);
        if (downloadState != null) {
            builder.setContentTitle(downloadState.consoleName);
        }
        startForeground(FOREGROUND_NOTIFICATION_ID, builder.build());
    }

    private void stop() {
        stopForeground(true);
        stopSelf();
    }

    private void connect(@NonNull final String ssid, @NonNull final String password) {
        deleteSavedDownloadState(this);
        downloadState = null;

        changeToState(State.Connecting);
        startForegroundWithNotification(getString(R.string.fmt_connecting_to, ssid));
        Compat.Instance.connect(this, ssid, password, compatListener);
    }

    private void disconnect() {
        Compat.Instance.disconnect(this, compatListener);
    }

    private final Compat.Listener compatListener = new Compat.Listener() {

        @Override
        public void onRemoveWifiNetworkError(String ssid) {
            for (Listener listener : listeners) {
                listener.onRemoveWifiNetworkError(ssid);
            }
            changeToState(State.Idle);
            stop();
        }

        @Override
        public void onAddWifiNetworkError() {
            for (Listener listener : listeners) {
                listener.onAddWifiNetworkError();
            }
            changeToState(State.Idle);
            stop();
        }

        @Override
        public void onDisconnectWifiError() {
            for (Listener listener : listeners) {
                listener.onDisconnectWifiError();
            }
            changeToState(State.Idle);
            stop();
        }

        @Override
        public void onEnableWifiNetworkError() {
            for (Listener listener : listeners) {
                listener.onEnableWifiNetworkError();
            }
            changeToState(State.Idle);
            stop();
        }

        @Override
        public void onNetworkAvailable(Network network) {
            startDownload(DEFAULT_HOST);
        }

        @Override
        public void onNetworkUnavailable() {
            changeToState(State.Idle);
            stop();
        }

        @Override
        public void onNetworkLost() {
            changeToState(State.Idle);
            stop();
        }
    };

    private static class JsonData {
        public final String consoleName;
        public final String[] urls;

        public JsonData(final String consoleName, final String[] urls) {
            this.consoleName = consoleName;
            this.urls = urls;
        }
    }

    // Executor to run non-ui threads.
    private final Executor executor = Executors.newFixedThreadPool(4);

    private static final String DEFAULT_HOST = "192.168.0.1";
    private static final String PROTOCOL = "http";


    // read data.json to get files to download.
    private JsonData readDataJson(final String host) throws IOException, JSONException {
        final StringBuilder sb = new StringBuilder();
        final URL url = new URL(PROTOCOL, host, "data.json");
        final URLConnection conn = url.openConnection();
        conn.connect();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(2000);
        try (final Reader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            int c = reader.read();
            while (c != -1) {
                sb.append((char) c);
                c = reader.read();
            }
        }
        Log.i("data.json", sb.toString());
        final JSONObject json = new JSONObject(sb.toString());
        final String consoleName = json.getString("ConsoleName");
        final JSONArray array = json.getJSONArray("FileNames");
        final String[] urls = new String[array.length()];
        for (int i = 0; i < array.length(); i++) {
            urls[i] = String.format("%s://%s/img/%s", PROTOCOL, host, array.getString(i));
        }
        return new JsonData(consoleName, urls);
    }

    public static class DownloadItem implements Serializable {
        static final long serialVersionUID = 1L;

        static final int STATE_DOWNLOADING = 0;
        static final int STATE_COMPLETED = 1;
        static final int STATE_ERROR = -1;

        public final boolean isVideo;
        public String fileUri;
        public int state = STATE_DOWNLOADING;

        public DownloadItem(final boolean isVideo) {
            this.isVideo = isVideo;
        }
    }

    public static class DownloadState implements Serializable {
        static final long serialVersionUID = 1L;

        final String consoleName;
        final DownloadItem[] items;

        public DownloadState(final String consoleName, final DownloadItem[] items) {
            this.consoleName = consoleName;
            this.items = items;
        }
    }

    private static final String DOWNLOAD_STATE_SER_FILE_NAME = "download_state.ser";

    private static void writeDownloadState(final Context context, final DownloadState state) {
        final File f = new File(context.getFilesDir(), DOWNLOAD_STATE_SER_FILE_NAME);
        try {
            try (final ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream(f))) {
                stream.writeObject(state);
            }
        } catch (IOException e) {
            Log.e("TransferService", "writeDownloadState", e);
        }
    }

    private static DownloadState readDownloadState(final Context context) {
        final File f = new File(context.getFilesDir(), DOWNLOAD_STATE_SER_FILE_NAME);
        try {
            try (final ObjectInputStream stream = new ObjectInputStream(new FileInputStream(f))) {
                return (DownloadState) stream.readObject();
            }
        } catch (Exception e) { // Catch all exceptions here. java.io.InvalidClassException etc.
            Log.i("TransferService", "readDownloadState", e);
        }
        return null;
    }

    private static void deleteSavedDownloadState(final Context context) {
        if (!new File(context.getFilesDir(), DOWNLOAD_STATE_SER_FILE_NAME).delete()) {
            Log.i("TransferService", "delete saved download state file failed.");
        }
    }


    private DownloadState downloadState;

    private void startDownload(final String host) {
        executor.execute(() -> {
            JsonData data = null;
            for (int retries = 0; retries < 3; retries++) {
                Log.i("readDataJson", String.format("%d time", retries + 1));
                try {
                    data = readDataJson(host);
                    break;
                } catch (Exception e) {
                    Log.e("readDataJson", "", e);
                }
                try {
                    Thread.sleep(1000 * (retries + 1));
                } catch (InterruptedException e) {
                    Log.e("readDataJson", "", e);
                }
            }
            final JsonData jsonData = data;
            Application.handler.post(() -> {
                if (jsonData == null) {
                    for (Listener listener : listeners) {
                        listener.onParseTasksError();
                    }
                    Compat.Instance.disconnect(this, compatListener);
                    changeToState(State.Idle);
                    return;
                }

                final DownloadItem[] downloadItems = new DownloadItem[jsonData.urls.length];
                final URL[] urls = new URL[jsonData.urls.length];
                try {
                    for (int i = 0; i < jsonData.urls.length; i++) {
                        downloadItems[i] = new DownloadItem(jsonData.urls[i].endsWith(".mp4"));
                        urls[i] = new URL(jsonData.urls[i]);
                    }
                } catch (MalformedURLException e) {
                    Log.e("data.json", "url", e);
                    for (Listener listener : listeners) {
                        listener.onParseTasksError();
                    }
                    Compat.Instance.disconnect(this, compatListener);
                    changeToState(State.Idle);
                    return;
                }
                String consoleName = jsonData.consoleName;
                if (consoleName == null || consoleName.isEmpty()) {
                    consoleName = getString(R.string.default_console_name);
                }
                downloadState = new DownloadState(consoleName, downloadItems);
                changeToState(State.Downloading);
                startDownload(urls);
            });
        });
    }

    private void startDownload(final URL[] urls) {
        // Should be `int remains = downloadItems.length`, but it will be used in anonymous class.
        final int[] remains = new int[]{downloadState.items.length};
        startForegroundWithNotification(getString(R.string.fmt_remaining, remains[0]));
        for (int i = 0; i < urls.length; i++) {
            final URL url = urls[i];
            final DownloadItem item = downloadState.items[i];
            final int pos = i;
            item.fileUri = Compat.Instance.createDownloadFile(this, new File(url.getPath()).getName(), item.isVideo).toString();
            if (item.fileUri == null) {
                Log.e("download", "can't create file ");
                for (Listener listener : listeners) {
                    listener.onCreateFileError();
                }
                Compat.Instance.disconnect(this, compatListener);
                changeToState(State.Idle);
                return;
            }

            Log.i("Download", String.format("remaining: %d", remains[0]));

            executor.execute(() -> {
                try {
                    try (OutputStream outputStream = new BufferedOutputStream(getContentResolver().openOutputStream(Uri.parse(item.fileUri)));
                         InputStream inputStream = url.openStream()) {
                        int c = inputStream.read();
                        while (c != -1) {
                            outputStream.write(c);
                            c = inputStream.read();
                        }
                    }
                    Application.handler.post(() -> item.state = DownloadItem.STATE_COMPLETED);
                } catch (IOException e) {
                    Application.handler.post(() -> item.state = DownloadItem.STATE_ERROR);
                    Log.e("download", "", e);
                }
                Application.handler.post(() -> {
                    //saveDownloadState();
                    for (Listener listener : listeners) {
                        listener.onDownloadItemStateChanged(pos);
                    }
                    if (--remains[0] == 0) {
                        writeDownloadState(this, downloadState);
                        showDownloadCompletedNotification();
                        stop();
                        for (Listener listener : listeners) {
                            listener.onDownloadCompleted();
                        }
                        changeToState(State.Idle);
                        stopForeground(true);
                        Compat.Instance.disconnect(this, compatListener);
                    } else {
                        startForegroundWithNotification(getString(R.string.fmt_remaining, remains[0]));
                        Log.i("Download", String.format("remaining: %d", remains[0]));
                    }
                });
            });
        }
    }
}
