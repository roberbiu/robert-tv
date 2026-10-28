package com.github.tvbox.osc.catalog;

import com.github.tvbox.osc.util.ContentPolicy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 默认目录只描述普通 CMS 数据接口，不携带可执行适配代码。 */
public final class CatalogPolicy {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_SOURCES = 16;
    private static final int MAX_CATEGORIES = 32;
    private static final Set<String> ROOT_FIELDS = new HashSet<>(Arrays.asList(
            "version", "name", "updatedAt", "description", "sites"));
    private static final Set<String> SOURCE_FIELDS = new HashSet<>(Arrays.asList(
            "key", "name", "type", "api", "searchable", "quickSearch", "filterable",
            "changeable", "playerType", "timeout", "authorization", "categories", "enabled"));

    private CatalogPolicy() {
    }

    /** 校验后重建字段白名单，禁止第三方订阅借目录更新带入脚本或解析器。 */
    public static String validate(String content) {
        if (content == null || content.length() > MAX_BYTES
                || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid("目录内容为空或过大");
        }
        try {
            JsonElement document = JsonParser.parseString(content);
            if (!document.isJsonObject()) throw invalid("目录必须是 JSON 对象");
            JsonObject input = document.getAsJsonObject();
            checkFields(input, ROOT_FIELDS);
            int version = integer(input, "version", -1);
            if (version < 1) throw invalid("目录版本无效");
            if (!input.has("sites") || !input.get("sites").isJsonArray()) {
                throw invalid("目录缺少站点列表");
            }
            JsonArray inputSites = input.getAsJsonArray("sites");
            if (inputSites.size() == 0 || inputSites.size() > MAX_SOURCES) {
                throw invalid("站点数量无效");
            }

            JsonObject result = new JsonObject();
            result.addProperty("version", version);
            result.addProperty("name", text(input, "name", "Robert影视来源目录", 48));
            copyText(input, result, "updatedAt", 40);
            copyText(input, result, "description", 512);
            JsonArray sites = new JsonArray();
            Set<String> keys = new HashSet<>();
            Set<String> endpoints = new HashSet<>();
            boolean searchable = false;
            for (JsonElement element : inputSites) {
                if (!element.isJsonObject()) throw invalid("站点格式无效");
                JsonObject source = element.getAsJsonObject();
                checkFields(source, SOURCE_FIELDS);
                JsonObject site = normalizeSource(source);
                if (source.has("enabled")) {
                    if (!source.get("enabled").isJsonPrimitive()
                            || !source.getAsJsonPrimitive("enabled").isBoolean()) {
                        throw invalid("启停状态无效");
                    }
                    if (!source.get("enabled").getAsBoolean()) continue;
                }
                if (!keys.add(site.get("key").getAsString())
                        || !endpoints.add(site.get("api").getAsString().replaceAll("/$", ""))) {
                    throw invalid("站点键或接口重复");
                }
                searchable |= site.get("searchable").getAsInt() == 1;
                sites.add(site);
            }
            if (sites.size() == 0 || !searchable) throw invalid("目录没有可搜索站点");
            result.add("sites", sites);
            return result.toString();
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalid("目录 JSON 格式无效");
        }
    }

    /** 优先较新的有效本地版本，同版本使用内置目录，避免旧缓存盖住 APK 修复。 */
    public static String chooseLocalCatalog(String cached, String bundled) {
        String validCached = validOrNull(cached);
        String validBundled = validOrNull(bundled);
        if (validCached == null && validBundled == null) throw invalid("没有可用的本地目录");
        if (validCached == null) return validBundled;
        if (validBundled == null) return validCached;
        return version(validCached) > version(validBundled) ? validCached : validBundled;
    }

    /** 远端修改必须递增版本；同版本不同内容以及回滚版本均不能覆盖本机配置。 */
    public static boolean isNewerVersion(String candidate, String current) {
        return version(validate(candidate)) > version(validate(current));
    }

    private static String validOrNull(String content) {
        if (content == null) return null;
        try {
            return validate(content);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int version(String validated) {
        return JsonParser.parseString(validated).getAsJsonObject().get("version").getAsInt();
    }

    private static JsonObject normalizeSource(JsonObject source) {
        String key = text(source, "key", "", 48);
        if (!key.matches("[a-zA-Z0-9_-]{1,48}")) throw invalid("站点键无效");
        if (integer(source, "type", -1) != 1) throw invalid("仅允许普通 JSON CMS 站点");
        int player = integer(source, "playerType", 2);
        if (player != 2) throw invalid("默认目录仅使用内置播放器");
        String authorization = text(source, "authorization", "unknown", 16);
        if (!Arrays.asList("none", "required", "unknown").contains(authorization)) {
            throw invalid("授权状态无效");
        }
        if (!source.has("categories") || !source.get("categories").isJsonArray()) {
            throw invalid("站点缺少分类白名单");
        }
        JsonArray inputCategories = source.getAsJsonArray("categories");
        if (inputCategories.size() == 0 || inputCategories.size() > MAX_CATEGORIES) {
            throw invalid("分类数量无效");
        }
        JsonArray categories = new JsonArray();
        Set<String> uniqueCategories = new HashSet<>();
        for (JsonElement category : inputCategories) {
            if (!category.isJsonPrimitive() || !category.getAsJsonPrimitive().isString()) {
                throw invalid("分类名称无效");
            }
            String name = category.getAsString().trim();
            if (name.isEmpty() || name.length() > 32 || hasControlCharacter(name)
                    || !ContentPolicy.isAllowedCategory(name)) {
                throw invalid("目录包含不允许的分类");
            }
            if (uniqueCategories.add(name)) categories.add(name);
        }

        JsonObject result = new JsonObject();
        result.addProperty("key", key);
        result.addProperty("name", text(source, "name", key, 48));
        result.addProperty("type", 1);
        result.addProperty("api", cmsEndpoint(text(source, "api", "", 512)));
        result.addProperty("searchable", flag(source, "searchable", 1));
        result.addProperty("quickSearch", flag(source, "quickSearch", 1));
        result.addProperty("filterable", flag(source, "filterable", 1));
        result.addProperty("changeable", flag(source, "changeable", 1));
        result.addProperty("playerType", 2);
        int timeout = integer(source, "timeout", 15);
        if (timeout < 5 || timeout > 30) throw invalid("站点超时范围无效");
        result.addProperty("timeout", timeout);
        result.addProperty("authorization", authorization);
        result.add("categories", categories);
        return result;
    }

    private static String cmsEndpoint(String address) {
        try {
            URI uri = new URI(address).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath();
            if (!("https".equals(scheme) || "http".equals(scheme))
                    || host.isEmpty() || !host.contains(".") || host.contains(":")
                    || host.matches("[0-9.]+") || host.endsWith(".localhost")
                    || host.endsWith(".local") || host.endsWith(".internal")
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getQuery() != null && !uri.getQuery().isEmpty())
                    || uri.getPort() > 65535 || uri.getPort() == 0
                    || !("/api.php/provide/vod".equals(path)
                    || "/api.php/provide/vod/".equals(path))) {
                throw invalid("仅允许不含凭据的公开 CMS 接口地址");
            }
            return new URI(scheme, null, host, uri.getPort(), path, null, null).toASCIIString();
        } catch (URISyntaxException error) {
            throw invalid("接口地址无效");
        }
    }

    private static int flag(JsonObject object, String key, int fallback) {
        int value = integer(object, key, fallback);
        if (value != 0 && value != 1) throw invalid("开关值无效");
        return value;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid("数字字段无效");
        }
        try {
            BigDecimal number = value.getAsBigDecimal();
            return number.intValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw invalid("数字超出范围");
        }
    }

    private static String text(JsonObject object, String key, String fallback, int limit) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid("文字字段无效");
        }
        String result = value.getAsString().trim();
        if (result.isEmpty() || result.length() > limit || hasControlCharacter(result)) {
            throw invalid("文字字段为空或过长");
        }
        return result;
    }

    private static void copyText(JsonObject input, JsonObject output, String key, int limit) {
        if (input.has(key)) output.addProperty(key, text(input, key, "", limit));
    }

    private static void checkFields(JsonObject object, Set<String> allowed) {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) throw invalid("目录包含不允许的字段");
        }
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
