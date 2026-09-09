package com.totgb.zazzproxy;

import android.content.Context;
import android.content.SharedPreferences;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.commons.codec.digest.DigestUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Security;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Import/export boundary for the only two user-facing Zazz formats. */
public final class ZazzArchive {
    public static final String MANIFEST_EXTENSION = ".zaZzProxy";
    public static final String SETTINGS_EXTENSION = ".zaZzSettings";
    private static final int FORMAT_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Logger LOG = LoggerFactory.getLogger(ZazzArchive.class);

    static {
        // Registered once when available; Android's platform provider remains the runtime fallback.
        if (Security.getProvider("BC") == null) Security.addProvider(new BouncyCastleProvider());
    }
    private ZazzArchive() { }

    public static void exportManifest(List<ZazzUdpNode.ShareFile> files, String nodeName, OutputStream output) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "zaZzProxy"); root.put("formatVersion", FORMAT_VERSION); root.put("nodeName", nodeName);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ZazzUdpNode.ShareFile file : files) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", file.id); entry.put("name", file.name); entry.put("bytes", file.bytes); entry.put("sha256", file.sha256); entries.add(entry);
        }
        root.put("files", entries); JSON.writeValue(output, root); LOG.info("Exported {} manifest entries", entries.size());
    }

    public static List<ZazzUdpNode.ShareFile> importManifest(InputStream input) throws Exception {
        Map<String, Object> root = JSON.readValue(input, new TypeReference<Map<String, Object>>() { });
        require(root, "zaZzProxy"); List<ZazzUdpNode.ShareFile> result = new ArrayList<>();
        Object raw = root.get("files"); if (!(raw instanceof List)) throw new IllegalArgumentException("Manifest has no file list");
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) throw new IllegalArgumentException("Invalid manifest entry");
            Map<?, ?> file = (Map<?, ?>) item;
            result.add(new ZazzUdpNode.ShareFile(String.valueOf(file.get("id")), String.valueOf(file.get("name")), ((Number) file.get("bytes")).longValue(), String.valueOf(file.get("sha256"))));
        }
        return result;
    }

    public static void exportSettings(Context context, OutputStream output) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("ZazzPrefs", Context.MODE_PRIVATE);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "zaZzSettings"); root.put("formatVersion", FORMAT_VERSION);
        root.put("clientName", prefs.getString("client_name", "")); root.put("serverName", prefs.getString("server_name", ""));
        root.put("darkMode", prefs.getBoolean("dark_mode", false));
        // The shared key is intentionally excluded: exporting it would silently copy trust to an arbitrary file.
        JSON.writeValue(output, root);
    }

    public static void importSettings(Context context, InputStream input) throws Exception {
        Map<String, Object> root = JSON.readValue(input, new TypeReference<Map<String, Object>>() { });
        require(root, "zaZzSettings"); SharedPreferences.Editor edit = context.getSharedPreferences("ZazzPrefs", Context.MODE_PRIVATE).edit();
        edit.putString("client_name", value(root, "clientName")); edit.putString("server_name", value(root, "serverName"));
        Object dark = root.get("darkMode"); if (dark instanceof Boolean) edit.putBoolean("dark_mode", (Boolean) dark); edit.apply();
    }

    public static String sha256(File file) throws Exception { try (InputStream in = new FileInputStream(file)) { return DigestUtils.sha256Hex(in); } }
    private static void require(Map<String, Object> root, String format) {
        if (!format.equals(root.get("format")) || !(root.get("formatVersion") instanceof Number) || ((Number) root.get("formatVersion")).intValue() != FORMAT_VERSION) throw new IllegalArgumentException("Unsupported Zazz file format");
    }
    private static String value(Map<String, Object> values, String key) { Object value = values.get(key); return value instanceof String ? (String) value : ""; }
}
