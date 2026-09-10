package com.totgb.zazzproxy.model;

import java.io.DataInput;
import java.io.DataOutput;
import java.nio.charset.StandardCharsets;

/** Bounded primitive fields shared by binary archives and protocol records. */
public final class BinaryFields {
    private static final int MAX_STRING_BYTES = 65_535;

    private BinaryFields() {}

    public static void writeString(DataOutput output, String value) throws Exception {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IllegalArgumentException("Binary text field is too large");
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    public static String readString(DataInput input) throws Exception {
        byte[] bytes = new byte[input.readUnsignedShort()];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
