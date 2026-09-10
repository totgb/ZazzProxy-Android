package com.totgb.zazzproxy.network;

/** Bounds owned by the datagram transport rather than by the application protocol. */
final class TransportLimits {
    static final int MAX_PACKET_BYTES = 1200;

    private TransportLimits() { }
}
