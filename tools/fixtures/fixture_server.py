"""两个仅监听本机的 CMS 夹具：A 可播，B 标注中字但媒体返回 404。"""

import argparse
import json
import re
import threading
import time
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit


ROOT = Path(__file__).resolve().parent
TITLE = "Robert自检"


def parse_range(value, size):
    if not value:
        return 0, size - 1, False
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value.strip())
    if not match or not any(match.groups()):
        raise ValueError("不支持的 Range")
    first, last = match.groups()
    if first:
        start = int(first)
        end = min(int(last), size - 1) if last else size - 1
    else:
        if int(last) == 0:
            raise ValueError("空尾段")
        start, end = max(0, size - int(last)), size - 1
    if start >= size or end < start:
        raise ValueError("越界 Range")
    return start, end, True


class State:
    def __init__(self, request_limit, log_limit):
        self.started = time.monotonic()
        self.request_limit = request_limit
        self.log_limit = log_limit
        self.requests = 0
        self.logged = 0
        self.bytes_sent = 0
        self.counts = {"A": Counter(), "B": Counter()}
        self.lock = threading.Lock()
        self.stop = threading.Event()

    def record(self, side, event, status, count_request=True):
        with self.lock:
            if count_request:
                self.requests += 1
            self.counts[side][event] += 1
            current = self.requests
            if current >= self.request_limit:
                self.stop.set()
            if self.logged < self.log_limit:
                self.logged += 1
                print(json.dumps({"event": event, "side": side, "status": status,
                                  "request_number": current}), flush=True)

    def sent(self, count):
        with self.lock:
            self.bytes_sent += count

    def snapshot(self):
        with self.lock:
            return {"uptime_seconds": round(time.monotonic() - self.started, 2),
                    "request_count": self.requests, "request_limit": self.request_limit,
                    "video_bytes_sent": self.bytes_sent,
                    "A": dict(self.counts["A"]), "B": dict(self.counts["B"])}


def cms_item(side, base_url):
    return {
        "vod_id": "1", "type_id": "1", "type_name": "科幻片",
        "vod_name": TITLE, "vod_year": "2026", "vod_area": "原创测试",
        "vod_lang": "无对白", "vod_remarks": "1080P" if side == "A" else "HD中字",
        "vod_content": "本地生成的色条和正弦音，只用于播放器回退验证。",
        "vod_pic": "", "vod_play_from": "mp4",
        "vod_play_url": "正片$" + base_url + ("/video.mp4" if side == "A" else "/missing.mp4"),
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        # 不记录原始查询参数；日志仅输出固定路由事件和计数。
        return

    def headers_for(self, status, content_type, length, extra=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        for name, value in (extra or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.close_connection = True

    def json_response(self, status, data, event, head=False):
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.server.state.record(self.server.side, event, status)
        self.headers_for(status, "application/json; charset=utf-8", len(body))
        if not head:
            self.wfile.write(body)

    def video(self, head=False):
        if self.server.side != "A":
            self.json_response(404, {"error": "intentional_fixture_404"}, "media_404", head)
            return
        path = ROOT / "video.mp4"
        size = path.stat().st_size
        try:
            start, end, partial = parse_range(self.headers.get("Range"), size)
        except ValueError:
            self.server.state.record("A", "media_range_416", 416)
            self.headers_for(416, "video/mp4", 0, {"Content-Range": "bytes */" + str(size)})
            return
        status = 206 if partial else 200
        extra = {"Accept-Ranges": "bytes"}
        if partial:
            extra["Content-Range"] = "bytes %d-%d/%d" % (start, end, size)
        self.server.state.record("A", "media_head" if head else "media_range_206" if partial else "media_200", status)
        self.headers_for(status, "video/mp4", end - start + 1, extra)
        if head:
            return
        with path.open("rb") as stream:
            stream.seek(start)
            remaining = end - start + 1
            while remaining:
                block = stream.read(min(64 * 1024, remaining))
                if not block:
                    break
                self.wfile.write(block)
                self.server.state.sent(len(block))
                remaining -= len(block)

    def route(self, head=False):
        parsed = urlsplit(self.path)
        if parsed.path.rstrip("/") == "/api.php/provide/vod":
            query = parse_qs(parsed.query)
            action = query.get("ac", ["list"])[0]
            word = query.get("wd", [""])[0]
            ids = query.get("ids", [""])[0]
            event = "cms_search" if word else "cms_detail" if ids or action == "detail" else "cms_home_list"
            matched = not word or word.strip().casefold() in TITLE.casefold()
            matched = matched and (not ids or "1" in ids.split(","))
            matched = matched and query.get("pg", ["1"])[0] == "1"
            matched = matched and query.get("t", ["1"])[0] == "1"
            items = [cms_item(self.server.side, self.server.public_base)] if matched else []
            self.json_response(200, {"code": 1, "msg": "数据列表", "page": 1,
                                    "pagecount": 1, "limit": "20", "total": len(items),
                                    "class": [{"type_id": "1", "type_name": "科幻片"}],
                                    "list": items}, event, head)
        elif parsed.path in ("/video.mp4", "/missing.mp4"):
            if parsed.path == "/missing.mp4":
                self.json_response(404, {"error": "intentional_fixture_404"}, "media_404", head)
            else:
                self.video(head)
        elif parsed.path == "/stats":
            self.json_response(200, self.server.state.snapshot(), "stats", head)
        else:
            self.json_response(404, {"error": "not_found"}, "other_404", head)

    def do_GET(self):
        try:
            self.route()
        except (BrokenPipeError, ConnectionResetError):
            self.server.state.record(self.server.side, "client_disconnected", 0, count_request=False)

    def do_HEAD(self):
        self.route(head=True)

    def do_POST(self):
        self.json_response(405, {"error": "read_only_fixture"}, "method_405")

    do_DELETE = do_POST
    do_PUT = do_POST


def self_test():
    assert parse_range(None, 100) == (0, 99, False)
    assert parse_range("bytes=0-9", 100) == (0, 9, True)
    assert parse_range("bytes=10-", 100) == (10, 99, True)
    assert parse_range("bytes=-5", 100) == (95, 99, True)
    for value in ("bytes=100-", "bytes=0-1,4-5", "bytes=-0", "bytes=9-2"):
        try:
            parse_range(value, 100)
        except ValueError:
            continue
        raise AssertionError("非法 Range 未拒绝")
    a = cms_item("A", "http://10.0.2.2.nip.io:18080")
    b = cms_item("B", "http://10.0.2.2.nip.io:18081")
    assert a["vod_name"] == b["vod_name"] == TITLE
    assert a["vod_year"] == b["vod_year"] == "2026"
    assert a["vod_remarks"] == "1080P" and b["vod_remarks"] == "HD中字"
    assert a["vod_play_url"].endswith("/video.mp4") and b["vod_play_url"].endswith("/missing.mp4")
    state = State(3, 0)
    state.record("A", "media_200", 200)
    state.record("B", "media_404", 404)
    state.record("A", "media_range_206", 206)
    assert state.stop.is_set() and state.snapshot()["request_count"] == 3
    assert state.snapshot()["A"]["media_range_206"] == 1 and state.snapshot()["B"]["media_404"] == 1
    print(json.dumps({"self_test": "pass", "ports_opened": 0}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ports", nargs=2, type=int, default=[18080, 18081], metavar=("A", "B"))
    parser.add_argument("--public-host", default="127.0.0.1.nip.io")
    parser.add_argument("--duration", type=int, default=900)
    parser.add_argument("--max-requests", type=int, default=500)
    parser.add_argument("--log-limit", type=int, default=60)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if len(set(args.ports)) != 2 or any(not 1024 <= port <= 65535 for port in args.ports):
        parser.error("必须使用两个不同的非特权端口")
    if not re.fullmatch(r"[a-zA-Z0-9.-]+", args.public_host):
        parser.error("public-host 只能是域名或 IPv4")
    if not 1 <= args.duration <= 3600 or not 1 <= args.max_requests <= 5000 or not 0 <= args.log_limit <= 120:
        parser.error("运行时长、请求数或日志上限超出范围")
    if not (ROOT / "video.mp4").is_file():
        parser.error("先运行 generate_video.py 生成原创测试视频")
    state = State(args.max_requests, args.log_limit)
    servers = []
    threads_started = False
    try:
        for side, port in zip(("A", "B"), args.ports):
            server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
            server.daemon_threads = True
            server.side, server.state = side, state
            server.public_base = "http://%s:%d" % (args.public_host, port)
            servers.append(server)
        for server in servers:
            threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.2}, daemon=True).start()
        threads_started = True
        print(json.dumps({"event": "started", "bind": "127.0.0.1", "ports": args.ports,
                          "duration_seconds": args.duration, "max_requests": args.max_requests,
                          "public_host": args.public_host}), flush=True)
        state.stop.wait(args.duration)
    except KeyboardInterrupt:
        pass
    finally:
        for server in servers:
            if server.fileno() != -1:
                if threads_started:
                    server.shutdown()
                server.server_close()
        print(json.dumps({"event": "stopped", "stats": state.snapshot()}), flush=True)


if __name__ == "__main__":
    main()
