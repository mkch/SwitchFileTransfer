package com.farproc.switchfiletransfer;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.content.Context.WIFI_SERVICE;

public abstract class Compat {
    public static final Impl Instance;

    static {
        final int sdk = Build.VERSION.SDK_INT;
        if (sdk >= Build.VERSION_CODES.Q) {
            Instance = new CompatImpl_Q();
        } else {
            Instance = new CompatImpl();
        }
    }

    public interface Listener {
        void onRemoveWifiNetworkError(String ssid);

        void onAddWifiNetworkError();

        void onDisconnectWifiError();

        void onEnableWifiNetworkError();

        void onNetworkAvailable(Network network);

        void onNetworkUnavailable();

        void onNetworkLost();
    }

    public interface Impl {
        void connect(final Context context, final String ssid, final String password, final Listener listener);

        void disconnect(final Context context, final Listener listener);

        Uri createDownloadFile(final Context context, final String fileName, final boolean isVideo);

        Bitmap createThumbnail(final Context context, final Uri file) throws IOException;
    }


    static class CompatImpl implements Impl {

        @SuppressLint("MissingPermission")
        @Override
        public void connect(final Context context, final String ssid, final String password, final Listener listener) {
            if (ssidConnected != null) { // already connected.
                return;
            }

            final WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);

            final String quotedSSID = String.format("\"%s\"", ssid);

            final List<WifiConfiguration> wifiConfigurations = wifiManager.getConfiguredNetworks();
            for (final WifiConfiguration c : wifiConfigurations) {
                if (quotedSSID.equals(c.SSID)) {
                    if (!wifiManager.removeNetwork(c.networkId)) {
                        Log.e("Connect", "Remove network failed.");
                        listener.onRemoveWifiNetworkError(ssid);
                        return;
                    }
                    break;
                }
            }

            final WifiConfiguration wifiConfig = new WifiConfiguration();
            wifiConfig.SSID = quotedSSID;
            wifiConfig.preSharedKey = String.format("\"%s\"", password);
            final int networkId = wifiManager.addNetwork(wifiConfig);
            if (networkId == -1) {
                Log.e("Connect", "Add network failed.");
                listener.onAddWifiNetworkError();
                return;
            }
            if (!wifiManager.disconnect()) {
                listener.onDisconnectWifiError();
                return;
            }

            if (networkStateChangedReceiver == null) {
                networkStateChangedReceiver = new NetworkStateChangedReceiver(ssid, listener);
                context.registerReceiver(networkStateChangedReceiver, new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION));
            }

            if (!wifiManager.enableNetwork(networkId, true)) {
                listener.onEnableWifiNetworkError();
            }
        }

        private void connectionCleanup(final Context context) {
            if (networkCallback != null) {
                ((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback);
                networkCallback = null;
            }
            if (networkStateChangedReceiver != null) {
                context.unregisterReceiver(networkStateChangedReceiver);
                networkStateChangedReceiver = null;
            }
            ssidConnected = null;

        }

        private String ssidConnected;
        private NetworkCallback networkCallback;
        private NetworkStateChangedReceiver networkStateChangedReceiver;

        private class NetworkStateChangedReceiver extends BroadcastReceiver {
            private final Listener listener;
            private final String ssid;

            public NetworkStateChangedReceiver(final String ssid, final Listener listener) {
                this.ssid = ssid;
                this.listener = listener;
            }

            @Override
            public void onReceive(Context context, Intent intent) {
                final WifiManager wifiManager = (WifiManager) context.getSystemService(WIFI_SERVICE);
                final ConnectivityManager connManager = (ConnectivityManager) context.getSystemService(CONNECTIVITY_SERVICE);

                final NetworkInfo networkInfo = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (networkInfo == null) {
                    return;
                }
                final NetworkInfo.State state = networkInfo.getState();
                switch (state) {
                    case DISCONNECTED:
                    case SUSPENDED:
                        if (ssidConnected != null) {
                            listener.onNetworkLost();
                            connectionCleanup(context);
                        }
                        break;
                    case CONNECTED:
                        final WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                        if (wifiInfo == null || !String.format("\"%s\"", ssid).equals(wifiInfo.getSSID())) {
                            break;
                        }
                        if (networkCallback == null) {
                            networkCallback = new NetworkCallback(context, ssid, listener);
                            connManager.requestNetwork(
                                    new NetworkRequest.Builder()
                                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                                            .build(),
                                    networkCallback
                            );
                        }
                        break;
                    default:
                }
            }
        }

        private class NetworkCallback extends ConnectivityManager.NetworkCallback {
            private final Context context;
            private final WifiManager wifiManager;
            private final String ssid;
            private final Listener listener;

            public NetworkCallback(final Context context, final String ssid, final Listener listener) {
                this.context = context;
                this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                this.ssid = ssid;
                this.listener = listener;
            }

            @Override
            public void onAvailable(@NonNull Network network) {
                super.onAvailable(network);
                Application.handler.post(() -> {
                    if (!((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE)).bindProcessToNetwork(network)) {
                        // network is for the previous connected Wi-Fi hotspot, which is unavailable now.
                        // Skip this callback and wait for the next one.
                        return;
                    }
                    final WifiInfo wifiInfo = wifiManager.getConnectionInfo();

                    if (wifiInfo != null && ("\"" + ssid + "\"").equals(wifiInfo.getSSID())) {
                        ssidConnected = ssid;
                        listener.onNetworkAvailable(network);
                    }
                });
            }

            @Override
            public void onUnavailable() {
                super.onUnavailable();
                Application.handler.post(() -> {
                    listener.onNetworkUnavailable();
                    connectionCleanup(context);
                });
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void disconnect(final Context context, final Listener listener) {
            if (ssidConnected != null) {
                final WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                final List<WifiConfiguration> wifiConfigurations = wifiManager.getConfiguredNetworks();
                final String quotedSSID = "\"" + ssidConnected + "\"";
                for (final WifiConfiguration c : wifiConfigurations) {
                    if (quotedSSID.equals(c.SSID)) {
                        if (!wifiManager.removeNetwork(c.networkId)) {
                            listener.onRemoveWifiNetworkError(ssidConnected);
                            return;
                        } else {
                            wifiManager.reconnect();
                        }
                        break;
                    }
                }
            }
            connectionCleanup(context);
        }

        @Override
        public Uri createDownloadFile(final Context context, final String fileName, final boolean isVideo) {
            final File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File f = new File(dir, fileName);
            try {
                if (!f.createNewFile()) {
                    String ext = "";
                    String base = fileName;
                    final int pos = fileName.lastIndexOf('.');
                    if (pos != -1) {
                        ext = fileName.substring(pos);
                        base = fileName.substring(0, pos);
                    }

                    int i = 0;
                    do {
                        f = new File(dir, String.format(Locale.US, "%s(%d)%s", base, i, ext));
                        if (f.createNewFile()) {
                            break;
                        }
                        i++;
                    } while (i < 99999);
                }
            } catch (IOException e) {
                Log.e("download", "create file failed", e);
                return null;
            }


            return FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", f);
        }

        @Override
        public Bitmap createThumbnail(final Context context, final Uri file) throws IOException {
            final MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
            mediaMetadataRetriever.setDataSource(context, file);
            return mediaMetadataRetriever.getFrameAtTime();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    static class CompatImpl_Q extends CompatImpl {

        @Override
        public void connect(final Context context, final String ssid, final String password, final Listener listener) {
            if (networkCallback != null) {
                return; // Already connected.
            }
            final NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(
                            new WifiNetworkSpecifier.Builder()
                                    .setSsid(ssid)
                                    .setWpa2Passphrase(password)
                                    .build()
                    )
                    .build();
            final ConnectivityManager cm = (ConnectivityManager) context.getSystemService(CONNECTIVITY_SERVICE);
            networkCallback = new NetworkCallback(context, new Listener() {
                @Override
                public void onRemoveWifiNetworkError(String ssid) {
                    listener.onRemoveWifiNetworkError(ssid);
                }

                @Override
                public void onAddWifiNetworkError() {
                    listener.onAddWifiNetworkError();
                }

                @Override
                public void onDisconnectWifiError() {
                    listener.onDisconnectWifiError();
                }

                @Override
                public void onEnableWifiNetworkError() {
                    listener.onEnableWifiNetworkError();
                }

                @Override
                public void onNetworkAvailable(Network network) {
                    listener.onNetworkAvailable(network);
                }

                @Override
                public void onNetworkUnavailable() {
                    networkCallback = null;
                    listener.onNetworkUnavailable();
                }

                @Override
                public void onNetworkLost() {
                    networkCallback = null;
                    listener.onNetworkLost();
                }
            });
            cm.requestNetwork(request, networkCallback);
        }

        @Override
        public void disconnect(Context context, Listener listener) {
            if (networkCallback != null) {
                ((ConnectivityManager) context.getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback);
                if (networkCallback.isNetworkAvailable()) {
                    listener.onNetworkLost();
                } else {
                    listener.onNetworkUnavailable();
                }
                networkCallback = null;
            }
        }

        private NetworkCallback networkCallback;

        private static class NetworkCallback extends ConnectivityManager.NetworkCallback {
            private final Context context;
            private final Listener listener;
            private boolean networkAvailable;

            public NetworkCallback(final Context context, final Listener listener) {
                this.context = context;
                this.listener = listener;
            }

            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
                Application.handler.post(() -> {
                    if (!((ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE)).bindProcessToNetwork(network)) {
                        // Just in case.
                        return;
                    }
                    networkAvailable = true;
                    listener.onNetworkAvailable(network);
                });
            }

            @Override
            public void onUnavailable() {
                super.onUnavailable();
                networkAvailable = false;
                Application.handler.post(listener::onNetworkUnavailable);
            }

            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                networkAvailable = false;
                Application.handler.post(listener::onNetworkLost);
            }

            public boolean isNetworkAvailable() {
                return networkAvailable;
            }
        }

        @Override
        public Uri createDownloadFile(final Context context, final String fileName, final boolean isVideo) {
            final ContentResolver resolver = context.getContentResolver();
            final ContentValues values = new ContentValues();
            values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.Files.FileColumns.MEDIA_TYPE,
                    isVideo ? MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO : MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE);
            return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        }

        @Override
        public Bitmap createThumbnail(final Context context, final Uri file) throws IOException {
            return context.getContentResolver().loadThumbnail(file, new Size(330, 300), null);
        }
    }
}
