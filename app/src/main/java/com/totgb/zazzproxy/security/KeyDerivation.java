package com.totgb.zazzproxy.security;

import java.nio.charset.StandardCharsets;
import java.security.spec.KeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Derives a 256-bit AES key from the shared network secret. */
public final class KeyDerivation {
    private KeyDerivation() {}

    public static SecretKeySpec fromPassword(String password) throws Exception {
        if (password == null || password.trim().length() < 8) {
            throw new IllegalArgumentException("A network key of at least 8 characters is required.");
        }
        KeySpec spec = new PBEKeySpec(password.toCharArray(),
                "ZazzProxy/v1".getBytes(StandardCharsets.UTF_8), 120000, 256);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        return new SecretKeySpec(key, "AES");
    }
}
