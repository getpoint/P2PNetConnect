package com.getpoint.p2pclient;

import org.jetbrains.annotations.NotNull;

public class LocalPeerAddress {
    public HostTuple localAddress;
    public HostTuple peerAddress;
    public LocalPeerAddress(HostTuple a, HostTuple b) {
        localAddress = a;
        peerAddress = b;
    }

    @NotNull
    public String toString() {
        return localAddress + "," + peerAddress;
    }

    public boolean equals(LocalPeerAddress localPeerAddress) {
        if (this == localPeerAddress) {
            return true;
        }

        if (this.localAddress.ip.equals(localPeerAddress.localAddress.ip)
                && this.localAddress.port == localPeerAddress.localAddress.port
                && this.peerAddress.ip.equals(localPeerAddress.peerAddress.ip)
                && this.peerAddress.port == localPeerAddress.peerAddress.port) {
            return true;
        }

        return false;
    }
}