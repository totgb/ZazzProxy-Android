package com.totgb.zazzproxy.network;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.crypto.spec.SecretKeySpec;

/** Owns the UDP socket, receive loop, authenticated framing, and raw packet sends. */
final class UdpTransport implements Closeable {
    static final class Packet {
        final byte type;
        final UUID id;
        final int sequence;
        final byte[] payload;

        Packet(byte type, UUID id, int sequence, byte[] payload) {
            this.type = type;
            this.id = id;
            this.sequence = sequence;
            this.payload = payload;
        }
    }

    interface Listener {
        void onPacket(InetSocketAddress from, Packet packet) throws Exception;
    }

    private final UdpPacketCodec codec;
    private final Listener listener;
    private final Consumer<String> error;
    private final ExecutorService receiver = Executors.newSingleThreadExecutor();
    private final ExecutorService packetProcessor = Executors.newFixedThreadPool(2);
    private volatile boolean running;
    private DatagramSocket socket;

    UdpTransport(SecretKeySpec key, Listener listener, Consumer<String> error) {
        codec = new UdpPacketCodec(key);
        this.listener = listener;
        this.error = error;
    }

    synchronized void start(int port) throws Exception {
        if (running) return;
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.setBroadcast(true);
        socket.bind(new InetSocketAddress(port));
        running = true;
        receiver.execute(this::receiveLoop);
    }

    synchronized void send(InetSocketAddress target, byte type, String transfer,
                           int sequence, byte[] payload) {
        try {
            if (!running || socket == null || socket.isClosed()) {
                throw new IOException("Network socket is not running");
            }
            UUID id = UUID.fromString(transfer);
            byte[] packet = codec.encode(type, id, sequence, payload);
            socket.send(new DatagramPacket(packet, packet.length, target));
        } catch (Exception e) {
            error.accept("Unable to send packet to " + target.getAddress().getHostAddress() + ":"
                    + target.getPort() + " (" + e.getMessage() + ")");
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[TransportLimits.MAX_PACKET_BYTES];
        while (running) {
            try {
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                socket.receive(datagram);
                Packet packet = codec.decode(datagram.getData(), datagram.getLength());
                if (packet != null) {
                    InetSocketAddress source = new InetSocketAddress(datagram.getAddress(), datagram.getPort());
                    packetProcessor.execute(() -> {
                        try {
                            listener.onPacket(source, packet);
                        } catch (Exception e) {
                            error.accept("Packet processing failed: " + e.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                if (running) error.accept("Network listener stopped: " + e.getMessage());
            }
        }
    }

    String localHost() {
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

    int localPort() {
        return socket == null ? 0 : socket.getLocalPort();
    }

    @Override public synchronized void close() {
        running = false;
        if (socket != null) socket.close();
        receiver.shutdownNow();
        packetProcessor.shutdownNow();
    }
}
