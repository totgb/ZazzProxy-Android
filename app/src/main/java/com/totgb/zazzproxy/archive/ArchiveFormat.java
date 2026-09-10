package com.totgb.zazzproxy.archive;

import java.nio.charset.StandardCharsets;

public final class ArchiveFormat {
    public static final int VERSION = 1;
    public static final byte[] MANIFEST_MAGIC = "zaZzP".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] SETTINGS_MAGIC = "zaZzS".getBytes(StandardCharsets.US_ASCII);

    private ArchiveFormat() {}
}
