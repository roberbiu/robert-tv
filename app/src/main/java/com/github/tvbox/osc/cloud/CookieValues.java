package com.github.tvbox.osc.cloud;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CookieValues {
    private CookieValues() { }

    static String merge(String current, List<String> setCookies) throws CloudException {
        if (current != null && (current.indexOf('\r') >= 0 || current.indexOf('\n') >= 0)) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        Map<String, String> values = parse(current);
        if (setCookies != null) for (String header : setCookies) {
            if (header == null || header.indexOf('\r') >= 0 || header.indexOf('\n') >= 0) continue;
            String[] pair = header.split(";", 2)[0].split("=", 2);
            if (pair.length != 2 || !pair[0].trim().matches("[A-Za-z0-9_~-]{1,80}")) continue;
            if (pair[1].isEmpty() || header.toLowerCase(java.util.Locale.ROOT).contains("max-age=0")) values.remove(pair[0].trim());
            else values.put(pair[0].trim(), pair[1]);
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (result.length() > 0) result.append("; ");
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        if (result.length() > 24576) throw new CloudException(CloudException.Code.INVALID_RESPONSE, null);
        return result.toString();
    }

    static Map<String, String> parse(String cookie) {
        Map<String, String> result = new LinkedHashMap<>();
        if (cookie == null || cookie.indexOf('\r') >= 0 || cookie.indexOf('\n') >= 0) return result;
        for (String item : cookie.split(";")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length == 2 && pair[0].matches("[A-Za-z0-9_~-]{1,80}")) result.put(pair[0], pair[1]);
        }
        return result;
    }
}
