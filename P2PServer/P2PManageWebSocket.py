import asyncio
import websockets
import json


class P2PManageWebSocket(object):
    def __init__(self, p2p_subscribe_server_host, all_p2p_info_dict):
        self.p2p_subscribe_server_host = p2p_subscribe_server_host
        self.p2p_subscribe_server = websockets.serve(self.handler, self.p2p_subscribe_server_host[0],
                                                     self.p2p_subscribe_server_host[1])
        self.all_p2p_info_dict = all_p2p_info_dict
        self.websocket_client_users = {}

    async def wait_p2p_notify(self, websocket):
        try:
            print("start wait_p2p_notify")
            message = await websocket.recv()
            print("wait: "+message)
            notify_dict = json.loads(message)
            if notify_dict['cmd'] == "notify_connect":
                if notify_dict['peer_client_id'] not in self.all_p2p_info_dict:
                    notify_dict['result'] = "failed"
                    notify_dict['info'] = "can not find peer_client_id %s" % notify_dict['peer_client_id']
                    await websocket.send(json.dumps(notify_dict))
                    print(notify_dict['info'])
                    return
                if notify_dict['local_client_id'] not in self.all_p2p_info_dict:
                    notify_dict['result'] = "failed"
                    notify_dict['info'] = "can not find local_client_id %s" % notify_dict['local_client_id']
                    await websocket.send(json.dumps(notify_dict))
                    print(notify_dict['info'])
                    return
                if notify_dict['peer_client_id'] not in self.websocket_client_users:
                    notify_dict['result'] = "failed"
                    notify_dict['info'] = "can not find websocket_client_user %s" % notify_dict['peer_client_id']
                    await websocket.send(json.dumps(notify_dict))
                    print(notify_dict['info'])
                    return

                # 通知相应的客户端进行连接
                user = self.websocket_client_users[notify_dict['peer_client_id']]
                request_dict = {'cmd': 'connect',
                                'ip': self.all_p2p_info_dict[notify_dict['local_client_id']]['ip_outside'],
                                'port': self.all_p2p_info_dict[notify_dict['local_client_id']]['port_outside']}
                await user.send(json.dumps(request_dict))
                message = await user.recv()
                response_dict = json.loads(message)
                print(response_dict)
                notify_dict["peer_client_info"] = self.all_p2p_info_dict[notify_dict['peer_client_id']]
                notify_dict["result"] = response_dict["result"]
                print("notify client %s connect %s:%d, response: %s" %
                      (notify_dict['peer_client_id'], request_dict['ip'], request_dict['port'], message))

                # 将相应的客户端的响应结果返回
                await websocket.send(json.dumps(notify_dict))
        except Exception as why:
            print("wait_p2p_notify exception: %s" % why)
            pass

    async def handler(self, websocket, path):
        if path == '/p2pinfo/subscribe':
            message = await websocket.recv()
            print("message: %s" % message)
            message_dict = json.loads(message)
            if "cmd" not in message_dict:
                print("unexpected message: %s" % message)
                return
            if message_dict["cmd"] == "register":
                if "client_id" not in message_dict:
                    message_dict["result"] = "failed"
                    message_dict["info"] = "can not find client_id"
                    await websocket.send(json.dumps(message_dict))
                    return
                if "type" not in message_dict:
                    message_dict["result"] = "failed"
                    message_dict["info"] = "can not find type"
                    await websocket.send(json.dumps(message_dict))
                    return
                message_dict["result"] = "success"
                await websocket.send(json.dumps(message_dict))
            else:
                message_dict["result"] = "failed"
                message_dict["info"] = "not support"
                await websocket.send(message_dict)
                return

            # 记录websocket客户端用户
            self.websocket_client_users[message_dict['client_id']] = websocket
            try:
                if message_dict['type'] == "client":
                    # 监听客户端的请求转发给对端
                    wait_closed_task = asyncio.ensure_future(websocket.wait_closed())
                    wait_p2p_notify_task = asyncio.ensure_future(self.wait_p2p_notify(websocket))
                    while 1:
                        done, pending = await asyncio.wait(
                            [wait_closed_task, wait_p2p_notify_task],
                            return_when=asyncio.FIRST_COMPLETED,
                        )
                        if wait_closed_task in done:
                            print("websocket /p2pinfo/subscribe from %s closed, type client"
                                  % message_dict['client_id'])
                            # 取消未完成的任务
                            for task in pending:
                                task.cancel()
                            # websocket断开连接, 退出
                            break
                        else:
                            # 循环等待p2p请求, 重新放入wait_p2p_notify_task
                            wait_p2p_notify_task = asyncio.ensure_future(self.wait_p2p_notify(websocket))
                            print("wait_p2p_notify_task handle end")
                if message_dict['type'] == "server":
                    # 服务端保持连接, 等待关闭
                    await websocket.wait_closed()
                    print("websocket /p2pinfo/subscribe from %s closed, type server" % message_dict['client_id'])
            except Exception as why:
                print("/p2pinfo/subscribe exception: %s" % why)
            finally:
                # 删除websocket客户端用户
                del self.websocket_client_users[message_dict['client_id']]
        else:
            async for message in websocket:
                print("hellotest", message)
                await websocket.send(message)

    def serve_forever(self):
        asyncio.get_event_loop().run_until_complete(self.p2p_subscribe_server)
        asyncio.get_event_loop().run_forever()
