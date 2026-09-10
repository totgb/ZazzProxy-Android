package com.totgb.zazzproxy.security;

import javax.crypto.spec.SecretKeySpec;

public final class KeyManager {
    private final SecretKeySpec key;

    public KeyManager(String sharedSecret) throws Exception {
        key = KeyDerivation.fromPassword(sharedSecret);
    }

    public SecretKeySpec key() { return key; }
}
