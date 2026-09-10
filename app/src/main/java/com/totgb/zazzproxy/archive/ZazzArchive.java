package com.totgb.zazzproxy.archive;

import android.content.Context;
import android.content.SharedPreferences;

import com.totgb.zazzproxy.network.ZazzUdpNode;
import com.totgb.zazzproxy.model.FileInfo;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Versioned binary import/export boundary for the two user-facing Zazz formats. */
public final class ZazzArchive {
    public static final String MANIFEST_EXTENSION = ".zaZzProxy";
    public static final String SETTINGS_EXTENSION = ".zaZzSettings";
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ENTRIES = 10_000;
    private static final byte[] MANIFEST_MAGIC = "zaZzP".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SETTINGS_MAGIC = "zaZzS".getBytes(StandardCharsets.US_ASCII);

    private ZazzArchive() { }

    public static void exportManifest(List<FileInfo> files, String nodeName, OutputStream output) throws Exception {
        if (files.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many manifest entries");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.write(MANIFEST_MAGIC); out.writeInt(FORMAT_VERSION); writeText(out, nodeName); out.writeInt(files.size());
        for (FileInfo file : files) {
            writeText(out, file.id); writeText(out, file.name); out.writeLong(file.bytes); writeText(out, file.sha256);
        }
        out.flush(); byte[] encoded = buffer.toByteArray(); output.write(encoded, 0, encoded.length);
    }

    public static List<FileInfo> importManifest(InputStream input) throws Exception {
        DataInputStream in = new DataInputStream(input);
        requireMagic(in, MANIFEST_MAGIC); requireVersion(in);
        readText(in); int count = readCount(in); List<FileInfo> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(new FileInfo(readText(in), readText(in), in.readLong(), readText(in)));
        return result;
    }

    public static void exportSettings(Context context, OutputStream output) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("ZazzPrefs", Context.MODE_PRIVATE);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.write(SETTINGS_MAGIC); out.writeInt(FORMAT_VERSION);
        writeText(out, prefs.getString("client_name", "")); writeText(out, prefs.getString("server_name", ""));
        out.writeBoolean(prefs.getBoolean("dark_mode", false)); out.flush();
        byte[] encoded = buffer.toByteArray(); output.write(encoded, 0, encoded.length);
    }

    public static void importSettings(Context context, InputStream input) throws Exception {
        DataInputStream in = new DataInputStream(input);
        requireMagic(in, SETTINGS_MAGIC); requireVersion(in);
        context.getSharedPreferences("ZazzPrefs", Context.MODE_PRIVATE).edit()
                .putString("client_name", readText(in)).putString("server_name", readText(in))
                .putBoolean("dark_mode", in.readBoolean()).apply();
    }

    public static String sha256(File file) throws Exception { try (InputStream in = new FileInputStream(file)) { return DigestUtils.sha256Hex(in); } }
    private static void writeText(DataOutputStream out, String value) throws Exception {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65_535) throw new IllegalArgumentException("Zazz text field is too large");
        out.writeShort(bytes.length); out.write(bytes);
    }
    private static String readText(DataInputStream in) throws Exception {
        int length = in.readUnsignedShort(); byte[] bytes = new byte[length]; in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static void requireMagic(DataInputStream in, byte[] expected) throws Exception {
        byte[] actual = new byte[expected.length]; in.readFully(actual);
        if (!java.util.Arrays.equals(actual, expected)) throw new IllegalArgumentException("Unsupported Zazz binary format");
    }
    private static void requireVersion(DataInputStream in) throws Exception {
        if (in.readInt() != FORMAT_VERSION) throw new IllegalArgumentException("Unsupported Zazz binary format version");
    }
    private static int readCount(DataInputStream in) throws Exception {
        int count = in.readInt(); if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid manifest entry count"); return count;
    }
}
