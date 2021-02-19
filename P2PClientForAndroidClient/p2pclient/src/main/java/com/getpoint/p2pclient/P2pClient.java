package com.getpoint.p2pclient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
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


class NetWorkStateReceiver extends BroadcastReceiver {
    boolean isNetworkChanged = true;

    @Override
    public void onReceive(Context context, Intent intent) {
        System.out.println("Network state changes");
        isNetworkChanged = true;
    }
}

class P2pClientWebsocket extends WebSocketClient {
    public final Object p2pWebsocketNotifySyncObject = new Object();
    public HostTuple lastPeerHost;

    public P2pClientWebsocket(URI serverUri) {
        super(serverUri);
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
                lastPeerHost = new HostTuple(peer_info.getString("ip_outside"), peer_info.getInt("port_outside"));
                synchronized (p2pWebsocketNotifySyncObject) {
                    p2pWebsocketNotifySyncObject.notify();
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

    /**
     * 向websocket服务器进行注册
     * @param localClientId   需要注册到p2p服务器上的本地client_id, 告知对端连接本地client_id
     */
    public void register(String localClientId) throws InterruptedException {
        connectBlocking();
        send("{\"cmd\": \"register\", \"client_id\": \""+ localClientId +"\", \"type\": \"client\"}");
    }

    /**
     * 通知打洞, 打洞成功后返回对端的公网地址
     * @param localClientId   需要注册到p2p服务器上的本地client_id, 告知对端连接本地client_id
     * @param peerClientId   需要通过p2p连接的对端的client_id
     * @return  success return p2pPeerHost or fail return null
     */
    public HostTuple getPeerHost(String localClientId, String peerClientId) {
        HostTuple p2pPeerHost;

        JSONObject jsonObject = new JSONObject();
        try {
            jsonObject.put("cmd", "notify_connect");
            jsonObject.put("local_client_id", localClientId);
            jsonObject.put("peer_client_id", peerClientId);
        } catch (JSONException e) {
            e.printStackTrace();
            System.out.println("create jsonObject failed");
            return null;
        }
        send(jsonObject.toString());

        // 等待响应, 当p2pClientWebsocket接收到响应时会进行notify()
        synchronized (p2pWebsocketNotifySyncObject) {
            try {
                p2pWebsocketNotifySyncObject.wait(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
                return null;
            }
        }
        if (lastPeerHost == null) {
            System.out.println("can not get notify_connect response");
            return null;
        }

        p2pPeerHost = lastPeerHost;
        // 清空上次获取得到的host
        lastPeerHost = null;

        return p2pPeerHost;
    }
}

/**
 * P2P客户端, 实例化后使用getP2PLocalPeerAddress获取打洞链接, 暂不支持对称型打洞
 * 对于网络状态会发生变化的android设备, 建议注册netWorkStateReceiver避免网络变化后nat类型不准确, 导致对称型NAT时仍尝试打洞
 */
public class P2pClient {
    private final int MAX_READ_BYTES = 1024;
    public NetWorkStateReceiver netWorkStateReceiver = new NetWorkStateReceiver();
    private P2pClientWebsocket p2pClientWebsocket = null;
    private NatType natType;

    /**
     * Get IP address from first non-localhost interface
     * @param useIPv4   true=return ipv4, false=return ipv6
     * @return  address or empty string
     */
    @NotNull
    private String getIPAddress(boolean useIPv4) {
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

    private InsideOutsideAddress getOutsideAddress(String p2PServerIp, int p2PServerPort, String localClientId, HostTuple insideAddress) {
        String responseStr = null;
        Socket socket = null;
        HostTuple outsideAddress;
        char[] readChars = new char[MAX_READ_BYTES];
        int len;
        int readLen;
        SocketAddress socAddress;
        JSONObject jsonObject;

        try {
            socket = new Socket();
            socAddress = new InetSocketAddress(p2PServerIp, p2PServerPort);
            if (insideAddress == null) {
                socket.connect(socAddress, 5000);
                insideAddress = new HostTuple(getIPAddress(true), socket.getLocalPort());
            } else {
                SocketAddress localSocAddress = new InetSocketAddress(insideAddress.ip, insideAddress.port);
                socket.bind(localSocAddress);
                socket.connect(socAddress, 5000);
            }
            socket.setSoTimeout(5000);

            OutputStreamWriter out = new OutputStreamWriter(socket.getOutputStream());
            InputStreamReader in = new InputStreamReader(socket.getInputStream());

            String jsonContent = "{\"client_id\": " + "\"" +localClientId + "\""
                    + ", \"ip_inside\": " + "\"" + insideAddress.ip + "\""
                    + ", \"port_inside\": " + insideAddress.port
                    + "}";

            String sendString = "POST /p2pinfo/register HTTP/1.1\r\n"
                    + "Host: " + p2PServerIp + ":" + p2PServerPort+ "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: " + jsonContent.length() + "\r\n"
                    + "Connection: Close\r\n"
                    + "\r\n"
                    + jsonContent;
            out.write(sendString);
            out.flush();

            readLen = 0;
            while (true) {
                len = in.read(readChars, readLen, MAX_READ_BYTES - readLen);
                if (len == -1 || readLen >= MAX_READ_BYTES) {
                    System.out.println("read end, read bytes " + readLen);
                    break;
                }
                readLen = readLen + len;
            }
            if (readLen > 0) {
                responseStr = new String(readChars, 0, readLen);
                System.out.println(responseStr);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
            }
        }

        try {
            String httpContent = responseStr.substring(responseStr.indexOf("\r\n\r\n") + "\r\n\r\n".length());
            jsonObject = new JSONObject(httpContent);
            outsideAddress = new HostTuple(jsonObject.getString("ip_outside"), jsonObject.getInt("port_outside"));
        } catch (JSONException e) {
            e.printStackTrace();
            return null;
        }

        return new InsideOutsideAddress(insideAddress, outsideAddress);
    }

    /**
     * 确定当前设备所处网络环境的nat类型, 主要用于当检测到是对称型时, 不再耗费时间打洞
     * @param p2PServerIp   在公网上的p2p服务器ip
     * @param p2PServerPort   在公网上的p2p服务器端口
     * @param localClientId   需要注册到p2p服务器上的本地client_id, 告知对端连接本地client_id
     * @return  success return HostTuple or fail return null
     */
    private NatType getNatType(String p2PServerIp, int p2PServerPort, String localClientId) {
        InsideOutsideAddress lastInsideOutsideAddress;
        InsideOutsideAddress newInsideOutsideAddress;

        // 在网络状态未发生变化时, nat类型一般不发生变化, 不再重新获取
        if (!netWorkStateReceiver.isNetworkChanged) {
            return natType;
        }

        // 先获取一个内部地址和外部地址的映射关系
        lastInsideOutsideAddress = getOutsideAddress(p2PServerIp, p2PServerPort, localClientId, null);

        // 间隔100ms, 等待上次socket的端口完全释放
        // 避免java.net.BindException: bind failed: EADDRINUSE (Address already in use)
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 再次使用上一次的内部地址, 获取与外部地址的映射关系
        newInsideOutsideAddress = getOutsideAddress(p2PServerIp, p2PServerPort, localClientId, lastInsideOutsideAddress.insideAddress);

        // 如果短时间连续2次相同的内部地址和外部地址的映射关系是相同的, 则说明不是对称型NAT
        if (lastInsideOutsideAddress.equals(newInsideOutsideAddress))
        {
            natType = NatType.PORT_RESTRICTED_CONE_NAT;
        } else {
            natType = NatType.SYMMETRIC_NAT;
        }

        // 已在网络状态发生变化时重新获取nat类型, 重置isNetworkChanged
        netWorkStateReceiver.isNetworkChanged = false;

        return natType;
    }

    /**
     * 向p2p公网服务器进行注册, 通知服务器更新local_client_id的外网地址, 在通知服务器打洞前调用
     * @param p2PServerIp   在公网上的p2p服务器ip
     * @param p2PServerPort   在公网上的p2p服务器端口
     * @param localClientId   需要注册到p2p服务器上的本地client_id, 告知对端连接本地client_id
     * @return  success return HostTuple or fail return null
     */
    private HostTuple registerP2PInfo(String p2PServerIp, int p2PServerPort, String localClientId) {
        InsideOutsideAddress insideOutsideAddress;

        insideOutsideAddress = getOutsideAddress(p2PServerIp, p2PServerPort, localClientId, null);
        if (insideOutsideAddress == null) {
            System.out.println("getOutsideAddress failed");
            return null;
        }

        return insideOutsideAddress.insideAddress;
    }

    /**
     * 获取p2p服务器对应的websocket服务器地址
     * @param p2PServerIp   在公网上的p2p服务器ip
     * @param p2PServerPort   在公网上的p2p服务器端口
     * @return  success return websocketServerAddress or fail return null
     */
    private HostTuple getP2PSubscribeServer(String p2PServerIp, int p2PServerPort) {
        String responseStr = null;
        char[] readChars = new char[MAX_READ_BYTES];
        int len;
        int readLen = 0;
        Socket socket = null;

        try {
            socket = new Socket();
            SocketAddress socAddress = new InetSocketAddress(p2PServerIp, p2PServerPort);
            socket.connect(socAddress, 5000);
            socket.setSoTimeout(5000);

            OutputStreamWriter out = new OutputStreamWriter(socket.getOutputStream());
            InputStreamReader in = new InputStreamReader(socket.getInputStream());

            String send_string = "GET /p2pinfo/subscribe/server HTTP/1.1\r\n"
                    + "Host: " + p2PServerIp + ":" + p2PServerPort+ "\r\n"
                    + "Content-Type: application/json\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: Close\r\n"
                    + "\r\n";
            out.write(send_string);
            out.flush();

            while (true) {
                len = in.read(readChars, readLen, MAX_READ_BYTES - readLen);
                if (len == -1 || readLen >= MAX_READ_BYTES) {
                    System.out.println("read end, read bytes " + readLen);
                    break;
                }
                readLen = readLen + len;
            }

            if (readLen > 0) {
                responseStr = new String(readChars, 0, readLen);
                System.out.println(responseStr);
                String httpContent = responseStr.substring(responseStr.indexOf("\r\n\r\n") + "\r\n\r\n".length());
                JSONObject jsonObject = new JSONObject(httpContent);
                return new HostTuple(jsonObject.getString("ip"), jsonObject.getInt("port"));
            } else {
                System.out.println("read failed, read bytes " + readLen);
            }

            socket.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ioException) {
                    ioException.printStackTrace();
                }
            }
        }

        return null;
    }

    /**
     * 获取能够通过p2p打洞连接远端的本地地址以及对端的地址, 暂不支持重入
     * @param p2PServerIp   在公网上的p2p服务器ip
     * @param p2PServerPort   在公网上的p2p服务器端口
     * @param localClientId   需要注册到p2p服务器上的本地client_id, 告知对端连接本地client_id
     * @param peerClientId   需要通过p2p连接的对端的client_id
     * @return  success return LocalPeerAddress or fail return null
     */
    public synchronized LocalPeerAddress getP2PLocalPeerAddress(String p2PServerIp, int p2PServerPort, String localClientId, String peerClientId)
    {
        URI websocketServerUri;
        HostTuple p2pSubscribeServerHost;
        HostTuple p2pLocalHost;
        HostTuple p2pPeerHost;

        if (getNatType(p2PServerIp, p2PServerPort, localClientId) == NatType.SYMMETRIC_NAT) {
            System.out.println("not support SYMMETRIC_NAT");
            return null;
        }

        // p2pClientWebsocket未初始化或者连接断开则重新建立连接
        if (p2pClientWebsocket == null || p2pClientWebsocket.isClosed()) {
            // 对端打洞完成后需要服务器实时通知到本地客户端, 通过订阅基于websocket的服务器实现实时通知
            p2pSubscribeServerHost = getP2PSubscribeServer(p2PServerIp, p2PServerPort);
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
            p2pClientWebsocket = new P2pClientWebsocket(websocketServerUri);
            try {
                p2pClientWebsocket.register(localClientId);
            } catch (InterruptedException e) {
                e.printStackTrace();
                return null;
            }
        }

        p2pLocalHost = registerP2PInfo(p2PServerIp, p2PServerPort, localClientId);
        if (p2pLocalHost == null) {
            System.out.println("register_p2p_info failed");
            return null;
        }

        p2pPeerHost = p2pClientWebsocket.getPeerHost(localClientId, peerClientId);
        if (p2pPeerHost == null) {
            System.out.println("getPeerHost failed");
            return null;
        }

        System.out.println("local:" + p2pLocalHost+" ,peer:" + p2pPeerHost);

        return new LocalPeerAddress(p2pLocalHost, p2pPeerHost);
    }
}
