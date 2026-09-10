package com.totgb.zazzproxy.archive;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.totgb.zazzproxy.network.ZazzUdpNode;
import com.totgb.zazzproxy.model.FileInfo;
import com.totgb.zazzproxy.model.Peer;
import com.totgb.zazzproxy.settings.ProfileAvatarStore;
import org.apache.commons.codec.digest.DigestUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.net.InetSocketAddress;

/** Versioned binary import/export boundary for the two user-facing Zazz formats. */
public final class ZazzArchive {
    public static final String MANIFEST_EXTENSION = ".zaZzProxy";
    public static final String SETTINGS_EXTENSION = ".zaZzSettings";
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_ENTRIES = 10_000;
    private static final byte[] MANIFEST_MAGIC = "zaZzP".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SETTINGS_MAGIC = "zaZzS".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONNECTIONS_MAGIC = "zaZzC".getBytes(StandardCharsets.US_ASCII);

    private ZazzArchive() { }

    public static void exportManifest(List<FileInfo> files, String nodeName, OutputStream output) throws Exception {
        exportManifest(files, nodeName, new byte[0], output);
    }

    public static void exportManifest(List<FileInfo> files, String nodeName, byte[] avatar, OutputStream output) throws Exception {
        if (files.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many manifest entries");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.write(MANIFEST_MAGIC); out.writeInt(FORMAT_VERSION); writeText(out, nodeName); writeBytes(out, avatar);
        out.writeInt(files.size());
        for (FileInfo file : files) {
            writeText(out, file.id); writeText(out, file.name); out.writeLong(file.bytes); writeText(out, file.sha256);
        }
        out.flush(); byte[] encoded = buffer.toByteArray(); output.write(encoded, 0, encoded.length);
    }

    public static List<FileInfo> importManifest(InputStream input) throws Exception {
        DataInputStream in = new DataInputStream(input);
        requireMagic(in, MANIFEST_MAGIC); int version = readVersion(in);
        readText(in);
        if (version >= 2) {
            // Discovery metadata is available to callers that need it; catalog imports retain file compatibility.
            readBytes(in);
        }
        int count = readCount(in); List<FileInfo> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(new FileInfo(readText(in), readText(in), in.readLong(), readText(in)));
        return result;
    }

    public static void exportSettings(Context context, OutputStream output) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("ZazzPrefs", Context.MODE_PRIVATE);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.write(SETTINGS_MAGIC); out.writeInt(FORMAT_VERSION);
        writeText(out, prefs.getString("client_name", "")); writeText(out, prefs.getString("server_name", ""));
        out.writeBoolean(prefs.getBoolean("dark_mode", false));
        writeBytes(out, profileAvatar(context, false)); writeBytes(out, profileAvatar(context, true)); out.flush();
        byte[] encoded = buffer.toByteArray(); output.write(encoded, 0, encoded.length);
    }

    public static void importSettings(Context context, InputStream input) throws Exception {
        DataInputStream in = new DataInputStream(input);
        requireMagic(in, SETTINGS_MAGIC); int version = readVersion(in);
        context.getSharedPreferences("ZazzPrefs", Context.MODE_PRIVATE).edit()
                .putString("client_name", readText(in)).putString("server_name", readText(in))
                .putBoolean("dark_mode", in.readBoolean()).apply();
        if (version >= 2) {
            saveAvatar(context, false, readBytes(in));
            saveAvatar(context, true, readBytes(in));
        }
    }

    public static String sha256(File file) throws Exception { try (InputStream in = new FileInputStream(file)) { return DigestUtils.sha256Hex(in); } }

    public static synchronized void rememberConnection(Context context, Peer peer) throws Exception {
        File file = new File(context.getFilesDir(), "connections.zaZzSettings");
        List<Peer> peers = loadConnections(context);
        boolean replaced = false;
        for (int i = 0; i < peers.size(); i++) {
            if (peers.get(i).id.equals(peer.id)) {
                peers.set(i, peer);
                replaced = true;
                break;
            }
        }
        if (!replaced) peers.add(peer);
        try (OutputStream output = new FileOutputStream(file, false)) {
            DataOutputStream out = new DataOutputStream(output);
            out.write(CONNECTIONS_MAGIC);
            out.writeInt(1);
            out.writeInt(peers.size());
            for (Peer saved : peers) {
                writeText(out, saved.id); writeText(out, saved.name); writeText(out, saved.version);
                writeText(out, saved.host); out.writeInt(saved.address().getPort());
                writeBytes(out, saved.avatar);
            }
        }
    }

    public static synchronized List<Peer> loadConnections(Context context) throws Exception {
        File file = new File(context.getFilesDir(), "connections.zaZzSettings");
        if (!file.isFile()) return new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            requireMagic(in, CONNECTIONS_MAGIC);
            if (in.readInt() != 1) throw new IllegalArgumentException("Unsupported connection archive version");
            int count = in.readInt();
            if (count < 0 || count > 1000) throw new IllegalArgumentException("Invalid connection archive");
            List<Peer> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String id = readText(in), name = readText(in), version = readText(in), host = readText(in);
                int port = in.readInt();
                result.add(new Peer(id, name, version, true,
                        new InetSocketAddress(host, port), readBytes(in)));
            }
            return result;
        }
    }
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
    private static int readVersion(DataInputStream in) throws Exception {
        int version = in.readInt();
        if (version < 1 || version > FORMAT_VERSION) throw new IllegalArgumentException("Unsupported Zazz binary format version");
        return version;
    }
    private static void requireVersion(DataInputStream in) throws Exception {
        readVersion(in);
    }
    public static byte[] profileAvatar(Context context, boolean server) throws Exception {
        Bitmap source = BitmapFactory.decodeFile(ProfileAvatarStore.avatar(context, server).getAbsolutePath());
        if (source == null) return new byte[0];
        Bitmap scaled = Bitmap.createScaledBitmap(source, 96, 96, true);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, bytes);
        scaled.recycle();
        byte[] result = bytes.toByteArray();
        if (result.length > 65_535) throw new IllegalArgumentException("Profile picture is too large for a Zazz settings file");
        return result;
    }
    private static void saveAvatar(Context context, boolean server, byte[] bytes) throws Exception {
        if (bytes.length == 0) return;
        File target = ProfileAvatarStore.avatar(context, server);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Could not create profile storage");
        try (OutputStream output = new FileOutputStream(target, false)) {
            output.write(bytes);
        }
    }
    private static void writeBytes(DataOutputStream out, byte[] bytes) throws Exception {
        out.writeInt(bytes.length);
        out.write(bytes);
    }
    private static byte[] readBytes(DataInputStream in) throws Exception {
        int length = in.readInt();
        if (length < 0 || length > 65_535) throw new IllegalArgumentException("Invalid profile picture field");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
    private static int readCount(DataInputStream in) throws Exception {
        int count = in.readInt(); if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid manifest entry count"); return count;
    }
}
