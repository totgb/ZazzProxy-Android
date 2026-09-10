package com.totgb.zazzproxy.network;

import android.content.Context;
import android.os.Environment;

import com.totgb.zazzproxy.model.FileInfo;
import com.totgb.zazzproxy.network.protocol.BinaryProtocol;
import com.totgb.zazzproxy.security.Integrity;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Owns the app-controlled hosted catalog and transfer directories. */
final class HostedFileService {
    private final String nodeName;
    private final File hostedDir;
    private final File downloadDir;

    HostedFileService(Context context, String nodeName) {
        this.nodeName = nodeName;
        hostedDir = new File(context.getFilesDir(), "zazzproxy/shared");
        downloadDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "zaZzProxy");
        hostedDir.mkdirs();
        downloadDir.mkdirs();
    }

    File hostedDir() { return hostedDir; }
    File downloadDirectory(String name) {
        File directory = new File(ensureDownloadDirectory(), safeName(name));
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create server download folder");
        }
        return directory;
    }
    File uploadDirectory(String name) { return ensureDownloadDirectory(); }

    List<FileInfo> hostedFiles() {
        File[] files = hostedDir.listFiles();
        if (files == null) return Collections.emptyList();
        List<FileInfo> result = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile() || file.getName().startsWith(".")
                    || file.getName().equals("catalog.zaZzProxy")) continue;
            try {
                result.add(new FileInfo(file.getName(), file.getName(), file.length(), sha256(file)));
            } catch (Exception ignored) {
                // A file disappearing while the catalog is read is harmless.
            }
        }
        return result;
    }

    File copyToHosted(InputStream source, String originalName) throws Exception {
        File target = uniqueFile(hostedDir, safeName(originalName));
        copy(source, target);
        writeManifest();
        return target;
    }

    void removeHosted(String name) throws Exception {
        File file = new File(hostedDir, safeName(name));
        if (!file.isFile() || !file.delete()) throw new IOException("Could not remove " + name);
        writeManifest();
    }

    File writeManifest() throws Exception {
        File catalog = new File(hostedDir, "catalog.zaZzProxy");
        try (FileOutputStream output = new FileOutputStream(catalog, false)) {
            output.write(BinaryProtocol.manifest(nodeName, hostedFiles()));
        }
        return catalog;
    }

    static String safeName(String name) {
        String clean = new File(name == null ? "file" : name).getName().replaceAll("[\\r\\n]", "_");
        return clean.length() == 0 ? "file" : clean;
    }

    static File uniqueFile(File directory, String name) {
        File result = new File(directory, name);
        int i = 1;
        int dot = name.lastIndexOf('.');
        while (result.exists()) {
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            result = new File(directory, stem + " (" + (i++) + ")" + ext);
        }
        return result;
    }

    private File ensureDownloadDirectory() {
        if (!downloadDir.exists() && !downloadDir.mkdirs()) {
            throw new IllegalStateException("Could not create transfer folder");
        }
        return downloadDir;
    }

    private static String sha256(File file) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return Integrity.sha256(in);
        }
    }

    private static void copy(InputStream input, File target) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(input);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[8192];
            for (int count; (count = in.read(buffer)) >= 0; ) out.write(buffer, 0, count);
        }
    }
}
