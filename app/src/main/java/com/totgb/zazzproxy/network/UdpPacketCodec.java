package com.totgb.zazzproxy.network;

import com.totgb.zazzproxy.security.Cipher;

import java.nio.ByteBuffer;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

/** Encodes and authenticates the wire envelope used by the UDP transport. */
final class UdpPacketCodec {
    private static final int MAGIC = 0x5A415A5A;
    private static final byte VERSION = 1;
    private final SecretKeySpec key;

    UdpPacketCodec(SecretKeySpec key) {
        this.key = key;
    }

    byte[] encode(byte type, UUID id, int sequence, byte[] payload) throws Exception {
        byte[] encrypted = Cipher.encrypt(key, type, id, sequence, payload);
        return ByteBuffer.allocate(4 + 1 + 1 + 16 + 4 + 2 + encrypted.length)
                .putInt(MAGIC).put(VERSION).put(type)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .putInt(sequence).putShort((short) encrypted.length).put(encrypted).array();
    }

    UdpTransport.Packet decode(byte[] data, int length) throws Exception {
        ByteBuffer input = ByteBuffer.wrap(data, 0, length);
        if (input.remaining() < 40 || input.getInt() != MAGIC || input.get() != VERSION) return null;
        byte type = input.get();
        UUID id = new UUID(input.getLong(), input.getLong());
        int sequence = input.getInt();
        int encryptedLength = input.getShort() & 0xffff;
        if (encryptedLength > input.remaining() || encryptedLength < 28) return null;
        byte[] encrypted = new byte[encryptedLength];
        input.get(encrypted);
        return new UdpTransport.Packet(type, id, sequence,
                Cipher.decrypt(key, type, id, sequence, encrypted));
    }
}
