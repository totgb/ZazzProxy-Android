package com.totgb.zazzproxy.security;

import java.security.MessageDigest;

public final class Authentication {
    private Authentication() {}

    public static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
