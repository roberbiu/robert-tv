package com.github.tvbox.osc.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 所有自管上游使用同一公网准入规则；手机LAN和本机媒体服务不使用此规则。 */
public final class PublicAddressPolicy {
    private PublicAddressPolicy() { }

    public static boolean isPublic(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] raw = address.getAddress();
        int a = raw[0] & 255, b = raw[1] & 255;
        if (raw.length == 16) {
            int c = raw[2] & 255, d = raw[3] & 255;
            // 仅全球单播；排除文档、6to4、Teredo及特殊协议分配区，拒绝映射/NAT64/ULA。
            return (a & 224) == 32 && !(a == 32 && b == 2)
                    && !(a == 32 && b == 1 && ((c == 13 && d == 184) || c <= 1))
                    && !(a == 63 && b == 255 && (c & 240) == 0);
        }
        if (raw.length != 4) return false;
        int c = raw[2] & 255;
        return a != 0 && a != 10 && a != 127 && a < 224
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && (b == 168 || b == 0 || (b == 88 && c == 99)))
                && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                && !(a == 203 && b == 0 && c == 113);
    }

    public static void requirePublic(InetAddress address) throws UnknownHostException {
        if (!isPublic(address)) throw denied();
    }

    public static List<InetAddress> requirePublicAnswers(List<InetAddress> addresses) throws UnknownHostException {
        if (addresses == null || addresses.isEmpty() || addresses.size() > 32) throw denied();
        List<InetAddress> checked = new ArrayList<>();
        for (InetAddress address : addresses) { requirePublic(address); if (!checked.contains(address)) checked.add(address); }
        return Collections.unmodifiableList(checked);
    }

    public static boolean isLiteral(String host) {
        return host != null && (host.indexOf(':') >= 0 || host.startsWith("[") || host.matches("[0-9.]+"));
    }

    /** 仅解析确定的数值地址，绝不将服务端Answer.data交给递归域名解析。 */
    public static InetAddress literal(String value) throws UnknownHostException {
        if (value == null) throw denied();
        String host = value;
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.indexOf(':') >= 0) {
            if (!host.matches("[0-9a-fA-F:.]{2,64}")) throw denied();
            InetAddress address = InetAddress.getByName(host); // ':'及仅十六进制字符保证是数值IPv6，不走hostname DNS。
            if (address.getAddress().length != 16) throw denied(); // 不接受被JVM折叠为IPv4的映射地址。
            return address;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) throw denied();
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw denied();
            int number = Integer.parseInt(parts[i]);
            if (number > 255) throw denied();
            bytes[i] = (byte) number;
        }
        return InetAddress.getByAddress(bytes);
    }

    /** 返回可外送的规范公开域名，拒绝本地主机、数值IP和特殊用途后缀。 */
    public static String publicHostname(String value) throws UnknownHostException {
        if (value == null || value.length() > 253 || isLiteral(value)) throw denied();
        String host = value.toLowerCase(Locale.ROOT);
        if (!host.contains(".") || host.endsWith(".")) throw denied();
        for (String suffix : new String[]{"localhost", "local", "internal", "lan", "home", "arpa", "onion", "test", "invalid", "example", "alt"}) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) throw denied();
        }
        for (String part : host.split("\\.", -1)) {
            if (!part.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw denied();
        }
        if (host.substring(host.lastIndexOf('.') + 1).matches("[0-9]+")) throw denied();
        return host;
    }

    public static void requirePublicHost(String host) throws UnknownHostException {
        if (isLiteral(host)) requirePublic(literal(host)); else publicHostname(host);
    }

    static UnknownHostException denied() { return new UnknownHostException("上游地址未准入"); }
}
