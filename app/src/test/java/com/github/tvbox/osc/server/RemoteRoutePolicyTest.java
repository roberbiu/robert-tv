package com.github.tvbox.osc.server;

import org.junit.Test;
import static org.junit.Assert.*;

public class RemoteRoutePolicyTest {
    @Test public void onlyPairingPageAndSearchPostReachAuthentication() {
        assertEquals(RemoteRoutePolicy.Route.PAGE, RemoteRoutePolicy.route("GET", "/"));
        assertEquals(RemoteRoutePolicy.Route.INPUT, RemoteRoutePolicy.route("POST", "/input"));
        assertEquals(RemoteRoutePolicy.Route.DENY, RemoteRoutePolicy.route("POST", "/"));
        assertEquals(RemoteRoutePolicy.Route.DENY, RemoteRoutePolicy.route("GET", "/input"));
        assertEquals(RemoteRoutePolicy.Route.DENY, RemoteRoutePolicy.route("OPTIONS", "/input"));
    }

    @Test public void legacyFileConfigurationAndBareMediaRoutesHaveNoFallback() {
        String[] paths = {"/file", "/file/test.txt", "/upload", "/newFile", "/delFolder", "/delFile",
                "/cache", "/action", "/push", "/api", "/input/", "/media", "/proxy", "/proxyM3u8",
                "/dash", "/live", "/config", "/index.html", "/../", "/%2e%2e/", "/favicon.ico"};
        for (String path : paths) for (String method : new String[]{"GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS"}) {
            assertEquals(method + " " + path, RemoteRoutePolicy.Route.DENY, RemoteRoutePolicy.route(method, path));
        }
    }
}
