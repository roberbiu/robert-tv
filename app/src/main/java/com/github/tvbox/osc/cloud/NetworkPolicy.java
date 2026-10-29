package com.github.tvbox.osc.cloud;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import com.github.tvbox.osc.net.PublicAddressPolicy;
import com.github.tvbox.osc.net.RecoveringDns;

/** 上游网络仅访问公网；解析和连接使用同一DNS约束，阻止私网/本机探测。 */
public final class NetworkPolicy {
    private NetworkPolicy() { }

    public static HttpUrl requireUrl(String value, Set<String> hosts, boolean httpsOnly) throws CloudException {
        if (value == null || value.length() > 32768) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        HttpUrl url = HttpUrl.parse(value == null ? "" : value);
        if (url == null || (!"https".equals(url.scheme()) && (httpsOnly || !"http".equals(url.scheme())))
                || !url.username().isEmpty() || !url.password().isEmpty() || url.fragment() != null
                || url.host().endsWith(".") || url.host().equals("localhost") || url.host().endsWith(".localhost")
                || url.host().endsWith(".local") || url.host().endsWith(".internal")
                || (hosts != null && !hosts.contains(url.host())))
            throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        try { PublicAddressPolicy.requirePublicHost(url.host()); }
        catch (UnknownHostException ignored) { throw new CloudException(CloudException.Code.INVALID_RESPONSE, null); }
        return url;
    }

    public static Dns publicDns() {
        return RecoveringDns.system();
    }

    public static boolean isPublic(InetAddress address) {
        return PublicAddressPolicy.isPublic(address);
    }
}
