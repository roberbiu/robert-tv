package com.github.tvbox.osc.server;

/** 网络路由按最小允许集判断，旧处理器和静态资源不能作为默认兜底。 */
public final class RemoteRoutePolicy {
    public enum Route { PAGE, INPUT, DENY }
    private RemoteRoutePolicy() { }
    public static Route route(String method, String path) {
        if ("GET".equals(method) && "/".equals(path)) return Route.PAGE;
        if ("POST".equals(method) && "/input".equals(path)) return Route.INPUT;
        return Route.DENY;
    }
}
