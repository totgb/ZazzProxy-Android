package com.totgb.zazzproxy.security;

import java.io.InputStream;
import java.security.MessageDigest;

public final class Integrity {
    private Integrity() {}

    public static String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        for (int count; (count = input.read(buffer)) >= 0; ) digest.update(buffer, 0, count);
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value));
        return result.toString();
    }
}
