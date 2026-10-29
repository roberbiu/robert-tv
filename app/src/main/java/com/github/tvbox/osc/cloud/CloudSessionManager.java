package com.github.tvbox.osc.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 授权epoch与凭据轮换revision分离，晚到响应不能注销或覆盖新登录。 */
public final class CloudSessionManager {
    public static final class Snapshot {
        public final CloudProvider provider;
        public final String accountId;
        public final long authEpoch;
        public final long credentialRevision;
        public final long updatedAt;
        private final transient String cookie;

        Snapshot(CloudProvider provider, String accountId, long epoch, long revision, long updatedAt, String cookie) {
            this.provider = provider;
            this.accountId = accountId;
            this.authEpoch = epoch;
            this.credentialRevision = revision;
            this.updatedAt = updatedAt;
            this.cookie = cookie;
        }

        public boolean isAuthorized() { return cookie != null && !cookie.isEmpty() && !accountId.isEmpty(); }
        String credential() { return cookie; }
        @Override public String toString() { return "CloudSession(" + provider.id + ",redacted)"; }
    }

    public static final class Authorization {
        public final CloudProvider provider;
        public final long serial;
        public final long expectedEpoch;
        Authorization(CloudProvider provider, long serial, long epoch) {
            this.provider = provider;
            this.serial = serial;
            this.expectedEpoch = epoch;
        }
    }

    private final SecretStore store;
    private final Map<CloudProvider, Snapshot> sessions = new ConcurrentHashMap<>();
    private final Map<CloudProvider, Long> authorizations = new EnumMap<>(CloudProvider.class);
    private final Map<CloudProvider, Boolean> storageErrors = new ConcurrentHashMap<>();

    public CloudSessionManager(SecretStore store) {
        this.store = store;
        for (CloudProvider provider : CloudProvider.values()) {
            Snapshot snapshot = empty(provider, 0);
            try {
                String value = store.read("session_" + provider.id);
                if (value != null) {
                    JsonObject data = JsonParser.parseString(value).getAsJsonObject();
                    if (data.get("schema").getAsInt() != 1 || !provider.id.equals(data.get("provider").getAsString())) throw new IllegalArgumentException();
                    Snapshot candidate = new Snapshot(provider, text(data, "account"), data.get("epoch").getAsLong(),
                            data.get("revision").getAsLong(), data.get("updatedAt").getAsLong(), text(data, "cookie"));
                    if (candidate.authEpoch < 0 || candidate.credentialRevision < 0 || !safeCookie(candidate.cookie)
                            || (!candidate.accountId.isEmpty() && !candidate.accountId.matches("[a-zA-Z0-9_-]{8,128}"))) throw new IllegalArgumentException();
                    snapshot = candidate;
                }
            } catch (Exception ignored) { storageErrors.put(provider, true); }
            sessions.put(provider, snapshot);
            authorizations.put(provider, 0L);
        }
    }

    private static Snapshot empty(CloudProvider provider, long epoch) {
        return new Snapshot(provider, "", epoch, 0, System.currentTimeMillis(), "");
    }

    public Snapshot snapshot(CloudProvider provider) { return sessions.get(provider); }
    public boolean hasSession(CloudProvider provider) { return !Boolean.TRUE.equals(storageErrors.get(provider)) && snapshot(provider).isAuthorized(); }
    public boolean matchesSession(Snapshot expected) {
        Snapshot current = snapshot(expected.provider);
        return !Boolean.TRUE.equals(storageErrors.get(expected.provider)) && current.isAuthorized()
                && current.authEpoch == expected.authEpoch && current.accountId.equals(expected.accountId);
    }

    public Snapshot require(CloudProvider provider) throws CloudException {
        if (Boolean.TRUE.equals(storageErrors.get(provider))) throw new CloudException(CloudException.Code.PRIVATE_STORAGE, provider);
        Snapshot snapshot = snapshot(provider);
        if (!snapshot.isAuthorized()) throw new CloudException(CloudException.Code.AUTH_REQUIRED, provider);
        return snapshot;
    }

    public synchronized Authorization beginAuthorization(CloudProvider provider) {
        long serial = authorizations.get(provider) + 1;
        authorizations.put(provider, serial);
        return new Authorization(provider, serial, snapshot(provider).authEpoch);
    }

    public synchronized void cancel(Authorization authorization) {
        if (authorizations.get(authorization.provider) == authorization.serial)
            authorizations.put(authorization.provider, authorization.serial + 1);
    }

    public synchronized boolean complete(Authorization authorization, String accountId, String cookie) throws CloudException {
        CloudProvider provider = authorization.provider;
        Snapshot current = snapshot(provider);
        if (authorizations.get(provider) != authorization.serial || current.authEpoch != authorization.expectedEpoch) return false;
        if (accountId == null || !accountId.matches("[a-zA-Z0-9_-]{8,128}") || cookie == null || cookie.isEmpty() || !safeCookie(cookie))
            throw new CloudException(CloudException.Code.INVALID_RESPONSE, provider);
        Snapshot next = new Snapshot(provider, accountId, current.authEpoch + 1, 0, System.currentTimeMillis(), cookie);
        save(next);
        sessions.put(provider, next);
        authorizations.put(provider, authorization.serial + 1);
        storageErrors.put(provider, false);
        return true;
    }

    synchronized boolean rotate(Snapshot expected, String cookie) throws CloudException {
        Snapshot current = snapshot(expected.provider);
        if (current.authEpoch != expected.authEpoch || current.credentialRevision != expected.credentialRevision || !current.isAuthorized()) return false;
        if (cookie.equals(current.cookie)) return true;
        if (!safeCookie(cookie)) throw new CloudException(CloudException.Code.INVALID_RESPONSE, expected.provider);
        Snapshot next = new Snapshot(current.provider, current.accountId, current.authEpoch, current.credentialRevision + 1, System.currentTimeMillis(), cookie);
        save(next);
        sessions.put(current.provider, next);
        return true;
    }

    public synchronized boolean expireIfCurrent(Snapshot expected) throws CloudException {
        Snapshot current = snapshot(expected.provider);
        if (current.authEpoch != expected.authEpoch || current.credentialRevision != expected.credentialRevision) return false;
        clear(current.provider);
        return true;
    }

    public synchronized void logout(CloudProvider provider) throws CloudException { clear(provider); }

    private void clear(CloudProvider provider) throws CloudException {
        Snapshot next = empty(provider, snapshot(provider).authEpoch + 1);
        sessions.put(provider, next);
        authorizations.put(provider, authorizations.get(provider) + 1);
        try { save(next); storageErrors.put(provider, false); }
        catch (CloudException failure) { storageErrors.put(provider, true); throw failure; }
    }

    private void save(Snapshot value) throws CloudException {
        JsonObject data = new JsonObject();
        data.addProperty("schema", 1);
        data.addProperty("provider", value.provider.id);
        data.addProperty("account", value.accountId);
        data.addProperty("epoch", value.authEpoch);
        data.addProperty("revision", value.credentialRevision);
        data.addProperty("updatedAt", value.updatedAt);
        data.addProperty("cookie", value.cookie);
        try { store.write("session_" + value.provider.id, data.toString()); }
        catch (Exception ignored) { throw new CloudException(CloudException.Code.PRIVATE_STORAGE, value.provider); }
    }

    private static String text(JsonObject data, String name) { return data.has(name) && !data.get(name).isJsonNull() ? data.get(name).getAsString() : ""; }

    private static boolean safeCookie(String cookie) {
        if (cookie == null || cookie.length() > 24576) return false;
        for (int i = 0; i < cookie.length(); i++) if (cookie.charAt(i) < 32 || cookie.charAt(i) > 126) return false;
        return true;
    }
}
