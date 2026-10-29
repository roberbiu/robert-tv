package com.github.tvbox.osc.cloud;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;

import okhttp3.Dns;
import okhttp3.HttpUrl;

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
        if (url.host().matches("[0-9.]+") || url.host().contains(":")) try {
            if (!isPublic(InetAddress.getByName(url.host()))) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        } catch (UnknownHostException ignored) { throw new CloudException(CloudException.Code.INVALID_RESPONSE, null); }
        return url;
    }

    public static Dns publicDns() {
        return hostname -> {
            List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
            for (InetAddress address : addresses) if (!isPublic(address)) throw new UnknownHostException("上游地址不在允许范围");
            return addresses;
        };
    }

    public static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255, d = bytes[3] & 255;
            // 当前仅允许全球单播空间；排除文档、Teredo及6to4隧道地址。
            return (a & 224) == 32 && !(a == 32 && b == 2)
                    && !(a == 32 && b == 1 && ((c == 13 && d == 184) || (c == 0 && d == 0)));
        }
        int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
        return a != 0 && a != 10 && a != 127 && a < 224
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && (b == 168 || b == 0))
                && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                && !(a == 203 && b == 0 && c == 113);
    }
}
