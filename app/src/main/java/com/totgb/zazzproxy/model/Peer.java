package com.totgb.zazzproxy.model;

import java.net.InetSocketAddress;

/** Immutable description of a discovered peer. */
public final class Peer {
    public final String id;
    public final String name;
    public final String version;
    public final String host;
    public final boolean server;
    public final byte[] avatar;
    private final InetSocketAddress address;

    public Peer(String id, String name, String version, boolean server, InetSocketAddress address) {
        this(id, name, version, server, address, new byte[0]);
    }

    public Peer(String id, String name, String version, boolean server, InetSocketAddress address, byte[] avatar) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.server = server;
        this.address = address;
        this.avatar = avatar == null ? new byte[0] : avatar.clone();
        this.host = address.getAddress().getHostAddress();
    }

    public InetSocketAddress address() {
        return address;
    }
}
