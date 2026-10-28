#!/usr/bin/env python3
"""开发维护用的受限 CMS 探测器；只读元数据及可选 HLS 清单。"""

import argparse
import ipaddress
import json
import re
import socket
import ssl
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from http.client import InvalidURL
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASES = [
    {"title": "流浪地球", "year": 2019, "category": "电影"},
    {"title": "西游记", "year": 1986, "category": "国产剧"},
    {"title": "老友记第一季", "query": "老友记", "year": 1994,
     "season": 1, "episode": 1, "category": "美剧"},
    {"title": "国家宝藏第一季", "query": "国家宝藏", "year": 2017,
     "season": 1, "episode": 1, "category": "综艺"},
    {"title": "熊出没", "year": 2012, "episode": 1, "category": "动画"},
    {"title": "舌尖上的中国第一季", "query": "舌尖上的中国", "year": 2012,
     "season": 1, "episode": 1, "category": "纪录片"},
]
ADULT_CATEGORY = re.compile(r"伦理|理论|成人|情色|色情|福利")
SEASON = re.compile(r"第([零〇一二两三四五六七八九十\d]+)季|season\s*(\d+)", re.I)


class ProbeError(Exception):
    def __init__(self, kind, status=None):
        self.kind = kind
        self.status = status

    def evidence(self):
        result = {"class": self.kind}
        if self.status is not None:
            result["http_status"] = self.status
        return result


def checked_url(url):
    try:
        raw = str(url)
        if re.search(r"[\x00-\x1f\x7f]", raw):
            raise ValueError("URL 包含控制字符")
        parsed = urllib.parse.urlsplit(raw)
        port = parsed.port
        if parsed.scheme.lower() not in ("http", "https") or not parsed.hostname:
            raise ValueError("仅支持 HTTP／HTTPS")
        if parsed.username or parsed.password:
            raise ProbeError("credential_in_url")
        if ":" in parsed.hostname:
            host = "[" + ipaddress.IPv6Address(parsed.hostname).compressed + "]"
        else:
            host = parsed.hostname.encode("idna").decode("ascii").lower()
            labels = host[:-1].split(".") if host.endswith(".") else host.split(".")
            if len(host.rstrip(".")) > 253 or any(
                    not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label)
                    for label in labels):
                raise ValueError("主机名称无效")
        authority = host + (":" + str(port) if port is not None else "")

        def encode_component(value, safe):
            # 保留已有 %xx，不能把已转义的斜线或签名参数再编码为 %25xx。
            value = re.sub(r"%(?![0-9a-fA-F]{2})", "%25", value)
            return urllib.parse.quote(value, safe=safe + "%", encoding="utf-8", errors="strict")

        path = encode_component(parsed.path, "/:@!$&'()*+,;=-._~")
        query = encode_component(parsed.query, "/?:@!$&'()*+,;=[]-._~")
        fragment = encode_component(parsed.fragment, "/?:@!$&'()*+,;=[]-._~")
        return urllib.parse.urlunsplit((parsed.scheme.lower(), authority, path, query, fragment))
    except (UnicodeError, ValueError, InvalidURL):
        raise ProbeError("unsupported_url") from None


def api_url(base, **params):
    try:
        parsed = urllib.parse.urlsplit(checked_url(base))
        query = dict(urllib.parse.parse_qsl(parsed.query, keep_blank_values=True))
        query.update(params)
        return checked_url(urllib.parse.urlunsplit(parsed._replace(
            query=urllib.parse.urlencode(query), fragment="")))
    except (UnicodeError, ValueError, InvalidURL):
        raise ProbeError("unsupported_url") from None


class Redirects(urllib.request.HTTPRedirectHandler):
    def __init__(self, client):
        self.client = client

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        newurl = checked_url(newurl)
        self.client.reserve_request()
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class Client:
    def __init__(self, timeout, max_requests, max_seconds):
        self.timeout = timeout
        self.max_requests = max_requests
        self.requests = 0
        self.deadline = time.monotonic() + max_seconds
        # 使用标准证书校验，不接受调用者提供的 Cookie、授权头或代理凭据。
        self.opener = urllib.request.build_opener(Redirects(self))

    def reserve_request(self):
        if self.requests >= self.max_requests or time.monotonic() >= self.deadline:
            raise ProbeError("budget_exhausted")
        self.requests += 1

    def fetch(self, url, limit, manifest=False):
        url = checked_url(url)
        self.reserve_request()
        end = min(self.deadline, time.monotonic() + self.timeout)
        started = time.monotonic()
        try:
            request = urllib.request.Request(url, headers={
                "User-Agent": "RobertTV-SourceProbe/0.1 (metadata-only)",
                "Accept": "application/json, application/vnd.apple.mpegurl, text/plain",
            })
            with self.opener.open(request, timeout=max(0.1, end - started)) as response:
                final_url = checked_url(response.geturl())
                content_type = response.headers.get_content_type()
                playlist_types = {"audio/mpegurl", "audio/x-mpegurl", "audio/vnd.apple.mpegurl"}
                if manifest and content_type.startswith(("video/", "audio/")) and content_type not in playlist_types:
                    raise ProbeError("unexpected_media_response")
                body = bytearray()
                while len(body) <= limit:
                    if time.monotonic() >= end:
                        raise ProbeError("timeout")
                    # read1 返回当前可取得的一块；避免一次等待完整大响应。
                    chunk = response.read1(min(4096, limit + 1 - len(body)))
                    if not chunk:
                        break
                    body.extend(chunk)
                    if manifest and len(body) >= 16 and not bytes(body).lstrip(b"\xef\xbb\xbf \t\r\n").startswith(b"#EXTM3U"):
                        raise ProbeError("not_hls_manifest")
                truncated = len(body) > limit
                if truncated and not manifest:
                    raise ProbeError("response_too_large")
                return {
                    "body": bytes(body[:limit]), "truncated": truncated,
                    "elapsed_ms": round((time.monotonic() - started) * 1000),
                    "url": final_url,
                }
        except urllib.error.HTTPError as error:
            kind = "forbidden" if error.code in (401, 403) else "http_error"
            status = error.code
            error.close()
            raise ProbeError(kind, status) from None
        except urllib.error.URLError as error:
            if isinstance(error.reason, (UnicodeError, ValueError, InvalidURL)):
                kind = "unsupported_url"
            elif isinstance(error.reason, ssl.SSLError):
                kind = "tls"
            elif isinstance(error.reason, (socket.timeout, TimeoutError)):
                kind = "timeout"
            else:
                kind = "network"
            raise ProbeError(kind) from None
        except (socket.timeout, TimeoutError):
            raise ProbeError("timeout") from None
        except ssl.SSLError:
            raise ProbeError("tls") from None
        except (UnicodeError, ValueError, InvalidURL):
            raise ProbeError("unsupported_url") from None
        except OSError:
            raise ProbeError("network") from None

    def cms(self, url):
        response = self.fetch(url, 1024 * 1024)
        try:
            data = json.loads(response["body"].decode("utf-8-sig"))
        except (UnicodeError, ValueError):
            raise ProbeError("invalid_json") from None
        if not isinstance(data, dict) or not isinstance(data.get("list"), list):
            raise ProbeError("cms_schema")
        if data.get("code") not in (None, 1, "1"):
            raise ProbeError("cms_rejected")
        return data, response["elapsed_ms"]


def number(value):
    text = str(value)
    if text.isdigit():
        return int(text)
    digits = {c: i for i, c in enumerate("零一二三四五六七八九")}
    text = text.replace("〇", "零").replace("两", "二")
    if "十" in text:
        left, right = text.split("十", 1)
        return digits.get(left, 1) * 10 + digits.get(right, 0)
    return digits.get(text)


def title_parts(title):
    text = unicodedata.normalize("NFKC", str(title)).lower()
    found = SEASON.search(text)
    season = number(found.group(1) or found.group(2)) if found else None
    text = SEASON.sub("", text)
    # 某些源把年份放入标题；只有详情年份相符才会最终确认匹配。
    text = re.sub(r"[\s\W_]+", "", text)
    text = re.sub(r"(?<=.)(?:19|20)\d{2}$", "", text)
    return text, season


def safe_text(value, limit=120):
    text = re.sub(r"https?://\S+", "[URL omitted]", str(value or ""))
    text = re.sub(r"\b(token|cookie|password|authorization|secret|signature)\s*[:=]\s*\S+",
                  "[redacted]", text, flags=re.I)
    return re.sub(r"[\x00-\x1f\x7f]", "", text)[:limit]


def metadata(item, case):
    expected_title, title_season = title_parts(case["title"])
    actual_title, actual_season = title_parts(item.get("vod_name", ""))
    wanted_season = case.get("season", title_season)
    year_match = None
    if case.get("year") is not None:
        year_match = str(item.get("vod_year", "")).strip() == str(case["year"])
    season_match = None if wanted_season is None else actual_season == wanted_season
    title_match = expected_title == actual_title
    category = str(item.get("type_name", ""))
    source_claim = bool(re.search(r"中字|中英|中文字幕", str(item.get("vod_remarks", ""))))
    # vod_lang 为内容语言，vod_sub 为副标题；均不是中文字幕证据。
    confirmed = title_match and year_match is not False and season_match is not False
    return {
        "title": safe_text(item.get("vod_name")), "year": safe_text(item.get("vod_year"), 10),
        "category": safe_text(category, 40), "season": actual_season,
        "title_match": title_match, "year_match": year_match, "season_match": season_match,
        "metadata_match": bool(confirmed and not ADULT_CATEGORY.search(category)),
        "subtitle_status": "source_claimed_chinese" if source_claim else "unknown",
        "playback_decoded": False,
    }


def hls_summary(response):
    text = response["body"].decode("utf-8-sig", errors="replace")
    if not text.lstrip().startswith("#EXTM3U"):
        raise ProbeError("not_hls_manifest")
    return {
        "is_hls": True, "kind": "master" if "#EXT-X-STREAM-INF:" in text else "media",
        "sample_truncated": response["truncated"],
        "segment_entries_seen": text.count("#EXTINF:"),
        "has_subtitle_rendition": bool(re.search(r"#EXT-X-MEDIA:.*TYPE=SUBTITLES", text)),
        "declares_chinese_subtitle": any(
            line.startswith("#EXT-X-MEDIA:") and "TYPE=SUBTITLES" in line and re.search(
                r'LANGUAGE="(?:zh|zho|chi|cmn|yue)(?:-[^"]*)?"', line, re.I)
            for line in text.splitlines()),
        "has_key_declaration": "#EXT-X-KEY:" in text,
        "playback_decoded": False,
    }, text


def probe_hls(client, item, case):
    flags = str(item.get("vod_play_from", "")).split("$$$")
    groups = str(item.get("vod_play_url", "")).split("$$$")
    episode = case.get("episode", 1)
    for flag, group in zip(flags, groups):
        if "m3u8" not in flag.lower():
            continue
        entries = group.split("#")
        # 只选明确集号；无集号的单项电影可使用唯一播放项。
        for entry in entries:
            if "$" not in entry:
                continue
            label, url = entry.split("$", 1)
            episode_number = re.search(r"(?:第)?(\d+)(?:集|话|期)?", label)
            if len(entries) > 1 and (not episode_number or int(episode_number.group(1)) != episode):
                continue
            url = checked_url(url)
            if not urllib.parse.urlsplit(url).path.lower().endswith(".m3u8"):
                continue
            response = client.fetch(url, 16 * 1024, manifest=True)
            result, text = hls_summary(response)
            result["episode_label"] = safe_text(label, 40)
            result["elapsed_ms"] = response["elapsed_ms"]
            if result["kind"] == "master":
                lines = text.splitlines()
                for index, line in enumerate(lines[:-1]):
                    if line.startswith("#EXT-X-STREAM-INF:"):
                        child = lines[index + 1].strip()
                        if child and not child.startswith("#"):
                            child_url = urllib.parse.urljoin(response["url"], child)
                            child_url = checked_url(child_url)
                            if not urllib.parse.urlsplit(child_url).path.lower().endswith(".m3u8"):
                                raise ProbeError("unsupported_hls_child")
                            child_response = client.fetch(child_url, 16 * 1024, manifest=True)
                            result["child"], _ = hls_summary(child_response)
                            break
            return result
    raise ProbeError("no_matching_hls_entry")


def probe_source(client, source, cases, include_hls):
    result = {"source_key": source["key"], "cases": []}
    hls_attempted = False
    allowed_categories = set(source.get("categories", []))
    for case in cases:
        row = {"expected": {k: safe_text(case[k]) if isinstance(case[k], str) else case[k]
                            for k in ("title", "year", "season", "episode", "category") if k in case}}
        result["cases"].append(row)
        try:
            search, elapsed = client.cms(api_url(source["api"], ac="list", wd=case.get("query", case["title"])))
            items = [item for item in search["list"] if isinstance(item, dict)]
            row["search"] = {"elapsed_ms": elapsed, "returned_count": len(items), "first_page_only": True}
            wanted_title = title_parts(case["title"])[0]
            candidates = [item for item in items if title_parts(item.get("vod_name", ""))[0] == wanted_title]
            candidates = [item for item in candidates if not ADULT_CATEGORY.search(str(item.get("type_name", "")))]
            ids = [str(item.get("vod_id", "")) for item in candidates[:3]]
            ids = [item_id for item_id in ids if re.fullmatch(r"[A-Za-z0-9_-]{1,80}", item_id)]
            if not ids:
                row["error"] = {"class": "no_title_candidate_on_first_page"}
                continue
            detail, elapsed = client.cms(api_url(source["api"], ac="detail", ids=",".join(ids)))
            details = [item for item in detail["list"] if isinstance(item, dict)]
            details = [item for item in details if not allowed_categories or item.get("type_name") in allowed_categories]
            row["detail_elapsed_ms"] = elapsed
            row["matches"] = [metadata(item, case) for item in details[:3]]
            matched = next((item for item in details if metadata(item, case)["metadata_match"]), None)
            if matched is None:
                row["error"] = {"class": "metadata_mismatch_or_unknown"}
            elif include_hls and not hls_attempted:
                hls_attempted = True
                try:
                    row["hls"] = probe_hls(client, matched, case)
                except ProbeError as error:
                    row["hls"] = {"error": error.evidence(), "playback_decoded": False}
        except ProbeError as error:
            row["error"] = error.evidence()
            if error.kind == "budget_exhausted":
                break
    return result


def self_test():
    assert title_parts("老友记 第一季") == title_parts("老友记第1季")
    assert title_parts("西游记1986")[0] == title_parts("西游记")[0]
    assert title_parts("西游记（1986）")[0] == title_parts("西游记")[0]
    assert title_parts("2012")[0] == "2012"
    case = {"title": "老友记第一季", "year": 1994, "season": 1}
    item = {"vod_name": "老友记 第1季", "vod_year": "1994", "type_name": "欧美剧",
            "vod_lang": "中文字幕", "vod_sub": "中文字幕"}
    assert metadata(item, case)["metadata_match"]
    assert metadata(item, case)["subtitle_status"] == "unknown"
    assert not metadata(dict(item, vod_year="1995"), case)["metadata_match"]
    assert not metadata(dict(item, vod_name="老友记第二季"), case)["metadata_match"]
    assert metadata(dict(item, vod_remarks="HD中字"), case)["subtitle_status"] == "source_claimed_chinese"
    assert not metadata(dict(item, type_name="伦理片"), case)["metadata_match"]
    assert not metadata(dict(item, type_name="福利"), case)["metadata_match"]
    chinese_url = "https://例子.测试/视频/第01集.m3u8?标题=中文&sig=a%2Fb%3D&n=1+2"
    encoded_url = checked_url(chinese_url)
    assert encoded_url.startswith("https://xn--fsqu00a.xn--0zwm56d/")
    assert "%E7%AC%AC01%E9%9B%86.m3u8" in encoded_url
    assert "%E6%A0%87%E9%A2%98=%E4%B8%AD%E6%96%87&sig=a%2Fb%3D&n=1+2" in encoded_url
    assert checked_url(encoded_url) == encoded_url and "%252F" not in encoded_url
    assert checked_url("https://[2001:0db8::1]:8443/第01集.m3u8?x=%2F") == (
        "https://[2001:db8::1]:8443/%E7%AC%AC01%E9%9B%86.m3u8?x=%2F")
    assert checked_url("https://example.com/a%2Fb.m3u8?x=%2B+%20&next=/a?b=c") == (
        "https://example.com/a%2Fb.m3u8?x=%2B+%20&next=/a?b=c")
    for url in ("file:///a.m3u8", "javascript:alert(1)", "https://user:pass@example.com/a",
                "https://bad host.example/a.m3u8", "https://[2001:::1]/a.m3u8",
                "https://example.com:99999/a.m3u8", "https://example.com/" + chr(0xD800)):
        try:
            checked_url(url)
        except ProbeError:
            continue
        raise AssertionError("禁止的 URL 未拒绝")
    summary, _ = hls_summary({"body": b"#EXTM3U\n#EXTINF:3,\nsecret.ts?token=redacted\n", "truncated": False})
    assert summary["segment_entries_seen"] == 1 and "token" not in json.dumps(summary)
    assert "https://" not in safe_text("地址 https://example.com/?token=hidden")
    assert "hidden" not in safe_text("token=hidden")
    client = Client(1, 1, 1)
    client.reserve_request()
    try:
        client.reserve_request()
    except ProbeError as error:
        assert error.kind == "budget_exhausted"
    else:
        raise AssertionError("请求预算未生效")

    class FailingOpener:
        def __init__(self, error):
            self.error = error
            self.seen_url = None

        def open(self, request, timeout):
            self.seen_url = request.full_url
            raise self.error

    for error, expected_kind in (
            (urllib.error.URLError("offline"), "network"),
            (InvalidURL("invalid fixture URL"), "unsupported_url"),
            (UnicodeEncodeError("ascii", "第", 0, 1, "fixture"), "unsupported_url")):
        fake_client = Client(1, 4, 5)
        fake_client.opener = FailingOpener(error)
        try:
            fake_client.fetch(chinese_url, 128, manifest=True)
        except ProbeError as caught:
            assert caught.kind == expected_kind
        else:
            raise AssertionError("网络／URL 异常未分类")
        assert fake_client.opener.seen_url == encoded_url

    redirect_client = Client(1, 4, 5)
    redirected = Redirects(redirect_client).redirect_request(
        urllib.request.Request("https://example.com/start"), None, 302, "Found", {}, chinese_url)
    assert redirected.full_url == encoded_url and redirect_client.requests == 1

    class OfflineEvidenceClient:
        def __init__(self):
            self.calls = 0

        def cms(self, url):
            self.calls += 1
            if self.calls > 2:
                raise ProbeError("network")
            fixture = dict(item, vod_id=1, vod_play_from="m3u8",
                           vod_play_url="第01集$https://example.com/第01集.m3u8?token=hidden")
            return {"list": [fixture]}, 0

        def fetch(self, url, limit, manifest=False):
            raise ProbeError("network")

    evidence = probe_source(OfflineEvidenceClient(),
                            {"key": "offline_fixture", "api": "https://example.com/api"},
                            [case, {"title": "流浪地球", "year": 2019}], True)
    assert evidence["cases"][0]["matches"][0]["metadata_match"]
    assert evidence["cases"][0]["hls"]["error"]["class"] == "network"
    assert evidence["cases"][1]["error"]["class"] == "network"
    serialized = json.dumps({"sources": [evidence]}, ensure_ascii=False)
    assert "老友记" in serialized and "network" in serialized and "token" not in serialized
    print(json.dumps({"self_test": "pass", "network_requests": 0}, ensure_ascii=False))


def bounded(low, high):
    def parse(value):
        number_value = int(value)
        if not low <= number_value <= high:
            raise argparse.ArgumentTypeError("参数须在 %s 到 %s 之间" % (low, high))
        return number_value
    return parse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=ROOT / "sources/catalog.json")
    parser.add_argument("--cases-file", type=Path, help="最多 6 项的 JSON 数组；字段 title/year/season/episode/query/category")
    parser.add_argument("--output", type=Path, help="脱敏 JSON 证据输出文件；探测时必填")
    parser.add_argument("--hls", action="store_true", help="每个来源最多探测一个匹配版本的主／子清单，不取分片")
    parser.add_argument("--timeout", type=bounded(1, 20), default=8)
    parser.add_argument("--max-requests", type=bounded(1, 60), default=48)
    parser.add_argument("--max-seconds", type=bounded(1, 180), default=90)
    parser.add_argument("--max-sources", type=bounded(1, 5), default=3)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.output is None:
        parser.error("探测必须指定 --output")
    try:
        catalog = json.loads(args.catalog.read_text(encoding="utf-8-sig"))
        cases = json.loads(args.cases_file.read_text(encoding="utf-8-sig")) if args.cases_file else DEFAULT_CASES
        if not isinstance(cases, list) or not 1 <= len(cases) <= 6:
            raise ValueError("用例必须为 1 至 6 项")
        for case in cases:
            if not isinstance(case, dict) or not isinstance(case.get("title"), str) \
                    or not case["title"].strip() or len(case["title"]) > 128:
                raise ValueError("每个用例必须有 title")
            for field in ("year", "season", "episode"):
                if field in case and (not isinstance(case[field], int) or isinstance(case[field], bool) or case[field] < 1):
                    raise ValueError("年份、季、集须为正整数")
            if "query" in case and (not isinstance(case["query"], str)
                    or not case["query"].strip() or len(case["query"]) > 128):
                raise ValueError("query 须为文本")
        if not isinstance(catalog, dict) or not isinstance(catalog.get("sites"), list):
            raise ValueError("目录须包含 sites 数组")
        sources = [site for site in catalog["sites"] if isinstance(site, dict)
                   and site.get("type") == 1 and not isinstance(site.get("type"), bool)
                   and str(site.get("searchable", 1)).lower() not in ("0", "false")]
        sources = sources[:args.max_sources]
        for source in sources:
            if not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", source.get("key", "")):
                raise ValueError("来源 key 格式无效")
            source["api"] = checked_url(source["api"])
            if "categories" in source and (not isinstance(source["categories"], list)
                    or not all(isinstance(category, str) for category in source["categories"])):
                raise ValueError("分类须为文本数组")
        protected = {Path(__file__).resolve(), args.catalog.resolve()}
        if args.cases_file:
            protected.add(args.cases_file.resolve())
        if args.output.suffix.lower() != ".json" or args.output.resolve() in protected:
            raise ValueError("输出文件不能覆盖脚本或输入文件")
    except (OSError, ValueError, TypeError, KeyError, ProbeError):
        parser.error("输入文件、用例、来源地址或输出路径无效；未发起网络请求")
    client = Client(args.timeout, args.max_requests, args.max_seconds)
    report = {
        "schema_version": 1, "checked_at_utc": datetime.now(timezone.utc).isoformat(),
        "scope": "metadata_and_optional_hls_manifests_only", "playback_decoded": False,
        "playback_urls_omitted": True, "subtitle_confirmation": "not_verified",
        "limits": {"timeout_seconds": args.timeout, "max_requests": args.max_requests,
                   "max_seconds": args.max_seconds, "manifest_sample_bytes": 16 * 1024},
        "sources": [probe_source(client, source, cases, args.hls) for source in sources],
        "limitations": ["只查搜索第一页", "HLS 清单可读不代表可播放", "未下载影片分片",
                        "未验证硬字幕或流内字幕", "未验证内容授权、持续性或雷鸟真机"],
    }
    report["request_attempts"] = client.requests
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"evidence_written": True, "source_count": len(sources),
                      "request_attempts": client.requests, "playback_decoded": False}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    sys.exit(main())
