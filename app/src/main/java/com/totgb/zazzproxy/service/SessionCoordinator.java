package com.totgb.zazzproxy.service;

/** Process-wide role lock: exactly one client or server session may own the UDP socket. */
public final class SessionCoordinator {
    public enum Role { CLIENT, SERVER }
    private static Role activeRole;

    private SessionCoordinator() {}

    public static synchronized boolean acquire(Role role) {
        if (activeRole != null && activeRole != role) return false;
        activeRole = role;
        return true;
    }

    public static synchronized void release(Role role) {
        if (activeRole == role) activeRole = null;
    }

    public static synchronized Role activeRole() {
        return activeRole;
    }
}
