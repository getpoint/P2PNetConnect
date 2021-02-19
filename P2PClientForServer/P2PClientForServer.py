import json
import threading

import requests
import socket
from requests.adapters import HTTPAdapter
from urllib3.poolmanager import PoolManager
import asyncio
import websockets
import time
from threading import Thread


class SourcePortAdapter(HTTPAdapter):
    """"Transport adapter" that allows us to set the source port."""

    def __init__(self, port, *args, **kwargs):
        self._source_port = port
        super(SourcePortAdapter, self).__init__(*args, **kwargs)

    def init_poolmanager(self, connections, maxsize, block=False):
        self.poolmanager = PoolManager(
            num_pools=connections, maxsize=maxsize,
            block=block, source_address=('', self._source_port))


def get_host_ip():
    s = None
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
    finally:
        if s is not None:
            s.close()
    return ip


class P2PClientManagement(object):
    def __init__(self, server, client_id, local_ip, local_listen_port):
        self.httpsession_with_source_port = requests.session()
        self.httpsession_with_source_port.mount("http://", SourcePortAdapter(local_listen_port))
        self.httpsession = requests.session()
        self.local_ip = local_ip
        self.local_listen_port = local_listen_port
        self.client_id = client_id
        self.server = server
        self.register_p2pinfo_thread_stop_flag = False
        self.p2p_websocket_client = None
        self.last_connect_time = 0
        self.connect_lock = threading.Lock()
        self.tcp_connect_with_same_source_port_interval = 0.5  # 使用相同tcp源端口的两次请求的间隔, 避免端口重用错误

    def __del__(self):
        self.httpsession_with_source_port.close()
        self.httpsession.close()

    def register_p2pinfo(self):
        p2pinfo_dict = {
            "client_id": self.client_id,
            "ip_inside": self.local_ip,
            "port_inside": self.local_listen_port
        }

        self.connect_lock.acquire()

        # 间隔较短, 可能上次的TCP连接还未结束, 导致直接复用端口报错, 设置0.5秒的间隔
        interval = time.time() - self.last_connect_time
        if interval < self.tcp_connect_with_same_source_port_interval:
            time.sleep(self.tcp_connect_with_same_source_port_interval - interval)

        try:
            self.httpsession_with_source_port.post(self.server + "/p2pinfo/register", json=p2pinfo_dict)
        except Exception as why:
            print('except:', why)
        self.last_connect_time = time.time()

        self.connect_lock.release()

    def get_subscribe_p2pinfo_server(self):
        result = self.httpsession.get(self.server + "/p2pinfo/subscribe/server")
        print(result.status_code, result.content)
        content_dict = json.loads(result.content)
        return content_dict

    async def handle_p2p_msg(self, websocket_client):
        message = await websocket_client.recv()
        message_dict = json.loads(message)
        if message_dict['cmd'] == 'connect':
            client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            # 仅发包打洞, 无需等待对端响应, 设置超时时间0.01秒
            client.settimeout(0.01)

            self.connect_lock.acquire()

            # 间隔较短, 可能上次的TCP连接还未完全结束, 导致直接复用端口报错, 设置0.3秒的间隔
            interval = time.time() - self.last_connect_time
            if interval < self.tcp_connect_with_same_source_port_interval:
                time.sleep(self.tcp_connect_with_same_source_port_interval - interval)

            client.bind((self.local_ip, self.local_listen_port))
            try:
                client.connect((message_dict['ip'], message_dict['port']))
                message_dict["result"] = "success"
            except Exception as why:
                print('except:', why)
                message_dict["result"] = "failed"
            finally:
                if "result" not in message_dict:
                    message_dict["result"] = "failed"
                client.close()
                await websocket_client.send(json.dumps(message_dict))
                print("connect cmd handle end, %s:%d connect %s:%d"
                      % (self.local_ip, self.local_listen_port, message_dict['ip'], message_dict['port']))
                self.last_connect_time = time.time()

                self.connect_lock.release()
        else:
            message_dict["result"] = "failed"
            message_dict["info"] = "not support"
            await self.p2p_websocket_client.send(json.dumps(message_dict))

    async def subscribe_p2pinfo(self):
        p2p_subscribe_server_host = self.get_subscribe_p2pinfo_server()
        if self.p2p_websocket_client is None or self.p2p_websocket_client.closed is True:
            url = f'ws://{p2p_subscribe_server_host["ip"]}:{p2p_subscribe_server_host["port"]}/p2pinfo/subscribe'
            print("reconnect", url)
            self.p2p_websocket_client = await websockets.connect(url, ping_interval=10)

        # 注册客户端
        await self.p2p_websocket_client.send(
            "{\"cmd\": \"register\", \"client_id\": \"%s\", \"type\": \"server\"}" % self.client_id)
        message = await self.p2p_websocket_client.recv()
        message_dict = json.loads(message)
        if "result" not in message_dict and message_dict["result"] != "success":
            raise Exception("p2pinfo subscribe failed, response msg: %s" % message)

        wait_closed_task = asyncio.ensure_future(
            self.p2p_websocket_client.wait_closed())
        wait_p2p_notify_task = asyncio.ensure_future(
            self.handle_p2p_msg(self.p2p_websocket_client))
        while 1:
            done, pending = await asyncio.wait(
                [wait_closed_task, wait_p2p_notify_task],
                return_when=asyncio.FIRST_COMPLETED,
            )
            if wait_closed_task in done:
                print("websocket /p2pinfo/subscribe client closed")
                # 取消未完成的任务
                for task in pending:
                    task.cancel()
                # websocket断开连接, 退出
                return
            else:
                # 不断处理p2p请求将, 重新加入任务
                wait_p2p_notify_task = asyncio.ensure_future(
                    self.handle_p2p_msg(self.p2p_websocket_client))

    def register_p2pinfo_forever(self):
        # 每隔5秒使用50300端口重新注册, 避免外部nat端口被回收，已测试10秒会被回收
        while self.register_p2pinfo_thread_stop_flag is not True:
            self.register_p2pinfo()
            time.sleep(5)
        self.register_p2pinfo_thread_stop_flag = False

    def register_p2pinfo_forever_stop(self):
        self.register_p2pinfo_thread_stop_flag = True


if __name__ == '__main__':
    config_file = open('config.json', 'r')
    config = config_file.read()
    config_file.close()
    config_dict = json.loads(config)
    CL = P2PClientManagement(config_dict["p2p_server_address"], config_dict["p2p_client_id"], get_host_ip(), config_dict["local_server_port"])

    t = Thread(target=CL.register_p2pinfo_forever)
    t.start()

    while True:
        try:
            asyncio.get_event_loop().run_until_complete(CL.subscribe_p2pinfo())
        except Exception as why1:
            print("/p2pinfo/subscribe exception: %s" % why1)

        time.sleep(5)
