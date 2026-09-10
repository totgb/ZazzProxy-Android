package com.totgb.zazzproxy.network.protocol;

import com.totgb.zazzproxy.model.BinaryFields;
import com.totgb.zazzproxy.model.FileInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Binary payload codec for all control messages sent over UDP. */
public final class BinaryProtocol {
    private static final int MAX_FILES = 10_000;

    private BinaryProtocol() {}

    public static byte[] hello(String id, String name, String version, boolean server, byte[] avatar) throws Exception {
        Buffer buffer = output(); DataOutputStream output = buffer.output;
        BinaryFields.writeString(output, id); BinaryFields.writeString(output, name);
        BinaryFields.writeString(output, version); output.writeBoolean(server);
        if (avatar == null || avatar.length > 700) throw new IllegalArgumentException("Profile picture is too large");
        output.writeShort(avatar.length); output.write(avatar);
        return buffer.bytes();
    }

    public static byte[] hello(String id, String name, String version, boolean server) throws Exception {
        return hello(id, name, version, server, new byte[0]);
    }

    public static Hello readHello(byte[] bytes) throws Exception {
        DataInputStream input = input(bytes);
        Hello result = new Hello(BinaryFields.readString(input), BinaryFields.readString(input),
                BinaryFields.readString(input), input.readBoolean(), readBytes(input, 700));
        requireEnd(input);
        return result;
    }

    public static byte[] fileId(String id) throws Exception {
        Buffer buffer = output(); BinaryFields.writeString(buffer.output, id); return buffer.bytes();
    }

    public static String readFileId(byte[] bytes) throws Exception {
        DataInputStream input = input(bytes); String id = BinaryFields.readString(input); requireEnd(input); return id;
    }

    public static byte[] manifest(String name, List<FileInfo> files) throws Exception {
        if (files.size() > MAX_FILES) throw new IllegalArgumentException("Too many manifest files");
        Buffer buffer = output(); DataOutputStream output = buffer.output;
        BinaryFields.writeString(output, name); output.writeInt(files.size());
        for (FileInfo file : files) file.writeTo(output);
        return buffer.bytes();
    }

    public static Manifest readManifest(byte[] bytes) throws Exception {
        DataInputStream input = input(bytes);
        String name = BinaryFields.readString(input); int count = input.readInt();
        if (count < 0 || count > MAX_FILES) throw new IllegalArgumentException("Invalid manifest file count");
        List<FileInfo> files = new ArrayList<>(count);
        for (int i = 0; i < count; i++) files.add(FileInfo.readFrom(input));
        requireEnd(input);
        return new Manifest(name, files);
    }

    public static byte[] file(FileInfo file) throws Exception {
        Buffer buffer = output(); file.writeTo(buffer.output); return buffer.bytes();
    }

    public static FileInfo readFile(byte[] bytes) throws Exception {
        DataInputStream input = input(bytes); FileInfo file = FileInfo.readFrom(input); requireEnd(input); return file;
    }

    public static byte[] error(String message) throws Exception {
        Buffer buffer = output(); BinaryFields.writeString(buffer.output, message); return buffer.bytes();
    }

    public static byte[] control(byte action, String peerId, String peerName) throws Exception {
        Buffer buffer = output();
        buffer.output.writeByte(action);
        BinaryFields.writeString(buffer.output, peerId);
        BinaryFields.writeString(buffer.output, peerName);
        return buffer.bytes();
    }

    public static byte[] connectionRequest(String peerId, String peerName) throws Exception {
        Buffer buffer = output();
        BinaryFields.writeString(buffer.output, peerId);
        BinaryFields.writeString(buffer.output, peerName);
        return buffer.bytes();
    }

    public static ConnectionRequest readConnectionRequest(byte[] bytes) throws Exception {
        DataInputStream input = input(bytes);
        ConnectionRequest request = new ConnectionRequest(
                BinaryFields.readString(input), BinaryFields.readString(input));
        requireEnd(input);
        return request;
    }

    public static byte[] decision(boolean accepted, String peerId, String peerName) throws Exception {
        Buffer buffer = output();
        buffer.output.writeBoolean(accepted);
        BinaryFields.writeString(buffer.output, peerId);
        BinaryFields.writeString(buffer.output, peerName);
        return buffer.bytes();
    }

    public static Decision readDecision(byte[] bytes) throws Exception {
        DataInputStream input = input(bytes);
        Decision decision = new Decision(input.readBoolean(), BinaryFields.readString(input),
                BinaryFields.readString(input));
        requireEnd(input);
        return decision;
    }

    public static Control readControl(byte[] bytes) throws Exception {
        DataInputStream input = input(bytes);
        Control control = new Control(input.readByte(), BinaryFields.readString(input), BinaryFields.readString(input));
        requireEnd(input);
        return control;
    }

    public static String readError(byte[] bytes) throws Exception {
        DataInputStream input = input(bytes); String message = BinaryFields.readString(input); requireEnd(input); return message;
    }

    private static Buffer output() { return new Buffer(); }
    private static DataInputStream input(byte[] bytes) { return new DataInputStream(new ByteArrayInputStream(bytes)); }
    private static void requireEnd(DataInputStream input) throws Exception {
        if (input.available() != 0) throw new IllegalArgumentException("Trailing bytes in binary payload");
    }

    private static byte[] readBytes(DataInputStream input, int max) throws Exception {
        int length = input.readUnsignedShort();
        if (length > max) throw new IllegalArgumentException("Binary image field is too large");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static final class Buffer {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream output = new DataOutputStream(bytes);
        byte[] bytes() throws Exception { output.flush(); return bytes.toByteArray(); }
    }

    public static final class Hello {
        public final String id, name, version;
        public final boolean server;
        public final byte[] avatar;
        public Hello(String id, String name, String version, boolean server, byte[] avatar) {
            this.id = id; this.name = name; this.version = version; this.server = server; this.avatar = avatar;
        }
    }

    public static final class Manifest {
        public final String name;
        public final List<FileInfo> files;
        public Manifest(String name, List<FileInfo> files) { this.name = name; this.files = files; }
    }

    public static final class Control {
        public final byte action;
        public final String peerId;
        public final String peerName;
        public Control(byte action, String peerId, String peerName) {
            this.action = action; this.peerId = peerId; this.peerName = peerName;
        }
    }

    public static final class ConnectionRequest {
        public final String peerId, peerName;
        public ConnectionRequest(String peerId, String peerName) {
            this.peerId = peerId;
            this.peerName = peerName;
        }
    }

    public static final class Decision {
        public final boolean accepted;
        public final String peerId, peerName;
        public Decision(boolean accepted, String peerId, String peerName) {
            this.accepted = accepted;
            this.peerId = peerId;
            this.peerName = peerName;
        }
    }
}
