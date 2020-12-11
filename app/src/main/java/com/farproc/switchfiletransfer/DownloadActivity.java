package com.farproc.switchfiletransfer;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.io.IOException;
import java.util.Objects;

public class DownloadActivity extends AppCompatActivity {

    private RecyclerView list;
    private TransferService.DownloadState downloadState;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);

        setContentView(R.layout.activity_download);
        list = findViewById(R.id.list);
        list.setHasFixedSize(true);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new ListAdapter());
        final DividerItemDecoration itemDecoration = new DividerItemDecoration(this, DividerItemDecoration.VERTICAL);
        list.addItemDecoration(itemDecoration);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        setContentView(list);
        registerReceiver(cancelReceiver, new IntentFilter(TransferService.CANCEL_ACTION));
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(cancelReceiver);
        super.onDestroy();
    }

    private TransferService.Binder serviceBinder;

    private class ServiceConnection implements android.content.ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.i("DownloadActivity", "Service onServiceConnected");
            serviceBinder = (TransferService.Binder) service;
            if (runAfterServiceBound != null) {
                runAfterServiceBound.run();
                runAfterServiceBound = null;
            }
            downloadState = serviceBinder.getDownloadState();
            list.getAdapter().notifyDataSetChanged();
            setTitle(downloadState.consoleName);
            if (serviceBinder.getState() != TransferService.State.Downloading) {
                dismissExitPrompt();
            }

            if (serviceListener != null) {
                throw new IllegalStateException("service listener was not removed properly.");
            }
            serviceListener = new ServiceListener();
            serviceBinder.addListener(serviceListener);

            if (serviceBinder.getState() == TransferService.State.Idle && serviceBinder.getDownloadState() == null) {
                finish();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i("DownloadActivity", "disconnected");
            runAfterServiceBound = null;
            if (serviceListener != null) {
                serviceBinder.removeListener(serviceListener);
                serviceListener = null;
            }
            serviceBinder = null;
            serviceConnection = null;
        }
    }

    private void dismissExitPrompt() {
        final Fragment f = getSupportFragmentManager().findFragmentByTag(ExitPrompt.TAG);
        if (f != null) {
            ((ExitPrompt) f).dismiss();
        }
    }

    private ServiceConnection serviceConnection;

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

    private ServiceListener serviceListener;

    private class ServiceListener implements TransferService.Listener {
        @Override
        public void onRemoveWifiNetworkError(String ssid) {
            Toast.makeText(getApplicationContext(), getString(R.string.fmt_can_not_modify_wifi, ssid), Toast.LENGTH_LONG).show();
            finish();
        }

        @Override
        public void onAddWifiNetworkError() {
            Toast.makeText(getApplicationContext(), R.string.can_not_operate_on_wifi, Toast.LENGTH_LONG).show();
            finish();
        }

        @Override
        public void onDisconnectWifiError() {
            Toast.makeText(getApplicationContext(), R.string.can_not_operate_on_wifi, Toast.LENGTH_LONG).show();
            finish();
        }

        @Override
        public void onEnableWifiNetworkError() {
            Toast.makeText(getApplicationContext(), R.string.can_not_operate_on_wifi, Toast.LENGTH_LONG).show();
            finish();
        }

        @Override
        public void onParseTasksError() {
            Toast.makeText(getApplicationContext(), R.string.can_not_parse_data, Toast.LENGTH_LONG).show();
            finish();
        }

        @Override
        public void onCreateFileError() {
            Toast.makeText(getApplicationContext(), R.string.can_not_create_file, Toast.LENGTH_LONG).show();
            finish();
        }

        @Override
        public void onDownloadItemStateChanged(int pos) {
            list.getAdapter().notifyItemChanged(pos);
        }

        @Override
        public void onDownloadCompleted() {
            removeDownloadCompletedNotification();
        }

        @Override
        public void onStateChanged(TransferService.State state) {
            if (state == TransferService.State.Downloading) {
                if (serviceBinder != null) {
                    downloadState = serviceBinder.getDownloadState();
                    list.getAdapter().notifyDataSetChanged();
                }
                if (downloadState != null) {
                    setTitle(downloadState.consoleName);
                }
            } else {
                dismissExitPrompt();
            }
        }
    }

    private void removeDownloadCompletedNotification() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(TransferService.DOWNLOAD_COMPLETED_NOTIFICATION_ID);
    }

    private BroadcastReceiver cancelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        removeDownloadCompletedNotification();
        bindTransferService();
    }

    @Override
    protected void onPause() {
        if (serviceBinder != null && serviceListener != null) {
            serviceBinder.removeListener(serviceListener);
            serviceListener = null;
        }
        unbindTransferService();
        super.onPause();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    public static class ExitPrompt extends DialogFragment {
        static final String TAG = "exit_prompt";

        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            return new AlertDialog.Builder(requireContext())
                    .setMessage(R.string.cancel_download_prompt)
                    .setPositiveButton(android.R.string.yes, (d, btn) -> {
                        final DownloadActivity activity = (DownloadActivity) requireActivity();
                        activity.cancelDownload();
                        activity.finish();
                    })
                    .create();
        }
    }

    private Runnable runAfterServiceBound;

    void cancelDownload() {
        if (serviceBinder != null) {
            serviceBinder.disconnect();
        } else {
            runAfterServiceBound = () -> {
                serviceBinder.disconnect();
            };
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (serviceBinder != null && serviceBinder.getState() == TransferService.State.Downloading) {
                final FragmentManager fm = getSupportFragmentManager();
                new ExitPrompt().show(fm, ExitPrompt.TAG);
                fm.executePendingTransactions();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private static class ListViewHolder extends RecyclerView.ViewHolder {

        final View view;

        public ListViewHolder(@NonNull View itemView) {
            super(itemView);
            view = itemView;
        }
    }

    private class ListAdapter extends RecyclerView.Adapter<ListViewHolder> {

        @NonNull
        @Override
        public ListViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ListViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.download_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ListViewHolder holder, int position) {
            final TransferService.DownloadItem item = downloadState.items[position];
            final Uri uri = Uri.parse(item.fileUri);

            final CircularProgressIndicator progressBar = holder.view.findViewById(R.id.progressBar);
            final ImageView imageView = holder.view.findViewById(R.id.imageView);
            final View videoPlay = holder.view.findViewById(R.id.video_play);
            final View errorView = holder.view.findViewById(R.id.error_view);

            switch (item.state) {
                case TransferService.DownloadItem.STATE_COMPLETED:
                    progressBar.setVisibility(View.GONE);
                    errorView.setVisibility(View.GONE);
                    if (item.isVideo) {
                        try {
                            imageView.setImageBitmap(Compat.Instance.createThumbnail(DownloadActivity.this, uri));
                        } catch (IOException e) {
                            Log.e("thumbnail", "", e);
                        }
                        imageView.setVisibility(View.VISIBLE);
                        videoPlay.setVisibility(View.VISIBLE);
                    } else {
                        imageView.setImageURI(uri);
                        imageView.setVisibility(View.VISIBLE);
                        videoPlay.setVisibility(View.GONE);
                    }
                    holder.view.setOnClickListener((v) -> {
                        final Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(uri, item.isVideo ? "video/mp4" : "image/jpeg");
                        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            startActivity(intent);
                        } catch (ActivityNotFoundException e) {
                            Log.e("View", "", e);
                        }
                    });
                    break;
                case TransferService.DownloadItem.STATE_ERROR:
                    progressBar.setVisibility(View.GONE);
                    errorView.setVisibility(View.VISIBLE);
                    imageView.setVisibility(View.GONE);
                    videoPlay.setVisibility(View.GONE);
                    holder.view.setOnClickListener(null);
                    break;
                case TransferService.DownloadItem.STATE_DOWNLOADING:
                    progressBar.setVisibility(View.VISIBLE);
                    if (item.size == -1 || item.downloaded == 0) {
                        progressBar.setIndeterminate(true);
                    } else {
                        progressBar.setIndeterminate(false);
                        progressBar.setMax(100);
                        progressBar.setProgress(
                                Math.round(((float) item.downloaded / item.size) * 100)
                        );
                    }
                    errorView.setVisibility(View.GONE);
                    imageView.setVisibility(View.GONE);
                    videoPlay.setVisibility(View.GONE);
                    holder.view.setOnClickListener(null);
            }

        }

        @Override
        public int getItemCount() {
            return downloadState == null ? 0 : downloadState.items.length;
        }
    }
}
