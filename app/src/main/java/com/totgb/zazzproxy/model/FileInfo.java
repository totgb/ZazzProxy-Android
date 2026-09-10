package com.totgb.zazzproxy.model;

import java.io.DataInput;
import java.io.DataOutput;

/** Metadata for one transferable file. */
public final class FileInfo {
    public final String id;
    public final String name;
    public final long bytes;
    public final String sha256;

    public FileInfo(String id, String name, long bytes, String sha256) {
        this.id = id;
        this.name = name;
        this.bytes = bytes;
        this.sha256 = sha256;
    }

    public void writeTo(DataOutput output) throws Exception {
        BinaryFields.writeString(output, id);
        BinaryFields.writeString(output, name);
        output.writeLong(bytes);
        BinaryFields.writeString(output, sha256);
    }

    public static FileInfo readFrom(DataInput input) throws Exception {
        return new FileInfo(BinaryFields.readString(input), BinaryFields.readString(input),
                input.readLong(), BinaryFields.readString(input));
    }
}
