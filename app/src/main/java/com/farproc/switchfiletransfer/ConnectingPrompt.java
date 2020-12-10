package com.farproc.switchfiletransfer;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

public class ConnectingPrompt extends DialogFragment {

    public static final String CANCEL_ACTION = "com.farproc.switchfiletransfer.connecting.action.CANCEL";


    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setMessage(R.string.connecting)
                .setNegativeButton(android.R.string.cancel, (d, which) -> {
                    requireContext().sendBroadcast(new Intent(CANCEL_ACTION));
                })
                .create();
        dialog.setCanceledOnTouchOutside(false);
        // Do not use setOnCancelListener of Dialog or it's builder. It does not work!!
        // Override onCancel of DialogFragment instead!!
        return dialog;
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        requireContext().sendBroadcast(new Intent(CANCEL_ACTION));
    }
}
