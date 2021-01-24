package com.getpoint.p2pclient;

public class HostTuple {
    public final String ip;
    public final int port;
    public HostTuple(String a, int b) {
        ip = a;
        port = b;
    }
    public String toString(){
        return ip + ":" + port;
    }
}