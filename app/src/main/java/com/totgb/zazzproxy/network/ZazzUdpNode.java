package com.totgb.zazzproxy.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.totgb.zazzproxy.model.FileInfo;
import com.totgb.zazzproxy.model.Peer;
import com.totgb.zazzproxy.network.protocol.BinaryProtocol;
import com.totgb.zazzproxy.security.Cipher;
import com.totgb.zazzproxy.security.Integrity;
import com.totgb.zazzproxy.security.KeyManager;
import com.totgb.zazzproxy.settings.ProfileAvatarStore;

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
import java.net.NetworkInterface;
import android.os.Environment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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

import javax.crypto.spec.SecretKeySpec;

/** UDP-only LAN transport. Every payload is AES-GCM encrypted and authenticated. */
public final class ZazzUdpNode implements Closeable {
    public static final int PORT = 39841;
    private static final int MAGIC = 0x5A415A5A, VERSION = 1, MAX_PACKET = 1200, CHUNK_BYTES = 800;
    private static final byte HELLO = 1, MANIFEST_REQUEST = 2, MANIFEST = 3,
            DOWNLOAD_REQUEST = 4, FILE_CHUNK = 5, ACK = 6, UPLOAD_OFFER = 7,
            UPLOAD_CHUNK = 8, ERROR = 9, CONTROL = 10, CONNECTION_REQUEST = 11,
            CONNECTION_DECISION = 12;
    public static final byte KICK = 1, BAN = 2, REMOTE_KICK = 3, REMOTE_BAN = 4;

    public interface Callback {
        void onPeer(Peer peer);
        void onManifest(Peer peer, List<FileInfo> files);
        void onTransfer(String name, long current, long total, boolean upload);
        void onComplete(File file);
        void onFailure(String message);
        default void onPeerRemoved(Peer peer, String reason) { }
        default void onConnectionRequest(Peer peer) { }
        default void onConnectionDecision(Peer peer, boolean accepted) { }
        default void onUploadOffer(Peer peer, String transfer, FileInfo file) { }
    }
    private final Context context;
    private final Callback callback;
    private final String nodeId = UUID.randomUUID().toString();
    private final String nodeName, version;
    private final boolean advertisedServer;
    private final byte[] avatar;
    private final SecretKeySpec key;
    private final ExecutorService receiver = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Map<String, Long> peerLastSeen = new ConcurrentHashMap<>();
    private final java.util.Set<String> bannedNames = ConcurrentHashMap.newKeySet();
    private final Map<String, Outgoing> sends = new ConcurrentHashMap<>();
    private final Map<String, Outgoing> pendingSends = new ConcurrentHashMap<>();
    private final Map<String, Incoming> receives = new ConcurrentHashMap<>();
    private final Map<String, Peer> approvedPeers = new ConcurrentHashMap<>();
    private final Map<String, Peer> pendingConnections = new ConcurrentHashMap<>();
    private final File hostedDir, downloadDir;
    private volatile boolean running;
    private DatagramSocket socket;

    public ZazzUdpNode(Context context, String name, boolean advertisedServer, String sharedKey, Callback callback) throws Exception {
        if (sharedKey == null || sharedKey.trim().length() < 8) throw new IllegalArgumentException("A network key of at least 8 characters is required.");
        this.context = context.getApplicationContext(); this.nodeName = name; this.version = "1.0"; this.advertisedServer = advertisedServer; this.callback = callback;
        hostedDir = new File(this.context.getFilesDir(), "zazzproxy/shared");
        downloadDir = new File(Environment.getExternalStorageDirectory(), "zaZzProxy");
        hostedDir.mkdirs(); downloadDir.mkdirs();
        key = new KeyManager(sharedKey).key();
        avatar = readAvatar(this.context, advertisedServer);
    }

    public synchronized void start() throws Exception {
        if (running) return;
        socket = new DatagramSocket(null); socket.setReuseAddress(true); socket.setBroadcast(true);
        socket.bind(new InetSocketAddress(advertisedServer ? PORT : 0)); running = true;
        receiver.execute(this::receiveLoop);
        scheduler.scheduleAtFixedRate(() -> { try { announce(); } catch (Exception ignored) { } }, 0, 4, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::removeExpiredPeers, 2, 2, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::refreshApprovedManifests, 2, 2, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::pumpOutgoing, 100, 100, TimeUnit.MILLISECONDS);
    }
    public void requestConnection(Peer peer) {
        try {
            InetSocketAddress server = new InetSocketAddress(peer.address().getAddress(), PORT);
            sendRaw(server, CONNECTION_REQUEST, UUID.randomUUID().toString(), 0,
                    BinaryProtocol.connectionRequest(nodeId, nodeName));
        } catch (Exception error) {
            postFail("Could not request connection");
        }
    }
    public void requestManifest(Peer peer) {
        if (approvedPeers.containsKey(peer.id)) {
            sendRaw(peer.address(), MANIFEST_REQUEST, UUID.randomUUID().toString(), 0, new byte[0]);
        }
    }
    public void approveConnection(Peer peer, boolean accepted) {
        pendingConnections.remove(peer.id);
        if (accepted) {
            approvedPeers.put(peer.id, peer);
            post(() -> callback.onPeer(peer));
        }
        try {
            sendRaw(peer.address(), CONNECTION_DECISION, UUID.randomUUID().toString(), 0,
                    BinaryProtocol.decision(accepted, nodeId, nodeName));
            if (accepted) sendManifest(peer.address(), UUID.randomUUID().toString());
        } catch (Exception error) {
            postFail("Could not send connection decision");
        }
    }
    public void approveUpload(Peer peer, String transfer, FileInfo file, boolean accepted) {
        if (accepted) {
            try {
                receives.put(transfer, new Incoming(transfer, peer.address(),
                        uniqueFile(uploadDirectory(peer.name), safeName(file.name)), file, true));
            } catch (Exception e) {
                postFail("Could not prepare incoming file");
                accepted = false;
            }
        }
        try {
            sendRaw(peer.address(), CONNECTION_DECISION, transfer, 0,
                    BinaryProtocol.decision(accepted, peer.id, nodeName));
        } catch (Exception error) {
            postFail("Could not send upload decision");
        }
    }
    public List<Peer> connectedPeers() {
        return new ArrayList<>(advertisedServer ? approvedPeers.values() : peers.values());
    }
    public List<Peer> pendingConnections() {
        return new ArrayList<>(pendingConnections.values());
    }
    public String localHost() {
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) continue;
                java.util.Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof java.net.Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back to the socket address below.
        }
        return socket == null || socket.getLocalAddress() == null
                ? "" : socket.getLocalAddress().getHostAddress();
    }
    public int localPort() {
        return socket == null ? 0 : socket.getLocalPort();
    }
    public String nodeName() {
        return nodeName;
    }
    public void kick(Peer peer) {
        control(peer, KICK);
        peers.remove(peer.id);
        peerLastSeen.remove(peer.id);
        approvedPeers.remove(peer.id);
        post(() -> callback.onPeerRemoved(peer, "kicked"));
    }
    public void ban(Peer peer) { bannedNames.add(peer.name); control(peer, BAN); peers.remove(peer.id); peerLastSeen.remove(peer.id); approvedPeers.remove(peer.id); post(() -> callback.onPeerRemoved(peer, "banned")); }
    private void control(Peer peer, byte action) {
        try { sendRaw(peer.address(), CONTROL, UUID.randomUUID().toString(), 0, BinaryProtocol.control(action, peer.id, peer.name)); }
        catch (Exception e) { postFail("Could not update " + peer.name); }
    }
    public void requestDownload(Peer peer, FileInfo file) {
        try {
            approvedPeers.put(peer.id, peer);
            sendRaw(peer.address(), DOWNLOAD_REQUEST, UUID.randomUUID().toString(), 0, BinaryProtocol.fileId(file.id));
        }
        catch (Exception e) { postFail("Unable to request file"); }
    }
    public void upload(Peer peer, File file) throws Exception {
        if (!file.isFile()) throw new IllegalArgumentException("The selected upload is unavailable.");
        String transfer = UUID.randomUUID().toString();
        FileInfo metadata = new FileInfo(UUID.randomUUID().toString(), safeName(file.getName()), file.length(), sha256(file));
        pendingSends.put(transfer, new Outgoing(transfer, peer.address(), file, metadata, true));
        sendRaw(peer.address(), UPLOAD_OFFER, transfer, 0, BinaryProtocol.file(metadata));
    }
    public List<FileInfo> hostedFiles() {
        File[] files = hostedDir.listFiles(); if (files == null) return Collections.emptyList();
        List<FileInfo> result = new ArrayList<>();
        for (File f : files) if (f.isFile() && !f.getName().startsWith(".") && !f.getName().equals("catalog.zaZzProxy")) try {
            result.add(new FileInfo(f.getName(), f.getName(), f.length(), sha256(f)));
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
            output.write(BinaryProtocol.manifest(nodeName, hostedFiles()));
        }
        return catalog;
    }

    private void announce() throws Exception {
        byte[] hello = BinaryProtocol.hello(nodeId, nodeName, version, advertisedServer, avatar);
        String transfer = UUID.randomUUID().toString();
        java.util.Set<String> sentAddresses = new java.util.HashSet<>();
        sendAnnouncement(new InetSocketAddress(InetAddress.getByName("255.255.255.255"), PORT), transfer, hello, sentAddresses);
        java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces != null && interfaces.hasMoreElements()) {
            NetworkInterface network = interfaces.nextElement();
            if (!network.isUp() || network.isLoopback()) continue;
            for (java.net.InterfaceAddress interfaceAddress : network.getInterfaceAddresses()) {
                InetAddress broadcast = interfaceAddress.getBroadcast();
                if (broadcast != null) {
                    sendAnnouncement(new InetSocketAddress(broadcast, PORT), transfer, hello, sentAddresses);
                }
            }
        }
    }

    private void sendAnnouncement(InetSocketAddress target, String transfer, byte[] hello,
                                   java.util.Set<String> sentAddresses) {
        String address = target.getAddress().getHostAddress();
        if (sentAddresses.add(address)) {
            sendRaw(target, HELLO, transfer, 0, hello);
        }
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
        switch (type) {
            case HELLO: handleHello(from, BinaryProtocol.readHello(payload)); break;
            case CONNECTION_REQUEST: handleConnectionRequest(from, BinaryProtocol.readConnectionRequest(payload)); break;
            case CONNECTION_DECISION: handleConnectionDecision(from, transfer, BinaryProtocol.readDecision(payload)); break;
            case MANIFEST_REQUEST:
                if (findPeer(from) != null && approvedPeers.containsKey(findPeer(from).id)) sendManifest(from, transfer);
                break;
            case MANIFEST: handleManifest(from, BinaryProtocol.readManifest(payload)); break;
            case DOWNLOAD_REQUEST: startDownload(from, transfer, BinaryProtocol.readFileId(payload)); break;
            case UPLOAD_OFFER: handleUploadOffer(from, transfer, BinaryProtocol.readFile(payload)); break;
            case ERROR: postFail(BinaryProtocol.readError(payload)); break;
            case CONTROL: handleControl(from, BinaryProtocol.readControl(payload)); break;
            default: break;
        }
    }
    private void handleHello(InetSocketAddress from, BinaryProtocol.Hello hello) throws Exception {
        if (nodeId.equals(hello.id)) return;
        if (advertisedServer && bannedNames.contains(hello.name)) return;
        Peer peer = new Peer(hello.id, hello.name, hello.version, hello.server, from, hello.avatar);
        Peer old = peers.put(peer.id, peer);
        peerLastSeen.put(peer.id, System.currentTimeMillis());
        if (advertisedServer && !hello.server) {
            sendRaw(from, HELLO, UUID.randomUUID().toString(), 0,
                    BinaryProtocol.hello(nodeId, nodeName, version, true, avatar));
        }
        if (!advertisedServer || peer.server) {
            if (old == null || !old.host.equals(peer.host)) post(() -> callback.onPeer(peer));
        }
    }

    private void handleConnectionRequest(InetSocketAddress from, BinaryProtocol.ConnectionRequest request) {
        Peer peer = findPeer(from);
        if (peer == null) {
            peer = new Peer(request.peerId, request.peerName, version, false, from);
        } else {
            peer = new Peer(request.peerId, request.peerName, peer.version, false, from, peer.avatar);
        }
        peers.put(peer.id, peer);
        peerLastSeen.put(peer.id, System.currentTimeMillis());
        final Peer requested = peer;
        pendingConnections.put(requested.id, requested);
        post(() -> callback.onConnectionRequest(requested));
    }

    private void handleConnectionDecision(InetSocketAddress from, String transfer, BinaryProtocol.Decision decision) {
        Peer peer = findPeer(from);
        if (peer == null) peer = new Peer(decision.peerId, decision.peerName, version, true, from);
        if (decision.accepted) approvedPeers.put(peer.id, peer);
        Outgoing pending = pendingSends.remove(transfer);
        if (decision.accepted && pending != null) sends.put(transfer, pending);
        if (pending == null) {
            final Peer approved = peer;
            post(() -> callback.onConnectionDecision(approved, decision.accepted));
        }
    }

    private void handleUploadOffer(InetSocketAddress from, String transfer, FileInfo file) {
        Peer peer = findPeer(from);
        if (peer != null) {
            final Peer sender = peer;
            post(() -> callback.onUploadOffer(sender, transfer, file));
        }
    }

    private Peer findPeer(InetSocketAddress address) {
        for (Peer peer : peers.values()) if (peer.address().getAddress().equals(address.getAddress())) return peer;
        return null;
    }

    private void removeExpiredPeers() {
        long cutoff = System.currentTimeMillis() - 10_000L;
        for (Map.Entry<String, Long> entry : peerLastSeen.entrySet()) {
            if (entry.getValue() >= cutoff) continue;
            Peer expired = peers.remove(entry.getKey());
            peerLastSeen.remove(entry.getKey());
            approvedPeers.remove(entry.getKey());
            if (expired != null) post(() -> callback.onPeerRemoved(expired, "went offline"));
        }
    }

    private void refreshApprovedManifests() {
        if (!running || !advertisedServer) return;
        for (Peer peer : approvedPeers.values()) sendManifest(peer.address(), UUID.randomUUID().toString());
    }
    private void handleControl(InetSocketAddress from, BinaryProtocol.Control control) {
        if (advertisedServer) return;
        if (control.action != KICK && control.action != BAN) return;
        Peer removed = peers.remove(control.peerId);
        peerLastSeen.remove(control.peerId);
        if (removed == null) removed = new Peer(control.peerId, control.peerName, version, true, from);
        final Peer peer = removed;
        post(() -> callback.onPeerRemoved(peer, control.action == BAN ? "banned by server" : "kicked by server"));
    }
    private void sendManifest(InetSocketAddress to, String transfer) {
        try {
            sendRaw(to, MANIFEST, transfer, 0, BinaryProtocol.manifest(nodeName, hostedFiles()));
        } catch (Exception e) { sendError(to, transfer, "Could not create catalog"); }
    }
    private void handleManifest(InetSocketAddress from, BinaryProtocol.Manifest manifest) {
        try {
            Peer discovered = peers.get("manifest-" + from);
            if (discovered == null) {
                for (Peer candidate : peers.values()) {
                    if (candidate.address().equals(from)) {
                        discovered = candidate;
                        break;
                    }
                }
            }
            Peer peer = new Peer(discovered == null ? "manifest-" + from : discovered.id, manifest.name,
                    version, true, from, discovered == null ? new byte[0] : discovered.avatar);
            post(() -> callback.onManifest(peer, manifest.files));
        } catch (Exception e) { postFail("Invalid .zaZzproxy manifest received"); }
    }
    private void startDownload(InetSocketAddress to, String transfer, String fileId) {
        File file = new File(hostedDir, safeName(fileId));
        if (!file.isFile()) { sendError(to, transfer, "Requested file is unavailable"); return; }
        try { sends.put(transfer, new Outgoing(transfer, to, file, new FileInfo(file.getName(), file.getName(), file.length(), sha256(file)), false)); }
        catch (Exception e) { sendError(to, transfer, "Cannot read requested file"); }
    }
    private void acceptUpload(InetSocketAddress from, String transfer, FileInfo file) {
        try {
            receives.put(transfer, new Incoming(transfer, from, uniqueFile(hostedDir, safeName(file.name)), file, true));
        } catch (Exception e) { sendError(from, transfer, "Cannot accept upload"); }
    }
    private void receiveChunk(InetSocketAddress from, String transfer, int seq, byte[] data) {
        try {
            Incoming in = receives.get(transfer);
            if (in == null) {
                ByteBuffer b = ByteBuffer.wrap(data); int metaLength = b.getShort() & 0xffff;
                if (metaLength > b.remaining()) throw new IllegalArgumentException("Invalid file metadata");
                byte[] meta = new byte[metaLength]; b.get(meta); FileInfo f = BinaryProtocol.readFile(meta);
                Peer server = findPeer(from);
                String serverName = server == null ? "Server" : server.name;
                in = new Incoming(transfer, from, uniqueFile(downloadDirectory(serverName), safeName(f.name)), f, false); receives.put(transfer, in);
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
        final String transfer; final InetSocketAddress peer; final File file; final FileInfo metadata; final boolean upload;
        int acknowledged = -1, sent = -1; long lastSent;
        Outgoing(String transfer, InetSocketAddress peer, File file, FileInfo metadata, boolean upload) { this.transfer=transfer; this.peer=peer; this.file=file; this.metadata=metadata; this.upload=upload; }
        synchronized void ack(int sequence) { acknowledged = Math.max(acknowledged, sequence); }
        synchronized void pump() throws Exception {
            int chunks = Math.max(1, (int) ((metadata.bytes + CHUNK_BYTES - 1) / CHUNK_BYTES));
            if (acknowledged >= chunks - 1) { sends.remove(transfer); return; }
            if (sent > acknowledged && System.currentTimeMillis() - lastSent < 700) return;
            sent = acknowledged + 1; long offset = (long) sent * CHUNK_BYTES;
            byte[] content = readRange(file, offset, (int) Math.min(CHUNK_BYTES, metadata.bytes - offset));
            if (!upload && sent == 0) {
                byte[] info = BinaryProtocol.file(metadata);
                ByteBuffer b = ByteBuffer.allocate(2 + info.length + content.length).putShort((short) info.length).put(info).put(content); content = b.array();
            }
            sendRaw(peer, upload ? UPLOAD_CHUNK : FILE_CHUNK, transfer, sent, content); lastSent = System.currentTimeMillis();
            final long done = Math.min(metadata.bytes, offset + Math.min(CHUNK_BYTES, metadata.bytes - offset));
            post(() -> callback.onTransfer(metadata.name, done, metadata.bytes, upload));
        }
    }
    private final class Incoming {
        final String transfer; final InetSocketAddress peer; final File target, part; final FileInfo metadata; final boolean upload;
        int expected; long received;
        Incoming(String transfer, InetSocketAddress peer, File target, FileInfo metadata, boolean upload) throws Exception {
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
    private synchronized void sendRaw(InetSocketAddress to, byte type, String transfer, int seq, byte[] plain) {
        try {
            if (!running || socket == null || socket.isClosed()) throw new IOException("Network socket is not running");
            UUID id = UUID.fromString(transfer);
            byte[] cipher = encrypt(type, id, seq, plain);
            ByteBuffer packet = ByteBuffer.allocate(4 + 1 + 1 + 16 + 4 + 2 + cipher.length)
                    .putInt(MAGIC).put((byte) VERSION).put(type)
                    .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                    .putInt(seq).putShort((short) cipher.length).put(cipher);
            socket.send(new DatagramPacket(packet.array(), packet.position(), to));
        } catch (Exception error) {
            postFail("Unable to send packet to " + to.getAddress().getHostAddress() + ":"
                    + to.getPort() + " (" + error.getMessage() + ")");
        }
    }
    private void sendError(InetSocketAddress to, String transfer, String message) { try { sendRaw(to, ERROR, transfer, 0, BinaryProtocol.error(message)); } catch (Exception e) { postFail("Unable to send error response"); } }
    private byte[] encrypt(byte type, UUID id, int seq, byte[] plain) throws Exception { return Cipher.encrypt(key, type, id, seq, plain); }
    private byte[] decrypt(byte type, UUID id, int seq, byte[] ciphertext) throws Exception { return Cipher.decrypt(key, type, id, seq, ciphertext); }
    private static String sha256(File file) throws Exception { try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) { return Integrity.sha256(in); } }
    private static byte[] readAvatar(Context context, boolean server) {
        try {
            android.graphics.Bitmap source = android.graphics.BitmapFactory.decodeFile(ProfileAvatarStore.avatar(context, server).getAbsolutePath());
            android.graphics.Bitmap bitmap = source == null ? null : android.graphics.Bitmap.createScaledBitmap(source, 48, 48, true);
            if (bitmap == null) return new byte[0];
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 35, output);
            byte[] bytes = output.toByteArray();
            return bytes.length <= 700 ? bytes : new byte[0];
        } catch (RuntimeException ignored) {
            return new byte[0];
        }
    }
    private static void copy(java.io.InputStream input, File target) throws Exception { try(BufferedInputStream in=new BufferedInputStream(input);BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(target))){byte[]b=new byte[8192];for(int n;(n=in.read(b))>=0;)out.write(b,0,n);} }
    private static byte[] readRange(File file,long offset,int length)throws Exception{byte[] result=new byte[length];try(java.io.RandomAccessFile in=new java.io.RandomAccessFile(file,"r")){in.seek(offset);in.readFully(result);}return result;}
    private static String safeName(String name) { String clean = new File(name == null ? "file" : name).getName().replaceAll("[\\r\\n]", "_"); return clean.length() == 0 ? "file" : clean; }
    private static File uniqueFile(File directory, String name) { File result=new File(directory,name);int i=1;int dot=name.lastIndexOf('.');while(result.exists()){String stem=dot>0?name.substring(0,dot):name;String ext=dot>0?name.substring(dot):"";result=new File(directory,stem+" ("+(i++)+")"+ext);}return result; }
    private File uploadDirectory(String clientName) {
        File directory = new File(downloadDir, safeName(clientName));
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Could not create transfer folder");
        return directory;
    }
    private File downloadDirectory(String serverName) {
        File directory = new File(downloadDir, safeName(serverName));
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Could not create transfer folder");
        return directory;
    }
    private void post(Runnable runnable) { main.post(runnable); } private void postFail(String message) { post(() -> callback.onFailure(message)); }
    @Override public synchronized void close() {
        running = false;
        peerLastSeen.clear();
        peers.clear();
        approvedPeers.clear();
        pendingConnections.clear();
        if (socket != null) socket.close();
        receiver.shutdownNow();
        scheduler.shutdownNow();
    }
}
