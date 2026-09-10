package com.totgb.zazzproxy.network;

import com.totgb.zazzproxy.model.Peer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe peer state used by the UDP transport. Keeping this state outside
 * the packet receiver makes authorization and expiry updates independently
 * testable without changing the transport API.
 */
final class PeerRegistry {
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final java.util.Set<String> bannedNames = ConcurrentHashMap.newKeySet();
    private final Map<String, Peer> approved = new ConcurrentHashMap<>();
    private final Map<String, Peer> pending = new ConcurrentHashMap<>();

    Map<String, Peer> peers() { return peers; }
    Map<String, Long> lastSeen() { return lastSeen; }
    java.util.Set<String> bannedNames() { return bannedNames; }
    Map<String, Peer> approved() { return approved; }
    Map<String, Peer> pending() { return pending; }

    void clear() {
        peers.clear();
        lastSeen.clear();
        approved.clear();
        pending.clear();
    }
}
