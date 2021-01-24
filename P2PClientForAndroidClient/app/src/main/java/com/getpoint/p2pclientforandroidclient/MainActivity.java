package com.getpoint.p2pclientforandroidclient;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.getpoint.p2pclient.LocalPeerAddress;
import com.getpoint.p2pclient.P2pClient;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;

public class MainActivity extends AppCompatActivity implements View.OnClickListener{
    private final String[] PERMISSIONS = {
            Manifest.permission.INTERNET
    };

    P2pClient p2pClient;

    private boolean hasPermissions(Context context, String... permissions) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Button button;

        setContentView(R.layout.activity_main);
        if (!hasPermissions(this, PERMISSIONS)) {
            ActivityCompat.requestPermissions(this, PERMISSIONS, 1);
        }
        button = findViewById(R.id.p2p_test_button);
        button.setOnClickListener(this);
        p2pClient = new P2pClient();
    }

    private void p2pTest()
    {
        EditText p2pServerText = null;
        EditText p2pLocalIdText = null;
        EditText p2pPeerIdText = null;
        URI serverUri = null;
        LocalPeerAddress localPeerAddress = null;
        String localId = null;
        String peerId = null;

        // 解析p2p服务器地址
        p2pServerText = (EditText) findViewById(R.id.p2p_server_text);
        try {
            serverUri = new URI(p2pServerText.getText().toString());
        } catch (URISyntaxException e) {
            e.printStackTrace();
            makeToastTextShow("can not parse p2p server addresss, example: http://xxx:80");
            return;
        }
        if (-1 == serverUri.getPort()) {
            makeToastTextShow("p2p server addresss invalid, need append port, example: http://xxx:80");
            return;
        }
        // 解析本地id和对端id
        p2pLocalIdText = (EditText) findViewById(R.id.p2p_local_id_text);
        p2pPeerIdText = (EditText) findViewById(R.id.p2p_peer_id_text);
        localId = p2pLocalIdText.getText().toString();
        peerId = p2pPeerIdText.getText().toString();

        // 获取本地和对端的可用于p2p的地址
        long start_time = System.currentTimeMillis();
        localPeerAddress = p2pClient.getP2PLocalPeerAddress(serverUri.getHost(), serverUri.getPort(), localId, peerId);
        long used_time = System.currentTimeMillis() - start_time;
        if (null == localPeerAddress) {
            makeToastTextShow("getP2PLocalPeerAddress failed");
            return;
        } else {
            makeToastTextShow("getP2PLocalPeerAddress success in "+ used_time +" ms.\n" +
                    "local_id: " + localId + ", localadress: " + localPeerAddress.LocalAddress + "\n" +
                    "peer_id: " + peerId + ", peeradress: " + localPeerAddress.PeerAddress + "\n");
        }

        // 测试p2p是否打洞成功
        Socket socket = new Socket();
        try {
            SocketAddress socAddress = new InetSocketAddress(localPeerAddress.PeerAddress.ip, localPeerAddress.PeerAddress.port);
            SocketAddress localSocAddress = new InetSocketAddress(localPeerAddress.LocalAddress.ip, localPeerAddress.LocalAddress.port);
            socket.bind(localSocAddress);
            socket.connect(socAddress, 5000);
            socket.setSoTimeout(5000);
            OutputStreamWriter out = new OutputStreamWriter(socket.getOutputStream());
            out.write("test p2p is success");
            out.flush();
            // 连接成功并发送数据成功认为p2p成功
            makeToastTextShow("p2p test success");
        } catch (IOException e) {
            makeToastTextShow("p2p test fail");
            e.printStackTrace();
        }
        try {
            socket.close();
        } catch (IOException e) {
            System.out.println("socket.close fail");
            e.printStackTrace();
        }
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.p2p_test_button)
        {
            // 由于android规范主线程不能有阻塞操作, 所以网络操作需要起线程
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    p2pTest();
                }
            });
            thread.start();
        }
    }

    public void makeToastTextShow(final String showString) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, showString, Toast.LENGTH_SHORT).show();
            }
        });
    }

}
