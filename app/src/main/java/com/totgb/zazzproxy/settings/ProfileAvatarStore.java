package com.totgb.zazzproxy.settings;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/** Keeps profile pictures local to the app; no network or account service is involved. */
public final class ProfileAvatarStore {
    private static final String CLIENT_AVATAR_FILE = "profile/client-avatar";
    private static final String SERVER_AVATAR_FILE = "profile/server-avatar";

    private ProfileAvatarStore() {
    }

    public static File localAvatar(Context context) {
        return avatar(context, false);
    }

    public static File avatar(Context context, boolean server) {
        return new File(context.getFilesDir(), server ? SERVER_AVATAR_FILE : CLIENT_AVATAR_FILE);
    }

    public static void save(Context context, Uri source) throws Exception {
        save(context, source, false);
    }

    public static void save(Context context, Uri source, boolean server) throws Exception {
        File target = avatar(context, server);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create profile storage");
        }
        try (InputStream in = context.getContentResolver().openInputStream(source);
             OutputStream out = new java.io.FileOutputStream(target, false)) {
            if (in == null) throw new java.io.IOException("Could not open profile picture");
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) != -1; ) out.write(buffer, 0, count);
        }
    }
}
