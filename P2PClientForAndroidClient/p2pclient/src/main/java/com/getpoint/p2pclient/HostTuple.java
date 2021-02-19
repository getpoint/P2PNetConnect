package com.getpoint.p2pclient;

import org.jetbrains.annotations.NotNull;

public class HostTuple {
    public final String ip;
    public final int port;
    public HostTuple(String a, int b) {
        ip = a;
        port = b;
    }

    @NotNull
    public String toString() {
        return ip + ":" + port;
    }

    public boolean equals(HostTuple hostTuple) {
        if (this == hostTuple) {
            return true;
        }

        if (this.ip.equals(hostTuple.ip)
                && this.port == hostTuple.port) {
            return true;
        }

        return false;
    }
}