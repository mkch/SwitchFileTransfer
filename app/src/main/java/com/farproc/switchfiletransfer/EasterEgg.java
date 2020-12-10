package com.farproc.switchfiletransfer;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

public class EasterEgg {
    public static class Entrance extends DialogFragment {
        private static final String CMD_HOST = "host:";

        @NonNull
        @Override
        public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
            final Context context = requireContext();
            final EditText editText = new EditText(context);
            editText.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new AlertDialog.Builder(context)
                    .setView(editText)
                    .setPositiveButton(android.R.string.ok, (d, which) -> {
                        final String input = editText.getText().toString();
                        if (input.startsWith(CMD_HOST)) {
                            final String host = input.substring(CMD_HOST.length(), input.length());
                            final SharedPreferences.Editor editor = context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit();
                            if(host.equals("default")) {
                                editor.remove("easter_egg_host");
                            } else {
                                editor.putString("easter_egg_host", host);
                            }
                            editor.apply();
                        }
                    })
                    .create();
        }
    }
}
