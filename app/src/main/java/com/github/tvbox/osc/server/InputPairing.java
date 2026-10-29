package com.github.tvbox.osc.server;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/** 仅授权短时搜索输入；与网盘会话、文件和媒体权限无关。 */
public final class InputPairing {
    public static final long TTL_MILLIS = 5 * 60 * 1000L;
    public static final int MAX_TEXT_LENGTH = 160;
    public static final int MAX_BODY_BYTES = 2048;
    public interface Clock { long nowMillis(); }
    interface TokenSource { String next(); }
    private final Clock clock;
    private final TokenSource tokens;
    private String token = "";
    private String origin = "";
    private String authority = "";
    private long expiresAt;
    private long rateWindow;
    private int submissions;

    public InputPairing(Clock clock) { this(clock, InputPairing::randomToken); }
    InputPairing(Clock clock, TokenSource tokens) { this.clock = clock; this.tokens = tokens; }

    public synchronized String open(String origin) {
        try {
            URI uri = new URI(origin);
            if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                return "";
            }
            this.origin = "http://" + uri.getRawAuthority().toLowerCase(Locale.ROOT);
            authority = uri.getRawAuthority().toLowerCase(Locale.ROOT);
            token = tokens.next();
            if (!token.matches("[a-f0-9]{48}")) throw new IllegalStateException("配对能力格式错误");
            expiresAt = clock.nowMillis() + TTL_MILLIS;
            rateWindow = clock.nowMillis();
            submissions = 0;
            return this.origin + "/?pair=" + token;
        } catch (java.net.URISyntaxException error) { return ""; }
    }

    public synchronized void close(String address) {
        String supplied = tokenFromAddress(address);
        if (!matches(supplied)) return;
        token = "";
        origin = "";
        authority = "";
        expiresAt = 0;
    }
    public synchronized void closeAll() {
        token = "";
        origin = "";
        authority = "";
        expiresAt = 0;
    }
    public synchronized boolean allowPage(String host, String suppliedToken) {
        return active() && validHost(host) && matches(suppliedToken);
    }
    public synchronized boolean allowInput(String host, String requestOrigin, String suppliedToken,
                                            long contentLength, String contentType, boolean chunked) {
        if (!active() || !validHost(host) || !origin.equals(requestOrigin)
                || !matches(suppliedToken) || chunked || contentLength < 1 || contentLength > MAX_BODY_BYTES
                || contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded")) {
            return false;
        }
        long now = clock.nowMillis();
        if (now - rateWindow >= 60_000L) { rateWindow = now; submissions = 0; }
        if (submissions >= 20) return false;
        submissions++;
        return true;
    }
    private boolean active() { return !token.isEmpty() && clock.nowMillis() < expiresAt; }
    private boolean validHost(String host) {
        return host != null && authority.equals(host.toLowerCase(Locale.ROOT));
    }
    private boolean matches(String supplied) {
        return supplied != null && supplied.matches("[a-f0-9]{48}") && !token.isEmpty()
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }
    static String tokenFromAddress(String address) {
        if (address == null) return "";
        try {
            String query = new URI(address).getRawQuery();
            return query != null && query.matches("pair=[a-f0-9]{48}") ? query.substring(5) : "";
        } catch (java.net.URISyntaxException ignored) { return ""; }
    }
    public static String normalizeText(String text) {
        if (text == null) return "";
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isISOControl(ch) && !Character.isWhitespace(ch)) return "";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.length() <= MAX_TEXT_LENGTH ? normalized : "";
    }
    private static String randomToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder token = new StringBuilder(48);
        for (byte value : bytes) token.append(String.format(Locale.ROOT, "%02x", value & 255));
        return token.toString();
    }
}
