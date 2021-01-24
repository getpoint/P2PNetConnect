package com.getpoint.p2pclient;

import org.jetbrains.annotations.NotNull;

public class LocalPeerAddress {
    public HostTuple LocalAddress;
    public HostTuple PeerAddress;
    public LocalPeerAddress(HostTuple a, HostTuple b) {
        LocalAddress = a;
        PeerAddress = b;
    }

    @NotNull
    public String toString(){
        return LocalAddress + "," + PeerAddress;
    }
}