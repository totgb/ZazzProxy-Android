package com.totgb.zazzproxy.archive;

import android.content.Context;

/** Settings-specific facade over the binary settings archive. */
public final class SettingsArchive {
    private SettingsArchive() {}
    public static void write(Context context, java.io.OutputStream output) throws Exception {
        ZazzArchive.exportSettings(context, output);
    }
    public static void read(Context context, java.io.InputStream input) throws Exception {
        ZazzArchive.importSettings(context, input);
    }
}
