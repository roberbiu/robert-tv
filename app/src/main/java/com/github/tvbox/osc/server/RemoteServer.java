package com.github.tvbox.osc.server;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.SystemClock;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.event.ServerEvent;
import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/** 局域网仅开放短时配对搜索输入；媒体由独立本机租约服务处理。 */
public class RemoteServer extends NanoHTTPD {
    public static int serverPort = 9978;
    /** 仅保留旧代码编译兼容，不再通过HTTP导出此全局内容。 */
    @Deprecated public static String m3u8Content;
    private final Context context;
    private final InputPairing pairing;
    private volatile boolean started;
    private DataReceiver receiver;

    public RemoteServer(int port, Context context) {
        this(port, context, new InputPairing(SystemClock::elapsedRealtime));
    }

    RemoteServer(int port, Context context, InputPairing pairing) {
        super(port);
        this.context = context.getApplicationContext();
        this.pairing = pairing;
    }

    @Override public void start(int timeout, boolean daemon) throws IOException {
        super.start(timeout, daemon);
        started = true;
        EventBus.getDefault().post(new ServerEvent(ServerEvent.SERVER_SUCCESS));
    }

    @Override public void stop() {
        pairing.closeAll();
        super.stop();
        started = false;
    }

    @Override public Response serve(IHTTPSession session) {
        String path = session.getUri();
        Map<String, String> headers = session.getHeaders();
        RemoteRoutePolicy.Route route = RemoteRoutePolicy.route(session.getMethod().name(), path);
        if (route == RemoteRoutePolicy.Route.PAGE) {
            String token = session.getParms().get("pair");
            if (!pairing.allowPage(headers.get("host"), token)) return denied();
            return secure(newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                    inputPage(token)), token);
        }
        if (route == RemoteRoutePolicy.Route.INPUT) {
            long length = contentLength(headers.get("content-length"));
            boolean chunked = headers.containsKey("transfer-encoding");
            if (!pairing.allowInput(headers.get("host"), headers.get("origin"),
                    headers.get("x-input-token"), length, headers.get("content-type"), chunked)) {
                return denied();
            }
            try {
                session.parseBody(new HashMap<>());
                if (!pairing.allowPage(headers.get("host"), headers.get("x-input-token"))) return denied();
                String text = InputPairing.normalizeText(session.getParms().get("text"));
                if (text.isEmpty()) return secure(createJSONResponse(Response.Status.BAD_REQUEST, "{\"ok\":false}"), null);
                DataReceiver target = receiver;
                if (target == null) return secure(createJSONResponse(Response.Status.SERVICE_UNAVAILABLE, "{\"ok\":false}"), null);
                target.onTextReceived(text);
                EventBus.getDefault().post(new ServerEvent(ServerEvent.SERVER_CONNECTION));
                return secure(createJSONResponse(Response.Status.OK, "{\"ok\":true}"), null);
            } catch (IOException | ResponseException | RuntimeException error) {
                // 不将输入内容、异常消息或私有状态回显到网络。
                return secure(createJSONResponse(Response.Status.BAD_REQUEST, "{\"ok\":false}"), null);
            }
        }
        // 包含file/cache/action/media/proxy/dash在内的旧路由都没有默认兜底。
        return secure(createPlainTextResponse(Response.Status.NOT_FOUND, "Not found"), null);
    }

    private static long contentLength(String value) {
        if (value == null || !value.matches("[0-9]{1,6}")) return -1;
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return -1; }
    }

    private Response denied() {
        return secure(createPlainTextResponse(Response.Status.FORBIDDEN, "Pairing unavailable"), null);
    }

    private static Response secure(Response response, String nonce) {
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("Referrer-Policy", "no-referrer");
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("X-Frame-Options", "DENY");
        String scripts = nonce == null ? "'none'" : "'nonce-" + nonce + "'";
        response.addHeader("Content-Security-Policy", "default-src 'none'; script-src " + scripts
                + "; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        return response;
    }

    private String inputPage(String nonce) {
        String name = context.getString(R.string.app_name).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        // token仅来自已校验的本页地址，脚本无外链、不请求文件或配置接口。
        return "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + name + " · 手机输入</title>"
                + "<style>body{margin:0;background:#f6f7ef;color:#163b32;font:17px/1.7 system-ui,sans-serif}"
                + "main{max-width:540px;margin:8vh auto;padding:28px}h1{font-size:27px}textarea{box-sizing:border-box;width:100%;min-height:110px;padding:14px;font:inherit;border:1px solid #bdcbbd;border-radius:10px}"
                + "button{width:100%;margin-top:18px;padding:14px;background:#084640;color:#fff7e4;border:0;border-radius:10px;font:inherit}p{color:#526a5e}#status{min-height:30px}</style>"
                + "<main><h1>" + name + "</h1><p>输入片名后发送到电视。关闭电视上的扫码窗口后，此链接失效。</p>"
                + "<form id=\"form\" autocomplete=\"off\"><textarea id=\"word\" maxlength=\"160\" placeholder=\"请输入片名\" aria-label=\"片名\"></textarea>"
                + "<button type=\"submit\">发送到电视</button></form><p id=\"status\" role=\"status\"></p></main>"
                + "<script nonce=\"" + nonce + "\">(function(){var m=/[?&]pair=([a-f0-9]{48})(?:&|$)/.exec(location.search);var token=m?m[1]:'';"
                + "var form=document.getElementById('form'),status=document.getElementById('status');form.onsubmit=function(e){e.preventDefault();var word=document.getElementById('word').value.trim();"
                + "if(!word){status.textContent='请输入片名';return;}var xhr=new XMLHttpRequest();xhr.open('POST','/input');xhr.setRequestHeader('Content-Type','application/x-www-form-urlencoded;charset=UTF-8');"
                + "xhr.setRequestHeader('X-Input-Token',token);xhr.timeout=8000;xhr.onload=function(){status.textContent=xhr.status===200?'已发送到电视':xhr.status===403?'链接已失效，请在电视重新打开扫码':'未能发送，请检查片名后重试';};"
                + "xhr.onerror=xhr.ontimeout=function(){status.textContent='未能连接电视，请检查网络后重试';};xhr.send('text='+encodeURIComponent(word));};})();</script></html>";
    }

    public void setDataReceiver(DataReceiver receiver) { this.receiver = receiver; }
    public DataReceiver getDataReceiver() { return receiver; }
    public boolean isStarting() { return started; }
    public String getServerAddress() {
        String ip = getLocalIPAddress(context);
        return ip.isEmpty() ? "" : "http://" + ip + ":" + serverPort + "/";
    }
    public String getLoadAddress() { return "http://127.0.0.1:" + serverPort + "/"; }
    public static Response createPlainTextResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, "text/plain; charset=utf-8", text);
    }
    public static Response createJSONResponse(Response.IStatus status, String text) {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", text);
    }

    @SuppressLint("DefaultLocale")
    public static String getLocalIPAddress(Context context) {
        try {
            String fallback = "";
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface device = interfaces.nextElement();
                if (!device.isUp() || device.isLoopback()) continue;
                Enumeration<InetAddress> addresses = device.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || !address.isSiteLocalAddress()) continue;
                    if (device.getName().startsWith("wlan") || device.getName().startsWith("eth")) return address.getHostAddress();
                    if (!device.getName().startsWith("tun") && !device.getName().startsWith("wg")) fallback = address.getHostAddress();
                }
            }
            return fallback;
        } catch (IOException | SecurityException unavailable) { return ""; }
    }
}
