package com.totgb.zazzproxy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** UDP-only LAN transport. Every payload is AES-GCM encrypted and authenticated. */
public final class ZazzUdpNode implements Closeable {
    public static final int PORT = 39841;
    private static final int MAGIC = 0x5A415A5A, VERSION = 1, MAX_PACKET = 1200, CHUNK_BYTES = 800;
    private static final byte HELLO = 1, MANIFEST_REQUEST = 2, MANIFEST = 3,
            DOWNLOAD_REQUEST = 4, FILE_CHUNK = 5, ACK = 6, UPLOAD_OFFER = 7,
            UPLOAD_CHUNK = 8, ERROR = 9;

    public interface Callback {
        void onPeer(Peer peer);
        void onManifest(Peer peer, List<ShareFile> files);
        void onTransfer(String name, long current, long total, boolean upload);
        void onComplete(File file);
        void onFailure(String message);
    }
    public static final class Peer {
        public final String id, name, version, host;
        public final boolean server;
        private final InetSocketAddress address;
        Peer(String id, String name, String version, boolean server, InetSocketAddress address) {
            this.id = id; this.name = name; this.version = version; this.address = address;
            this.server = server;
            this.host = address.getAddress().getHostAddress();
        }
    }
    public static final class ShareFile {
        public final String id, name, sha256;
        public final long bytes;
        ShareFile(String id, String name, long bytes, String sha256) {
            this.id = id; this.name = name; this.bytes = bytes; this.sha256 = sha256;
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("name", name).put("bytes", bytes).put("sha256", sha256);
        }
        static ShareFile from(JSONObject json) throws Exception {
            return new ShareFile(json.getString("id"), json.getString("name"), json.getLong("bytes"), json.getString("sha256"));
        }
    }

    private final Context context;
    private final Callback callback;
    private final String nodeId = UUID.randomUUID().toString();
    private final String nodeName, version;
    private final boolean advertisedServer;
    private final SecretKeySpec key;
    private final ExecutorService receiver = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Map<String, Outgoing> sends = new ConcurrentHashMap<>();
    private final Map<String, Incoming> receives = new ConcurrentHashMap<>();
    private final File hostedDir, downloadDir;
    private volatile boolean running;
    private DatagramSocket socket;

    public ZazzUdpNode(Context context, String name, boolean advertisedServer, String sharedKey, Callback callback) throws Exception {
        if (sharedKey == null || sharedKey.trim().length() < 8) throw new IllegalArgumentException("A network key of at least 8 characters is required.");
        this.context = context.getApplicationContext(); this.nodeName = name; this.version = "1.0"; this.advertisedServer = advertisedServer; this.callback = callback;
        hostedDir = new File(this.context.getFilesDir(), "zazzproxy/shared");
        downloadDir = new File(this.context.getExternalFilesDir(null), "ZazzProxy Downloads");
        hostedDir.mkdirs(); downloadDir.mkdirs();
        KeySpec spec = new PBEKeySpec(sharedKey.toCharArray(), "ZazzProxy/v1".getBytes(StandardCharsets.UTF_8), 120000, 256);
        key = new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
    }

    public synchronized void start() throws Exception {
        if (running) return;
        socket = new DatagramSocket(null); socket.setReuseAddress(true); socket.setBroadcast(true);
        socket.bind(new InetSocketAddress(PORT)); running = true;
        receiver.execute(this::receiveLoop);
        scheduler.scheduleAtFixedRate(() -> { try { announce(); } catch (Exception ignored) { } }, 0, 4, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::pumpOutgoing, 100, 100, TimeUnit.MILLISECONDS);
    }
    public void requestManifest(Peer peer) { sendJson(peer.address, MANIFEST_REQUEST, UUID.randomUUID().toString(), 0, new JSONObject()); }
    public void requestDownload(Peer peer, ShareFile file) {
        JSONObject p = new JSONObject(); try { p.put("fileId", file.id); } catch (Exception ignored) { }
        sendJson(peer.address, DOWNLOAD_REQUEST, UUID.randomUUID().toString(), 0, p);
    }
    public void upload(Peer peer, File file) throws Exception {
        if (!file.isFile()) throw new IllegalArgumentException("The selected upload is unavailable.");
        String transfer = UUID.randomUUID().toString();
        ShareFile metadata = new ShareFile(UUID.randomUUID().toString(), safeName(file.getName()), file.length(), sha256(file));
        JSONObject p = metadata.json(); p.put("transfer", transfer);
        sends.put(transfer, new Outgoing(transfer, peer.address, file, metadata, true));
        sendJson(peer.address, UPLOAD_OFFER, transfer, 0, p);
    }
    public List<ShareFile> hostedFiles() {
        File[] files = hostedDir.listFiles(); if (files == null) return Collections.emptyList();
        List<ShareFile> result = new ArrayList<>();
        for (File f : files) if (f.isFile() && !f.getName().startsWith(".") && !f.getName().equals("catalog.zaZzProxy")) try {
            result.add(new ShareFile(f.getName(), f.getName(), f.length(), sha256(f)));
        } catch (Exception e) { postFail("Cannot read " + f.getName()); }
        return result;
    }
    /** Copies a selected Android document into the app-controlled server folder. */
    public File hostCopy(java.io.InputStream source, String originalName) throws Exception {
        File target = uniqueFile(hostedDir, safeName(originalName));
        copy(source, target); writeManifest(); return target;
    }
    public File getHostedDir() { return hostedDir; }
    /** Removes only a direct child of the controlled server folder. */
    public void removeHostedFile(String name) throws Exception {
        File file = new File(hostedDir, safeName(name));
        if (!file.isFile() || !file.delete()) throw new IOException("Could not remove " + name);
        writeManifest();
    }

    /** Persists the catalog format that is also transferred as the MANIFEST payload. */
    public File writeManifest() throws Exception {
        File catalog = new File(hostedDir, "catalog.zaZzProxy");
        try (FileOutputStream output = new FileOutputStream(catalog, false)) {
            output.write(manifestJson().toString(2).getBytes(StandardCharsets.UTF_8));
        }
        return catalog;
    }

    private void announce() throws Exception {
        JSONObject p = new JSONObject().put("id", nodeId).put("name", nodeName).put("version", version).put("server", advertisedServer);
        sendJson(new InetSocketAddress(InetAddress.getByName("255.255.255.255"), PORT), HELLO, UUID.randomUUID().toString(), 0, p);
    }
    private void receiveLoop() {
        byte[] buffer = new byte[MAX_PACKET];
        while (running) try {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length); socket.receive(packet);
            handle(packet);
        } catch (Exception e) { if (running) postFail("Network listener stopped: " + e.getMessage()); }
    }
    private void handle(DatagramPacket packet) throws Exception {
        ByteBuffer input = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
        if (input.remaining() < 40 || input.getInt() != MAGIC || input.get() != VERSION) return;
        byte type = input.get(); UUID id = new UUID(input.getLong(), input.getLong()); int seq = input.getInt(); int length = input.getShort() & 0xffff;
        if (length > input.remaining() || length < 28) return;
        byte[] encrypted = new byte[length]; input.get(encrypted);
        byte[] payload = decrypt(type, id, seq, encrypted);
        InetSocketAddress from = new InetSocketAddress(packet.getAddress(), packet.getPort());
        String transfer = id.toString();
        if (type == FILE_CHUNK || type == UPLOAD_CHUNK) { receiveChunk(from, transfer, seq, payload); return; }
        if (type == ACK) { Outgoing send = sends.get(transfer); if (send != null) send.ack(seq); return; }
        JSONObject json = new JSONObject(new String(payload, StandardCharsets.UTF_8));
        switch (type) {
            case HELLO: handleHello(from, json); break;
            case MANIFEST_REQUEST: sendManifest(from, transfer); break;
            case MANIFEST: handleManifest(from, json); break;
            case DOWNLOAD_REQUEST: startDownload(from, transfer, json.getString("fileId")); break;
            case UPLOAD_OFFER: acceptUpload(from, transfer, json); break;
            case ERROR: postFail(json.optString("message", "Peer rejected request")); break;
            default: break;
        }
    }
    private void handleHello(InetSocketAddress from, JSONObject json) throws Exception {
        if (nodeId.equals(json.getString("id"))) return;
        Peer peer = new Peer(json.getString("id"), json.getString("name"), json.optString("version", "?"), json.optBoolean("server", false), from);
        Peer old = peers.put(peer.id, peer);
        if (old == null || !old.host.equals(peer.host)) post(() -> callback.onPeer(peer));
    }
    private void sendManifest(InetSocketAddress to, String transfer) {
        try {
            sendJson(to, MANIFEST, transfer, 0, manifestJson());
        } catch (Exception e) { sendError(to, transfer, "Could not create catalog"); }
    }
    private JSONObject manifestJson() throws Exception {
        JSONArray files = new JSONArray(); for (ShareFile file : hostedFiles()) files.put(file.json());
        return new JSONObject().put("format", "zaZzProxy").put("formatVersion", VERSION)
                .put("name", nodeName).put("version", version).put("files", files);
    }
    private void handleManifest(InetSocketAddress from, JSONObject json) {
        try {
            List<ShareFile> files = new ArrayList<>(); JSONArray a = json.getJSONArray("files");
            for (int i = 0; i < a.length(); i++) files.add(ShareFile.from(a.getJSONObject(i)));
            Peer peer = new Peer("manifest-" + from, json.optString("name", "Server"), json.optString("version", "?"), true, from);
            post(() -> callback.onManifest(peer, files));
        } catch (Exception e) { postFail("Invalid .zaZzproxy manifest received"); }
    }
    private void startDownload(InetSocketAddress to, String transfer, String fileId) {
        File file = new File(hostedDir, safeName(fileId));
        if (!file.isFile()) { sendError(to, transfer, "Requested file is unavailable"); return; }
        try { sends.put(transfer, new Outgoing(transfer, to, file, new ShareFile(file.getName(), file.getName(), file.length(), sha256(file)), false)); }
        catch (Exception e) { sendError(to, transfer, "Cannot read requested file"); }
    }
    private void acceptUpload(InetSocketAddress from, String transfer, JSONObject json) {
        try {
            ShareFile file = ShareFile.from(json); receives.put(transfer, new Incoming(transfer, from, uniqueFile(hostedDir, safeName(file.name)), file, true));
        } catch (Exception e) { sendError(from, transfer, "Cannot accept upload"); }
    }
    private void receiveChunk(InetSocketAddress from, String transfer, int seq, byte[] data) {
        try {
            Incoming in = receives.get(transfer);
            if (in == null) {
                ByteBuffer b = ByteBuffer.wrap(data); int metaLength = b.getShort() & 0xffff;
                if (metaLength > b.remaining()) throw new IllegalArgumentException("Invalid file metadata");
                byte[] meta = new byte[metaLength]; b.get(meta); ShareFile f = ShareFile.from(new JSONObject(new String(meta, StandardCharsets.UTF_8)));
                in = new Incoming(transfer, from, uniqueFile(downloadDir, safeName(f.name)), f, false); receives.put(transfer, in);
                data = new byte[b.remaining()]; b.get(data);
            }
            in.write(seq, data); sendRaw(from, ACK, transfer, seq, new byte[0]);
            if (in.complete()) {
                receives.remove(transfer); in.finish();
                final File completed = in.target;
                post(() -> callback.onComplete(completed));
            }
        } catch (Exception e) { receives.remove(transfer); sendError(from, transfer, "Transfer rejected: " + e.getMessage()); postFail("Transfer failed: " + e.getMessage()); }
    }
    private void pumpOutgoing() {
        for (Outgoing outgoing : sends.values()) try { outgoing.pump(); } catch (Exception e) { sends.remove(outgoing.transfer); postFail("Transfer failed: " + e.getMessage()); }
    }
    private final class Outgoing {
        final String transfer; final InetSocketAddress peer; final File file; final ShareFile metadata; final boolean upload;
        int acknowledged = -1, sent = -1; long lastSent;
        Outgoing(String transfer, InetSocketAddress peer, File file, ShareFile metadata, boolean upload) { this.transfer=transfer; this.peer=peer; this.file=file; this.metadata=metadata; this.upload=upload; }
        synchronized void ack(int sequence) { acknowledged = Math.max(acknowledged, sequence); }
        synchronized void pump() throws Exception {
            int chunks = Math.max(1, (int) ((metadata.bytes + CHUNK_BYTES - 1) / CHUNK_BYTES));
            if (acknowledged >= chunks - 1) { sends.remove(transfer); return; }
            if (sent > acknowledged && System.currentTimeMillis() - lastSent < 700) return;
            sent = acknowledged + 1; long offset = (long) sent * CHUNK_BYTES;
            byte[] content = readRange(file, offset, (int) Math.min(CHUNK_BYTES, metadata.bytes - offset));
            if (!upload && sent == 0) {
                byte[] info = metadata.json().toString().getBytes(StandardCharsets.UTF_8);
                ByteBuffer b = ByteBuffer.allocate(2 + info.length + content.length).putShort((short) info.length).put(info).put(content); content = b.array();
            }
            sendRaw(peer, upload ? UPLOAD_CHUNK : FILE_CHUNK, transfer, sent, content); lastSent = System.currentTimeMillis();
            final long done = Math.min(metadata.bytes, offset + Math.min(CHUNK_BYTES, metadata.bytes - offset));
            post(() -> callback.onTransfer(metadata.name, done, metadata.bytes, upload));
        }
    }
    private final class Incoming {
        final String transfer; final InetSocketAddress peer; final File target, part; final ShareFile metadata; final boolean upload;
        int expected; long received;
        Incoming(String transfer, InetSocketAddress peer, File target, ShareFile metadata, boolean upload) throws Exception {
            this.transfer=transfer;this.peer=peer;this.target=target;this.metadata=metadata;this.upload=upload;
            part = new File(target.getParentFile(), "." + target.getName() + ".part"); if (part.exists()) part.delete();
        }
        synchronized void write(int sequence, byte[] bytes) throws Exception {
            if (sequence != expected || received + bytes.length > metadata.bytes) return;
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(part, true))) { out.write(bytes); }
            received += bytes.length; expected++; final long done = received;
            post(() -> callback.onTransfer(metadata.name, done, metadata.bytes, upload));
        }
        boolean complete() { return received == metadata.bytes; }
        void finish() throws Exception { if (!sha256(part).equalsIgnoreCase(metadata.sha256)) throw new SecurityException("SHA-256 mismatch"); if (!part.renameTo(target)) throw new Exception("Could not save final file"); }
    }
    private void sendJson(InetSocketAddress to, byte type, String transfer, int seq, JSONObject json) { sendRaw(to, type, transfer, seq, json.toString().getBytes(StandardCharsets.UTF_8)); }
    private synchronized void sendRaw(InetSocketAddress to, byte type, String transfer, int seq, byte[] plain) {
        try { UUID id=UUID.fromString(transfer); byte[] cipher=encrypt(type,id,seq,plain); ByteBuffer b=ByteBuffer.allocate(4+1+1+16+4+2+cipher.length).putInt(MAGIC).put((byte)VERSION).put(type).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).putInt(seq).putShort((short)cipher.length).put(cipher); socket.send(new DatagramPacket(b.array(),b.position(),to)); }
        catch (Exception e) { postFail("Unable to send packet"); }
    }
    private void sendError(InetSocketAddress to, String transfer, String message) { try { sendJson(to, ERROR, transfer, 0, new JSONObject().put("message", message)); } catch (Exception ignored) { } }
    private byte[] encrypt(byte type, UUID id, int seq, byte[] plain) throws Exception { byte[] nonce=new byte[12];new SecureRandom().nextBytes(nonce);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce));c.updateAAD(aad(type,id,seq));byte[] encrypted=c.doFinal(plain);byte[] result=Arrays.copyOf(nonce,nonce.length+encrypted.length);System.arraycopy(encrypted,0,result,nonce.length,encrypted.length);return result; }
    private byte[] decrypt(byte type, UUID id, int seq, byte[] ciphertext) throws Exception { Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOf(ciphertext,12)));c.updateAAD(aad(type,id,seq));return c.doFinal(ciphertext,12,ciphertext.length-12); }
    private static byte[] aad(byte type, UUID id, int seq) { return ByteBuffer.allocate(21).put(type).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).putInt(seq).array(); }
    private static String sha256(File file) throws Exception { MessageDigest d=MessageDigest.getInstance("SHA-256");try(BufferedInputStream in=new BufferedInputStream(new FileInputStream(file))){byte[] b=new byte[8192];for(int n;(n=in.read(b))>=0;)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte x:d.digest())s.append(String.format("%02x",x));return s.toString(); }
    private static void copy(java.io.InputStream input, File target) throws Exception { try(BufferedInputStream in=new BufferedInputStream(input);BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(target))){byte[]b=new byte[8192];for(int n;(n=in.read(b))>=0;)out.write(b,0,n);} }
    private static byte[] readRange(File file,long offset,int length)throws Exception{byte[] result=new byte[length];try(java.io.RandomAccessFile in=new java.io.RandomAccessFile(file,"r")){in.seek(offset);in.readFully(result);}return result;}
    private static String safeName(String name) { String clean = new File(name == null ? "file" : name).getName().replaceAll("[\\r\\n]", "_"); return clean.length() == 0 ? "file" : clean; }
    private static File uniqueFile(File directory, String name) { File result=new File(directory,name);int i=1;int dot=name.lastIndexOf('.');while(result.exists()){String stem=dot>0?name.substring(0,dot):name;String ext=dot>0?name.substring(dot):"";result=new File(directory,stem+" ("+(i++)+")"+ext);}return result; }
    private void post(Runnable runnable) { main.post(runnable); } private void postFail(String message) { post(() -> callback.onFailure(message)); }
    @Override public synchronized void close() { running=false; if(socket!=null)socket.close();receiver.shutdownNow();scheduler.shutdownNow(); }
}
