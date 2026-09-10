package com.totgb.zazzproxy.security;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-GCM encryption/decryption with authenticated packet metadata. */
public final class Cipher {
    private Cipher() {}

    public static byte[] encrypt(SecretKeySpec key, byte type, UUID transfer, int sequence, byte[] plain) throws Exception {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad(type, transfer, sequence));
        byte[] payload = cipher.doFinal(plain);
        return ByteBuffer.allocate(nonce.length + payload.length).put(nonce).put(payload).array();
    }

    public static byte[] decrypt(SecretKeySpec key, byte type, UUID transfer, int sequence, byte[] encrypted) throws Exception {
        if (encrypted.length < 12 + 16) throw new IllegalArgumentException("Encrypted packet is too short");
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(128, Arrays.copyOf(encrypted, 12)));
        cipher.updateAAD(aad(type, transfer, sequence));
        return cipher.doFinal(encrypted, 12, encrypted.length - 12);
    }

    private static byte[] aad(byte type, UUID transfer, int sequence) {
        return ByteBuffer.allocate(21).put(type).putLong(transfer.getMostSignificantBits())
                .putLong(transfer.getLeastSignificantBits()).putInt(sequence).array();
    }
}
