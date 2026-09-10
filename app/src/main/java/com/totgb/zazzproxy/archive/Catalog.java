package com.totgb.zazzproxy.archive;

import com.totgb.zazzproxy.model.FileInfo;
import java.util.List;

/** Catalog-specific facade over the binary manifest archive. */
public final class Catalog {
    private Catalog() {}
    public static void write(List<FileInfo> files, String nodeName, java.io.OutputStream output) throws Exception {
        ZazzArchive.exportManifest(files, nodeName, output);
    }
    public static List<FileInfo> read(java.io.InputStream input) throws Exception {
        return ZazzArchive.importManifest(input);
    }
}
