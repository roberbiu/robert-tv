package com.github.tvbox.osc.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** 固定官方HTTPS DNS数据协议；bootstrap不递归调用系统或恢复DNS。 */
final class AliDnsHttpsResolver implements RecoveringDns.Fallback {
    static final String HOST = "dns.alidns.com";
    static final int MAX_BYTES = 64 * 1024;
    interface Calls { Call create(Request request, long remainingMillis); }
    private final Calls calls;
    private static final OkHttpClient BASE = new OkHttpClient.Builder().dns(bootstrap()).proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(1, TimeUnit.SECONDS).build();

    AliDnsHttpsResolver() { this((request, remaining) -> client(remaining).newCall(request)); }
    AliDnsHttpsResolver(Calls calls) { this.calls = calls; }
    static OkHttpClient client(long remaining) {
        return BASE.newBuilder().callTimeout(Math.max(1, Math.min(remaining, 3000)), TimeUnit.MILLISECONDS).build();
    }
    static Dns bootstrap() {
        return host -> {
            if (!HOST.equals(host)) throw PublicAddressPolicy.denied();
            return Arrays.asList(InetAddress.getByAddress(new byte[]{(byte)223, 5, 5, 5}),
                    InetAddress.getByAddress(new byte[]{(byte)223, 6, 6, 6}));
        };
    }

    @Override public List<InetAddress> lookup(String hostname, long deadline, RecoveringDns.Cancellation cancellation) throws IOException {
        String host = PublicAddressPolicy.publicHostname(hostname);
        List<InetAddress> result = query(host, 1, deadline, cancellation);
        if (result.isEmpty()) result = query(host, 28, deadline, cancellation);
        return PublicAddressPolicy.requirePublicAnswers(result);
    }

    private List<InetAddress> query(String host, int type, long deadline, RecoveringDns.Cancellation cancellation) throws IOException {
        cancellation.check();
        HttpUrl endpoint = new HttpUrl.Builder().scheme("https").host(HOST).addPathSegment("resolve")
                .addQueryParameter("name", host).addQueryParameter("type", type == 1 ? "A" : "AAAA").build();
        Call call = calls.create(new Request.Builder().url(endpoint).header("Accept", "application/dns-json,application/json").build(), RecoveringDns.remaining(deadline));
        Runnable cancel = call::cancel;
        cancellation.attach(cancel);
        try (Response response = call.execute()) {
            cancellation.check();
            if (response.code() != 200 || response.body() == null || response.body().contentLength() > MAX_BYTES) throw PublicAddressPolicy.denied();
            byte[] bytes = readBounded(response.body().byteStream(), deadline, cancellation);
            return parse(bytes, host, type);
        } finally { cancellation.detach(cancel); }
    }

    static byte[] readBounded(InputStream input, long deadline, RecoveringDns.Cancellation cancellation) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            cancellation.check(); RecoveringDns.remaining(deadline);
            if (output.size() + count > MAX_BYTES) throw PublicAddressPolicy.denied();
            output.write(buffer, 0, count);
        }
        RecoveringDns.remaining(deadline);
        return output.toByteArray();
    }

    static List<InetAddress> parse(byte[] bytes, String host, int type) throws UnknownHostException {
        if (bytes.length > MAX_BYTES) throw PublicAddressPolicy.denied();
        try {
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (integer(root, "Status") != 0 || (root.has("TC") && (!root.get("TC").isJsonPrimitive()
                    || !root.getAsJsonPrimitive("TC").isBoolean() || root.get("TC").getAsBoolean()))) throw PublicAddressPolicy.denied();
            JsonElement questions = root.get("Question");
            JsonObject question;
            if (questions != null && questions.isJsonObject()) question = questions.getAsJsonObject();
            else if (questions != null && questions.isJsonArray() && questions.getAsJsonArray().size() == 1)
                question = questions.getAsJsonArray().get(0).getAsJsonObject();
            else throw PublicAddressPolicy.denied();
            if (!host.equals(dnsName(question.get("name").getAsString())) || integer(question, "type") != type) throw PublicAddressPolicy.denied();
            if (!root.has("Answer")) return new ArrayList<>();
            JsonArray answers = root.getAsJsonArray("Answer");
            if (answers == null || answers.size() > 64) throw PublicAddressPolicy.denied();
            Map<String, String> aliases = new HashMap<>();
            Map<String, List<InetAddress>> addresses = new HashMap<>();
            for (JsonElement row : answers) {
                JsonObject answer = row.getAsJsonObject();
                String name = dnsName(answer.get("name").getAsString());
                int recordType = integer(answer, "type");
                String data = answer.get("data").getAsString();
                if (recordType == 5) {
                    String alias = dnsName(data);
                    if (aliases.put(name, alias) != null) throw PublicAddressPolicy.denied();
                } else {
                    if (recordType != type) throw PublicAddressPolicy.denied();
                    InetAddress address = PublicAddressPolicy.literal(data);
                    PublicAddressPolicy.requirePublic(address);
                    if (address.getAddress().length != (type == 1 ? 4 : 16)) throw PublicAddressPolicy.denied();
                    List<InetAddress> list = addresses.get(name);
                    if (list == null) { list = new ArrayList<>(); addresses.put(name, list); }
                    list.add(address);
                }
            }
            Set<String> seen = new HashSet<>(); String current = host;
            while (seen.add(current)) {
                String next = aliases.get(current);
                if (next == null) {
                    if (!seen.containsAll(addresses.keySet()) || !seen.containsAll(aliases.keySet())) throw PublicAddressPolicy.denied();
                    return addresses.containsKey(current) ? PublicAddressPolicy.requirePublicAnswers(addresses.get(current)) : new ArrayList<>();
                }
                if (addresses.containsKey(current)) throw PublicAddressPolicy.denied();
                current = next;
            }
            throw PublicAddressPolicy.denied();
        } catch (RuntimeException invalid) { throw PublicAddressPolicy.denied(); }
    }

    private static String dnsName(String name) throws UnknownHostException {
        return PublicAddressPolicy.publicHostname(name.endsWith(".") ? name.substring(0, name.length() - 1) : name);
    }
    private static int integer(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
        return new java.math.BigDecimal(value.getAsString()).intValueExact();
    }
}
