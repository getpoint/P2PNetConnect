package com.getpoint.p2pclient;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

class P2pClientWebsocket extends WebSocketClient{
    private final Object lock;
    public HostTuple last_peer_host;
    public P2pClientWebsocket(URI serverUri, Object lock) {
        super(serverUri);
        this.lock = lock;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("new connection opened");
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("closed with exit code " + code + " additional info: " + reason);
    }

    @Override
    public void onMessage(String message) {
        System.out.println("received message: " + message);
        try {
            JSONObject jsonObject = new JSONObject(message);
            String cmd = jsonObject.getString("cmd");
            if (cmd.equals("notify_connect")) {
                JSONObject peer_info = (JSONObject) jsonObject.get("peer_client_info");
                last_peer_host = new HostTuple(peer_info.getString("ip_outside"), peer_info.getInt("port_outside"));
                synchronized (lock) {
                    lock.notify();
                }
            } else if (cmd.equals("register")) {
                String result = jsonObject.getString("result");
                if (result.equals("failed")) {
                    this.close();
                }
            }
        } catch (JSONException e) {
            System.out.println("Socket exception");
            e.printStackTrace();
            this.close();
        }
    }

    @Override
    public void onMessage(ByteBuffer message) {
        System.out.println("received ByteBuffer");
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("an error occurred:" + ex);
    }

}

public class P2pClient{
    P2pClientWebsocket p2pClientWebsocket = null;
    final Object lock;
    final int max_read_bytes = 1024;
    public P2pClient() {
        this.lock = new Object();
    }
    /**
     * Get IP address from first non-localhost interface
     * @param useIPv4   true=return ipv4, false=return ipv6
     * @return  address or empty string
     */
    @NotNull
    public static String getIPAddress(boolean useIPv4) {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        //boolean isIPv4 = InetAddressUtils.isIPv4Address(sAddr);
                        boolean isIPv4 = sAddr.indexOf(':')<0;

                        if (useIPv4) {
                            if (isIPv4)
                                return sAddr;
                        } else {
                            if (!isIPv4) {
                                int delim = sAddr.indexOf('%'); // drop ip6 zone suffix
                                return delim<0 ? sAddr.toUpperCase() : sAddr.substring(0, delim).toUpperCase();
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) { } // for now eat exceptions
        return "";
    }

    private HostTuple register_p2p_info(String p2p_server_ip, int p2p_server_port, String local_client_id)
    {
        int local_port = 0;
        String response_str = null;
        Socket socket;
        String ip_local = getIPAddress(true);
        int i;
        char[] chs = new char[max_read_bytes];
        int len = 0;
        int read_len = 0;
        for (i = 0; i < 2; i++) {
            // TODO: 可以优化为不再每次确定, 重复两次让p2p server判断是否是对称型Symmetric NAT
            try {

                if (local_port == 0) {
                    socket = new Socket();
                    SocketAddress socAddress = new InetSocketAddress(p2p_server_ip, p2p_server_port);
                    socket.connect(socAddress, 5000);
                    socket.setSoTimeout(5000);
                    local_port = socket.getLocalPort();
                } else {
                    socket = new Socket();
                    SocketAddress socAddress = new InetSocketAddress(p2p_server_ip, p2p_server_port);
                    SocketAddress localSocAddress = new InetSocketAddress(ip_local, local_port);
                    socket.bind(localSocAddress);
                    socket.connect(socAddress, 5000);
                    socket.setSoTimeout(5000);
                }
                OutputStreamWriter out = new OutputStreamWriter(socket.getOutputStream());
                InputStreamReader in = new InputStreamReader(socket.getInputStream());

                String json_content = "{\"client_id\": " + "\"" +local_client_id + "\""
                        + ", \"ip_inside\": " + "\"" + ip_local + "\""
                        + ", \"port_inside\": " + local_port
                        + "}";

                String send_string = "POST /p2pinfo/register HTTP/1.1\r\n"
                        + "Host: " + p2p_server_ip + ":" + p2p_server_port+ "\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + json_content.length() + "\r\n"
                        + "Connection: Close\r\n"
                        + "\r\n"
                        + json_content;
                out.write(send_string);
                out.flush();

                read_len = 0;
                while (true)
                {
                    len = in.read(chs, read_len, max_read_bytes - read_len);
                    if (len == -1 || read_len >= max_read_bytes)
                    {
                        System.out.println("read end, read bytes " + read_len);
                        break;
                    }
                    read_len = read_len + len;
                }
                if (read_len != -1)
                {
                    response_str = new String(chs, 0, read_len);
                    System.out.println(response_str);
                }
                socket.close();
                if (i != 1) {
                    // 间隔100ms, 等待上次socket的端口完全释放
                    // 避免java.net.BindException: bind failed: EADDRINUSE (Address already in use)
                    Thread.sleep(100);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (response_str == null) {
            return null;
        }

        String http_content = response_str.substring(response_str.indexOf("\r\n\r\n") + "\r\n\r\n".length());
        try {
            // TODO: 暂只考虑端口受限圆锥型NAT（Port-Restricted cone NAT)的服务端被连接的情况
            JSONObject jsonObject = new JSONObject(http_content);
            if (jsonObject.get("can_p2p_across_nat").equals(true)) {
                return new HostTuple(ip_local, local_port);
            }
            else {
                System.out.println("can_p2p_across_nat is not true, under Symmetric NAT");
                return null;
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return null;
    }

    private HostTuple get_p2p_subscribe_server(String p2p_server_ip, int p2p_server_port)
    {
        String response_str = null;
        char[] chs = new char[max_read_bytes];
        int len = 0;
        int read_len = 0;
        try {
            Socket socket = new Socket();
            SocketAddress socAddress = new InetSocketAddress(p2p_server_ip, p2p_server_port);
            socket.connect(socAddress, 5000);
            socket.setSoTimeout(5000);

            OutputStreamWriter out = new OutputStreamWriter(socket.getOutputStream());
            InputStreamReader in = new InputStreamReader(socket.getInputStream());

            String send_string = "GET /p2pinfo/subscribe/server HTTP/1.1\r\n"
                    + "Host: " + p2p_server_ip + ":" + p2p_server_port+ "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: Close\r\n"
                    + "\r\n";
            out.write(send_string);
            out.flush();

            while (true)
            {
                len = in.read(chs, read_len, max_read_bytes - read_len);
                if (len == -1 || read_len >= max_read_bytes)
                {
                    System.out.println("read end, read bytes " + read_len);
                    break;
                }
                read_len = read_len + len;
            }
            if (read_len != -1)
            {
                response_str = new String(chs, 0, read_len);
                System.out.println(response_str);
            }
            socket.close();

            assert response_str != null;
            String http_content = response_str.substring(response_str.indexOf("\r\n\r\n") + "\r\n\r\n".length());
            JSONObject jsonObject = new JSONObject(http_content);
            return new HostTuple(jsonObject.getString("ip"), jsonObject.getInt("port"));
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * 获取能够通过p2p打洞连接远端的本地地址以及对端的地址, 暂不支持重入
     * @param p2p_server_ip   在公网上的p2p服务器ip
     * @param p2p_server_port   在公网上的p2p服务器端口
     * @param local_client_id   需要注册到p2p服务器上的本地client_id, 告知对端连接本地client_id
     * @param peer_client_id   需要通过p2p连接的对端的client_id
     * @return  success return LocalPeerAddress or fail return null
     */
    public LocalPeerAddress getP2PLocalPeerAddress(String p2p_server_ip, int p2p_server_port, String local_client_id, String peer_client_id)
    {
        URI websocketServerUri;
        HostTuple p2pSubscribeServerHost;
        HostTuple p2pLocalHost;
        HostTuple p2pPeerHost;

        if (p2pClientWebsocket == null || p2pClientWebsocket.isClosed()) {
            // 对端打洞完成后需要服务器实时通知到本地客户端, 通过订阅基于websocket的服务器实现实时通知
            p2pSubscribeServerHost = get_p2p_subscribe_server(p2p_server_ip, p2p_server_port);
            if (p2pSubscribeServerHost == null) {
                System.out.println("get_p2p_subscribe_server failed");
                return null;
            }

            try {
                websocketServerUri = new URI("ws://" + p2pSubscribeServerHost + "/p2pinfo/subscribe");
            } catch (URISyntaxException e) {
                e.printStackTrace();
                return null;
            }
            p2pClientWebsocket = new P2pClientWebsocket(websocketServerUri, lock);
            try {
                p2pClientWebsocket.connectBlocking();
            } catch (InterruptedException e) {
                e.printStackTrace();
                return null;
            }
        }
        p2pClientWebsocket.send("{\"cmd\": \"register\", \"client_id\": \""+ local_client_id +"\", \"type\": \"client\"}");

        p2pLocalHost = register_p2p_info(p2p_server_ip, p2p_server_port, local_client_id);
        if (p2pLocalHost == null) {
            System.out.println("register_p2p_info failed");
            return null;
        }

        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("cmd", "notify_connect");
            jsonObject.put("local_client_id", local_client_id);
            jsonObject.put("peer_client_id", peer_client_id);
        } catch (JSONException e) {
            e.printStackTrace();
            System.out.println("create jsonObject failed");
            return null;
        }
        p2pClientWebsocket.send(jsonObject.toString());
        // 等待响应, 当p2pClientWebsocket接收到响应时会进行lock.notify()
        synchronized (lock) {
            try {
                lock.wait(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
                return null;
            }
        }
        if (p2pClientWebsocket.last_peer_host == null) {
            System.out.println("can not get notify_connect response");
            return null;
        }
        p2pPeerHost = new HostTuple(p2pClientWebsocket.last_peer_host.ip, p2pClientWebsocket.last_peer_host.port);
        // 清空上次获取得到的host
        p2pClientWebsocket.last_peer_host = null;

        System.out.println("local:" + p2pLocalHost+" ,peer:" + p2pPeerHost);

        return new LocalPeerAddress(p2pLocalHost, p2pPeerHost);
    }
}
