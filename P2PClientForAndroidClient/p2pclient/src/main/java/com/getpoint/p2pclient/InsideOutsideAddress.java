package com.getpoint.p2pclient;

import org.jetbrains.annotations.NotNull;

public class InsideOutsideAddress {
    public HostTuple insideAddress;
    public HostTuple outsideAddress;
    public InsideOutsideAddress(HostTuple a, HostTuple b) {
        insideAddress = a;
        outsideAddress = b;
    }

    @NotNull
    public String toString(){
        return insideAddress + "," + outsideAddress;
    }

    public boolean equals(InsideOutsideAddress insideOutsideAddress) {
        if (this == insideOutsideAddress) {
            return true;
        }

        if (this.insideAddress.ip.equals(insideOutsideAddress.insideAddress.ip)
                && this.insideAddress.port == insideOutsideAddress.insideAddress.port
                && this.outsideAddress.ip.equals(insideOutsideAddress.outsideAddress.ip)
                && this.outsideAddress.port == insideOutsideAddress.outsideAddress.port) {
            return true;
        }

        return false;
    }
}