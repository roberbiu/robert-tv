package com.github.tvbox.osc.server;

import java.util.Map;
import fi.iki.elonen.NanoHTTPD;

/** 旧通用入口停用；即使被误注册也不得读取私有状态或修改配置。 */
public final class CacheRequestProcess implements RequestProcess {
    @Override public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) { return false; }
    @Override public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName,
                                                   Map<String, String> params, Map<String, String> files) {
        return RemoteServer.createPlainTextResponse(NanoHTTPD.Response.Status.FORBIDDEN, "Not available");
    }
}
