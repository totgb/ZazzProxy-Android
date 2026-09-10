package com.totgb.zazzproxy.model;

/** State shared by transfer implementations without transport details. */
public final class Transfer {
    public final String id;
    public final FileInfo file;
    public long completedBytes;

    public Transfer(String id, FileInfo file) {
        this.id = id;
        this.file = file;
    }
}
