import time
from http.server import HTTPServer, BaseHTTPRequestHandler
import json
from threading import Thread
from P2PManageWebSocket import P2PManageWebSocket

all_p2p_info_dict = {}


class Resquest(BaseHTTPRequestHandler):
    def handle_p2pinfo_register(self, read_str):
        p2pinfo_dict = json.loads(read_str)
        p2pinfo_dict["ip_outside"] = self.client_address[0]
        p2pinfo_dict["port_outside"] = self.client_address[1]
        p2pinfo_dict["register_time"] = time.time()

        if p2pinfo_dict['client_id'] in all_p2p_info_dict:
            last_p2pinfo_dict = all_p2p_info_dict[p2pinfo_dict['client_id']]
            register_interval = last_p2pinfo_dict["register_time"] - p2pinfo_dict["register_time"]
            if register_interval < 5 \
                    and last_p2pinfo_dict["port_inside"] == p2pinfo_dict["port_inside"] \
                    and last_p2pinfo_dict["ip_inside"] == p2pinfo_dict["ip_inside"] \
                    and last_p2pinfo_dict["port_outside"] == p2pinfo_dict["port_outside"] \
                    and last_p2pinfo_dict["ip_outside"] == p2pinfo_dict["ip_outside"]:
                # 如果5秒内的两次请求内部端口和内部IP都未发生变化， 外部端口也未发生变化，说明是可以建立p2p打洞链接的情况
                p2pinfo_dict["can_p2p_across_nat"] = True
            else:
                # 如果5秒内内部端口和内部IP都未发生变化， 但是外部端口发生变化，说明是对称型nat
                # 或者暂时无法判断是否可以打洞的情况
                # 实际测试流媒体所在环境是端口受限圆锥型NAT（Port-Restricted cone NAT),当对端是Symmetric NAT时必然无法打洞成功
                # 对于处于受限圆锥型NAT（（Address-）Restricted cone NAT）下的流媒体环境优先级低
                pass

        # 更新p2p注册信息
        all_p2p_info_dict[p2pinfo_dict['client_id']] = p2pinfo_dict

        return 200, json.dumps(p2pinfo_dict).encode()

    def do_POST(self):
        read_str = self.rfile.read(int(self.headers['content-length']))
        if self.path == "/p2pinfo/register":
            code, send_content = self.handle_p2pinfo_register(read_str)
        else:
            code = 404
            send_content = "{\"err_info\": \"can not find url %s}" % self.path

        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(send_content)))
        self.end_headers()
        self.wfile.write(send_content)

    def do_GET(self):
        if self.path == "/p2pinfo/subscribe/server":
            code = 200
            p2p_subscribe_websocket_server_dict = {"ip": p2p_websocket_server_host_outside[0],
                                                   "port": p2p_websocket_server_host_outside[1]}
            send_content = json.dumps(p2p_subscribe_websocket_server_dict).encode()
        else:
            code = 404
            send_content = "{\"err_info\": \"can not find url %s}" % self.path

        self.send_response(code)
        self.send_header('Content-type', 'application/json')
        self.send_header('Content-Length', str(len(send_content)))
        self.end_headers()
        self.wfile.write(send_content)


if __name__ == '__main__':
    config_file = open('config.json', 'r')
    config = config_file.read()
    config_file.close()
    config_dict = json.loads(config)
    p2p_websocket_server_host_outside = (config_dict["local_ip"], config_dict["websocket_port"])

    server = HTTPServer(('0.0.0.0', config_dict["http_port"]), Resquest)
    print("Starting server, listen at: %s:%s" % ('0.0.0.0', config_dict["http_port"]))
    Thread(target=server.serve_forever).start()

    CL_P2PManageWebSocket = P2PManageWebSocket(('0.0.0.0', config_dict["websocket_port"]), all_p2p_info_dict)
    print("Starting P2PManageWebSocket server, listen at: %s:%s" % ('0.0.0.0', config_dict["websocket_port"]))
    CL_P2PManageWebSocket.serve_forever()
