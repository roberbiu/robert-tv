package com.github.tvbox.osc.source;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

import okhttp3.Dns;

/** 外部来源的请求边界；本机媒体租约入口和手机配对不使用这条上游规则。 */
public final class PublicNetworkPolicy {
    private PublicNetworkPolicy() { }

    public static URI requirePublicUrl(String url, boolean allowHttp) {
        try {
            if (url == null || url.length() > 8192 || hasControls(url)) throw new IllegalArgumentException();
            URI uri = new URI(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || (allowHttp && "http".equals(scheme)))
                    || host.isEmpty() || host.endsWith(".") || host.length() > 253
                    || host.equals("localhost") || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal")
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            if (host.matches("[0-9.]+") || host.contains(":")) {
                requirePublicAddress(InetAddress.getByName(host));
            }
            return uri;
        } catch (URISyntaxException | UnknownHostException | IllegalArgumentException error) {
            throw new IllegalArgumentException("上游目标未准入");
        }
    }

    /** 必须在连接所用DNS中验证每个结果，避免验证和实际连接分别解析。 */
    public static Dns publicDns(Dns delegate) {
        return hostname -> {
            if (hostname == null || hostname.equalsIgnoreCase("localhost") || hostname.endsWith(".")) {
                throw new UnknownHostException("上游地址未准入");
            }
            List<InetAddress> addresses = delegate.lookup(hostname);
            if (addresses == null || addresses.isEmpty()) throw new UnknownHostException("上游地址未准入");
            for (InetAddress address : addresses) requirePublicAddress(address);
            return addresses;
        };
    }

    public static void requirePublicAddress(InetAddress address) throws UnknownHostException {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            throw new UnknownHostException("上游地址未准入");
        }
        byte[] bytes = address.getAddress();
        if ((bytes.length == 4 && ((bytes[0] & 255) == 0 || (bytes[0] & 255) >= 224))
                || (bytes.length == 4 && (bytes[0] & 255) == 100 && (bytes[1] & 192) == 64)
                || (bytes.length == 16 && (bytes[0] & 254) == 252)) {
            throw new UnknownHostException("上游地址未准入");
        }
    }

    private static boolean hasControls(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }
}
