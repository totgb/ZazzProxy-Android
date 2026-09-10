package com.totgb.zazzproxy.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A peer's advertised file catalog. */
public final class Manifest {
    private final String nodeName;
    private final List<FileInfo> files;

    public Manifest(String nodeName, List<FileInfo> files) {
        this.nodeName = nodeName;
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
    }

    public String nodeName() { return nodeName; }
    public List<FileInfo> files() { return files; }
}
