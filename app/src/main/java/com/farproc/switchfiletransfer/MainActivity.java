package com.farproc.switchfiletransfer;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public class MainActivity extends AppCompatActivity {
    private Button scanButton;
    private TextView wifiOffPrompt;

    private ActivityResultLauncher<String> requestCameraPermission;
    private ActivityResultLauncher<Void> scanQR;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        showVersion();
        scanButton = findViewById(R.id.scan);
        scanButton.setOnClickListener((v) -> {
            v.setEnabled(false);
            startScanQR();
        });
        wifiOffPrompt = findViewById(R.id.wifi_off_prompt);

        requestCameraPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (((Boolean) true).equals(isGranted)) {
                scanQR.launch(null);
            } else {
                Toast.makeText(this, R.string.lack_of_permission, Toast.LENGTH_LONG).show();
                scanButton.setEnabled(true);
            }
        });

        // Used in ActivityResultCallback lambda.
        // requestPermissions must be registered before activity is started.
        final WifiConfig[] wifiConfigToConnectAfterPermissionsGranted = new WifiConfig[1];
        final ActivityResultLauncher<String[]> requestPermissions = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), m -> {
            if (!m.containsValue(false)) {
                connect(wifiConfigToConnectAfterPermissionsGranted[0]);
            } else {
                Toast.makeText(this, R.string.lack_of_permission, Toast.LENGTH_LONG).show();
            }
        });

        scanQR = registerForActivityResult(new ScanQR(), code -> {
            if (code == null) {
                scanButton.setEnabled(true);
                return;
            }
            final WifiConfig wifiConfig = WifiConfig.parse(code);
            if (wifiConfig == null) {
                Toast.makeText(this, R.string.wrong_qr, Toast.LENGTH_LONG).show();
                scanButton.setEnabled(true);
                return;
            }

            // 1. MediaStore API instead of raw File access is used on Android Q+
            // 2. P2P Wi-Fi connectivity instead of direct Wi-Fi operation on Android Q+
            // https://developer.android.com/guide/topics/connectivity/wifi-bootstrap
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                connect(wifiConfig);
            } else if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                wifiConfigToConnectAfterPermissionsGranted[0] = wifiConfig;
                requestPermissions.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.WRITE_EXTERNAL_STORAGE});
            } else {
                connect(wifiConfig);
            }
        });
    }

    private void showVersion() {
        try {
            final PackageInfo pkg = getPackageManager().getPackageInfo(getPackageName(), 0);
            final TextView textView = findViewById(R.id.version);
            final String easterEggHost = getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("easter_egg_host", "");
            if (easterEggHost.isEmpty()) {
                textView.setText(getString(R.string.fmt_version, pkg.versionName));
            } else {
                textView.setText(getString(R.string.fmt_version_easter_egg, pkg.versionName, "host:" + easterEggHost));
            }

            final long[] lastClicked = new long[1];
            final int[] clickedCount = new int[1];
            textView.setOnClickListener((v) -> {
                Log.i("EasterEgg", String.format("%d %d", clickedCount[0], System.currentTimeMillis() - lastClicked[0]));
                if (lastClicked[0] == 0 || System.currentTimeMillis() - lastClicked[0] > 1000) {
                    lastClicked[0] = System.currentTimeMillis();
                    clickedCount[0] = 1;
                } else if (++clickedCount[0] == 5) {
                    if (getSupportFragmentManager().findFragmentByTag("easter_egg") == null) {
                        new EasterEgg.Entrance().show(getSupportFragmentManager(), "easter_egg");
                    }
                    lastClicked[0] = System.currentTimeMillis();
                    clickedCount[0] = 0;
                }
            });
        } catch (PackageManager.NameNotFoundException e) {
            Log.e("getPackageInfo", "", e);
        }
    }

    private final BroadcastReceiver cancelConnectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (serviceBinder != null) {
                serviceBinder.disconnect();
            }
        }
    };

    private boolean waitingForConnect;

    private void connect(final WifiConfig wifiConfig) {
        waitingForConnect = true;
        if (serviceBinder == null) {
            Log.i("MainActivity", "Service not bound, delay connect");
            runAfterServiceBound = () -> serviceBinder.connect(wifiConfig.SSID, wifiConfig.Password);
            return;
        }
        serviceBinder.connect(wifiConfig.SSID, wifiConfig.Password);
    }

    private Runnable runAfterServiceBound;

    private void bindTransferService() {
        if (serviceConnection == null) {
            serviceConnection = new ServiceConnection();
            TransferService.bind(this, serviceConnection);
        }
    }

    private void unbindTransferService() {
        if (serviceConnection != null) {
            unbindService(serviceConnection);
            serviceConnection = null;
            serviceBinder = null;
        }
    }

    private ServiceConnection serviceConnection;

    private class ServiceConnection implements android.content.ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i("MainActivity", "Service onServiceConnected");
            serviceBinder = (TransferService.Binder) service;
            if (runAfterServiceBound != null) {
                runAfterServiceBound.run();
                runAfterServiceBound = null;
            }
            if (serviceListener != null) {
                throw new IllegalStateException("service listener was not removed properly.");
            }
            serviceListener = new ServiceListener();
            serviceBinder.addListener(serviceListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i("MainActivity", "Service onServiceDisconnected");
            if (serviceListener != null) {
                serviceBinder.removeListener(serviceListener);
                serviceListener = null;
            }
            serviceBinder = null;
            serviceConnection = null;
        }
    }

    private TransferService.Binder serviceBinder;
    private ServiceListener serviceListener;

    private class ServiceListener implements TransferService.Listener {

        @Override
        public void onRemoveWifiNetworkError(String ssid) {
            Toast.makeText(getApplicationContext(), getString(R.string.fmt_can_not_modify_wifi, ssid), Toast.LENGTH_LONG).show();
        }

        @Override
        public void onAddWifiNetworkError() {
            Toast.makeText(getApplicationContext(), R.string.can_not_operate_on_wifi, Toast.LENGTH_LONG).show();
        }

        @Override
        public void onDisconnectWifiError() {
            Toast.makeText(getApplicationContext(), R.string.can_not_operate_on_wifi, Toast.LENGTH_LONG).show();
        }

        @Override
        public void onEnableWifiNetworkError() {
            Toast.makeText(getApplicationContext(), R.string.can_not_operate_on_wifi, Toast.LENGTH_LONG).show();
        }

        @Override
        public void onParseTasksError() {
            Toast.makeText(getApplicationContext(), R.string.can_not_parse_data, Toast.LENGTH_LONG).show();
        }

        @Override
        public void onCreateFileError() {
            Toast.makeText(getApplicationContext(), R.string.can_not_parse_data, Toast.LENGTH_LONG).show();
        }

        @Override
        public void onDownloadItemStateChanged(int pos) {
            Log.i("MainActivity", String.format("onDownloadItemStateChanged %d", pos));
        }

        @Override
        public void onDownloadCompleted() {
            Log.i("MainActivity", "onDownloadCompleted");
        }

        private static final String CONNECTING_PROMPT_FRAGMENT_TAG = "connecting";

        @Override
        public void onStateChanged(TransferService.State state) {
            Log.i("MainActivity", "onStateChanged " + state);
            final FragmentManager fm = getSupportFragmentManager();
            scanButton.setEnabled(state == TransferService.State.Idle);
            if (state != TransferService.State.Connecting) {
                try {
                    //connectionPrompt.dismiss();
                    final Fragment f = fm.findFragmentByTag(CONNECTING_PROMPT_FRAGMENT_TAG);
                    if (f != null) {
                        ((ConnectingPrompt) f).dismiss();
                    }
                } catch (RuntimeException e) {
                    // Do nothing.
                    Log.e("MainActivity", "ConnectionPrompt", e);
                }
            }
            switch (state) {
                case Idle:
                    if (waitingForConnect) {
                        scanButton.setEnabled(false);
                        waitingForConnect = false;
                    } else {
                        scanButton.setEnabled(true);
                    }
                    break;
                case Downloading:
                    scanButton.setEnabled(false);
                    startActivity(new Intent(getApplicationContext(), DownloadActivity.class));
                    break;
                case Connecting:
                    scanButton.setEnabled(false);
                    if (fm.findFragmentByTag(CONNECTING_PROMPT_FRAGMENT_TAG) == null) {
                        new ConnectingPrompt().show(getSupportFragmentManager(), CONNECTING_PROMPT_FRAGMENT_TAG);
                    }
                    break;
                default:
                    scanButton.setEnabled(false);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindTransferService();
        registerReceiver(wifiStateReceiver, new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));
        registerReceiver(cancelConnectionReceiver, new IntentFilter(ConnectingPrompt.CANCEL_ACTION));
    }


    @Override
    protected void onPause() {
        unregisterReceiver(wifiStateReceiver);
        unregisterReceiver(cancelConnectionReceiver);

        if (serviceBinder != null && serviceListener != null) {
            serviceBinder.removeListener(serviceListener);
            serviceListener = null;
        }
        unbindTransferService();
        super.onPause();
    }

    private final BroadcastReceiver wifiStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED) {
                scanButton.setVisibility(View.VISIBLE);
                wifiOffPrompt.setVisibility(View.GONE);
            } else {
                scanButton.setVisibility(View.GONE);
                wifiOffPrompt.setVisibility(View.VISIBLE);
            }
        }
    };

    private void startScanQR() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanQR.launch(null);
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA);
        }
    }


    // ScanQR is a ActivityResultContract to scan QR code with device camera.
    private static class ScanQR extends ActivityResultContract<Void, String> {

        @NonNull
        @Override
        public Intent createIntent(@NonNull Context context, Void input) {
            final IntentIntegrator ii = new IntentIntegrator((Activity) context);
            ii.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
            ii.setOrientationLocked(false);
            ii.setBarcodeImageEnabled(false);
            return ii.createScanIntent();
        }

        @Override
        public String parseResult(int resultCode, @Nullable Intent intent) {
            IntentResult result = IntentIntegrator.parseActivityResult(IntentIntegrator.REQUEST_CODE, resultCode, intent);
            if (result == null) {
                return null;
            }
            return result.getContents();
        }
    }
}
