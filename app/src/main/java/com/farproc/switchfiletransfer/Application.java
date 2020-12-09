package com.farproc.switchfiletransfer;

import android.os.Handler;

import java.util.Objects;

public class Application extends android.app.Application {
    // The global UI thread handler in this application.
    // Application instance is created by main UI thread,
    // so this handler is guaranteed to be in UI thread.
    public final static Handler handler = new Handler();
}
