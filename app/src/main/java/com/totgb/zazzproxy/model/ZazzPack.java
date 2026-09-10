package com.totgb.zazzproxy.model;

import java.util.List;

/** Versioned logical package exchanged by archive and transfer layers. */
public final class ZazzPack {
    public final int version;
    public final Manifest manifest;

    public ZazzPack(int version, Manifest manifest) {
        this.version = version;
        this.manifest = manifest;
    }
}
