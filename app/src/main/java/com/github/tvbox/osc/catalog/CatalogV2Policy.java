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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** v2只分发数据和APK内适配器标识，外部执行文件及全局配置没有准入字段。 */
public final class CatalogV2Policy {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_SOURCES = 16;
    public static final String CMS = "cms";
    public static final String SELECTED = "selected";
    private static final Set<String> ROOT_FIELDS = fields("schemaVersion", "name", "updatedAt", "profiles");
    private static final Set<String> PROFILE_FIELDS = fields("id", "version", "sources");
    private static final Set<String> SOURCE_FIELDS = fields("id", "name", "adapterId", "configRevision",
            "enabled", "api", "sites", "categories", "authorization", "countryFilterMode",
            "qualityStatus", "priority", "timeout");

    private CatalogV2Policy() { }

    /** 拆分后由存储层逐profile验证；一个坏站点目录不能清空另一个有效目录。 */
    public static Map<String, String> splitBundle(String content) {
        JsonObject root = object(content);
        checkFields(root, ROOT_FIELDS);
        if (integer(root, "schemaVersion", -1) != 2) throw invalid("目录格式版本不支持");
        JsonArray profiles = array(root, "profiles");
        if (profiles.size() < 1 || profiles.size() > 2) throw invalid("目录分组无效");
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonElement value : profiles) {
            if (!value.isJsonObject()) throw invalid("目录分组格式无效");
            JsonObject profile = value.getAsJsonObject();
            String id = text(profile, "id", "", 20);
            requireProfileId(id);
            if (result.put(id, profile.toString()) != null) throw invalid("目录分组重复");
        }
        return Collections.unmodifiableMap(result);
    }

    public static String validateProfile(String content, String expectedId) {
        requireProfileId(expectedId);
        JsonObject input = object(content);
        checkFields(input, PROFILE_FIELDS);
        if (!expectedId.equals(text(input, "id", "", 20))) throw invalid("目录分组不匹配");
        int version = integer(input, "version", -1);
        if (version < 1) throw invalid("目录版本无效");
        JsonArray sources = array(input, "sources");
        if (sources.size() > MAX_SOURCES || (CMS.equals(expectedId) && sources.size() == 0)) {
            throw invalid("来源数量无效");
        }
        JsonArray normalized = new JsonArray();
        Set<String> ids = new HashSet<>();
        Set<String> endpoints = new HashSet<>();
        boolean anonymousCms = false;
        for (JsonElement value : sources) {
            if (!value.isJsonObject()) throw invalid("来源格式无效");
            JsonObject source = normalizeSource(value.getAsJsonObject(), expectedId, version);
            if (!ids.add(source.get("id").getAsString())) throw invalid("来源标识重复");
            String endpointKey = "cms".equals(source.get("adapterId").getAsString())
                    ? source.get("api").getAsString().replaceAll("/$", "")
                    : "builtin:" + source.get("adapterId").getAsString();
            if (!endpoints.add(endpointKey)) throw invalid("相同来源重复收录");
            if (CMS.equals(expectedId) && source.get("enabled").getAsBoolean()
                    && "none".equals(source.get("authorization").getAsString())) anonymousCms = true;
            normalized.add(source);
        }
        if (CMS.equals(expectedId) && !anonymousCms) throw invalid("没有可用的免认证后备描述");
        JsonObject result = new JsonObject();
        result.addProperty("id", expectedId);
        result.addProperty("version", version);
        result.add("sources", normalized);
        return result.toString();
    }

    private static JsonObject normalizeSource(JsonObject input, String profile, int profileVersion) {
        checkFields(input, SOURCE_FIELDS);
        String id = text(input, "id", "", 48);
        if (!id.matches("[a-zA-Z0-9_-]{1,48}")) throw invalid("来源标识无效");
        String adapter = text(input, "adapterId", "", 20);
        if (CMS.equals(profile) ? !"cms".equals(adapter)
                : !("wogg".equals(adapter) || "dm84".equals(adapter))) {
            throw invalid("本地适配器未准入");
        }
        if (SELECTED.equals(profile) && !id.startsWith("fty_")) throw invalid("精选来源标识未分仓");
        if (CMS.equals(profile) && id.startsWith("fty_")) throw invalid("来源标识分组冲突");
        int revision = integer(input, "configRevision", profileVersion);
        int priority = integer(input, "priority", "cms".equals(adapter) ? 20 : 40);
        int timeout = integer(input, "timeout", 15);
        if (revision < 1 || priority < 0 || priority > 100 || timeout < 5 || timeout > 30) {
            throw invalid("来源版本或预算无效");
        }
        boolean enabled = bool(input, "enabled", true);
        String authorization = text(input, "authorization", "unknown", 16);
        if (!fields("none", "required", "unknown").contains(authorization)) throw invalid("授权类型无效");
        String country = text(input, "countryFilterMode", "none", 20);
        if (!fields("none", "verified_server").contains(country)) throw invalid("地区能力无效");
        if ("verified_server".equals(country)
                && !("cms".equals(adapter) && ("robert_liangzi".equals(id) || "robert_feifan".equals(id)))) {
            throw invalid("来源地区能力未经验证");
        }
        String quality = text(input, "qualityStatus", "unverified", 24);
        if (!fields("unverified", "sample_verified", "observed_ads").contains(quality)) {
            throw invalid("质量状态无效");
        }
        JsonArray categories = array(input, "categories");
        if (categories.size() < 1 || categories.size() > 32) throw invalid("分类数量无效");
        JsonArray cleanCategories = new JsonArray();
        Set<String> categoryNames = new HashSet<>();
        for (JsonElement value : categories) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid("分类字段无效");
            String name = value.getAsString().trim();
            if (name.isEmpty() || name.length() > 32 || controls(name) || !ContentPolicy.isAllowedCategory(name)) {
                throw invalid("分类未准入");
            }
            if (categoryNames.add(name)) cleanCategories.add(name);
        }
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("name", text(input, "name", id, 48));
        result.addProperty("adapterId", adapter);
        result.addProperty("configRevision", revision);
        result.addProperty("enabled", enabled);
        result.addProperty("authorization", authorization);
        result.addProperty("countryFilterMode", country);
        result.addProperty("qualityStatus", quality);
        result.addProperty("priority", priority);
        result.addProperty("timeout", timeout);
        result.add("categories", cleanCategories);
        if ("cms".equals(adapter)) {
            if (input.has("sites")) throw invalid("CMS不可附带站点解析参数");
            // 复用已验证的v1 CMS端点约束，保持旧客户端数据格式独立。
            JsonObject legacySite = new JsonObject();
            legacySite.addProperty("key", id);
            legacySite.addProperty("name", result.get("name").getAsString());
            legacySite.addProperty("type", 1);
            legacySite.addProperty("api", text(input, "api", "", 512));
            legacySite.addProperty("authorization", authorization);
            legacySite.add("categories", cleanCategories);
            JsonArray legacySites = new JsonArray();
            legacySites.add(legacySite);
            JsonObject legacy = new JsonObject();
            legacy.addProperty("version", 1);
            legacy.add("sites", legacySites);
            String api = JsonParser.parseString(CatalogPolicy.validate(legacy.toString())).getAsJsonObject()
                    .getAsJsonArray("sites").get(0).getAsJsonObject().get("api").getAsString();
            result.addProperty("api", api);
        } else {
            if (input.has("api")) throw invalid("本地适配器不可携带任意接口");
            JsonArray sites = array(input, "sites");
            if (sites.size() < 1 || sites.size() > 5) throw invalid("站点主备数量无效");
            JsonArray cleanSites = new JsonArray();
            Set<String> unique = new HashSet<>();
            for (JsonElement value : sites) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid("站点地址无效");
                String endpoint = nativeEndpoint(value.getAsString());
                if (unique.add(endpoint)) cleanSites.add(endpoint);
            }
            result.add("sites", cleanSites);
        }
        return result;
    }

    /** 站点主备列表来自本项目维护目录；只允许HTTPS公开域名根地址。 */
    private static String nativeEndpoint(String value) {
        try {
            if (value == null || value.length() > 512 || controls(value)) throw invalid("站点地址无效");
            URI uri = new URI(value.trim()).normalize();
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host.isEmpty() || !host.contains(".")
                    || host.length() > 253 || host.endsWith(".") || host.matches("[0-9.]+") || host.contains(":") || host.endsWith(".local")
                    || host.endsWith(".localhost") || host.endsWith(".internal")
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPort() != -1 || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw invalid("站点目标未准入");
            }
            return new URI("https", null, host, -1, "/", null, null).toASCIIString();
        } catch (URISyntaxException error) { throw invalid("站点地址无效"); }
    }

    public static List<SourceDescriptor> descriptors(String canonical) {
        JsonObject profile = object(canonical);
        String id = text(profile, "id", "", 20);
        JsonObject checked = object(validateProfile(canonical, id));
        List<SourceDescriptor> result = new ArrayList<>();
        for (JsonElement value : checked.getAsJsonArray("sources")) {
            JsonObject source = value.getAsJsonObject();
            result.add(new SourceDescriptor(source.get("id").getAsString(), source.get("name").getAsString(),
                    source.get("adapterId").getAsString(), source.get("configRevision").getAsInt(), id,
                    source.get("enabled").getAsBoolean(), source.has("api") ? source.get("api").getAsString() : "",
                    strings(source, "sites"), strings(source, "categories"), source.get("authorization").getAsString(),
                    source.get("countryFilterMode").getAsString(), source.get("qualityStatus").getAsString(),
                    source.get("priority").getAsInt(), source.get("timeout").getAsInt()));
        }
        return result;
    }

    public static SourceSnapshot merge(long revision, String cms, String selected) {
        List<SourceDescriptor> all = new ArrayList<>(descriptors(validateProfile(cms, CMS)));
        all.addAll(descriptors(validateProfile(selected, SELECTED)));
        Set<String> endpoints = new HashSet<>();
        int anonymousCms = 0;
        int activeCount = 0;
        for (SourceDescriptor source : all) {
            String endpoint = "cms".equals(source.getAdapterId())
                    ? source.getApi().replaceAll("/$", "") : "builtin:" + source.getAdapterId();
            if (!endpoints.add(endpoint)) throw invalid("来源重复");
            if (source.isEnabled()) activeCount++;
            if (source.isEnabled() && "cms".equals(source.getAdapterId())
                    && "none".equals(source.getAuthorization())) anonymousCms++;
        }
        if (activeCount > MAX_SOURCES) throw invalid("合并启用来源超出预算");
        if (anonymousCms == 0) throw invalid("没有可用的免认证后备描述");
        return new SourceSnapshot(revision, all);
    }

    public static int version(String canonical) { return integer(object(canonical), "version", -1); }
    public static String chooseLocalProfile(String id, String bundled, String cached, String previous) {
        requireProfileId(id);
        String asset = validOrNull(bundled, id);
        String local = validOrNull(cached, id);
        String backup = validOrNull(previous, id);
        if (local == null || (backup != null && version(backup) > version(local) && compatibleUpdate(local, backup))) {
            local = backup;
        }
        if (local == null && asset == null) throw invalid("没有可用的来源目录");
        if (local == null) return asset;
        if (asset != null && version(asset) >= version(local) && compatibleUpdate(local, asset)) return asset;
        return local;
    }
    private static String validOrNull(String value, String id) {
        if (value == null) return null;
        try { return validateProfile(value, id); } catch (IllegalArgumentException invalid) { return null; }
    }
    public static boolean preservesIds(String oldProfile, String newProfile) {
        Set<String> newIds = new HashSet<>();
        for (SourceDescriptor source : descriptors(newProfile)) newIds.add(source.getId());
        for (SourceDescriptor source : descriptors(oldProfile)) if (!newIds.contains(source.getId())) return false;
        return true;
    }
    /** 输入须为已校验的规范目录；更换配置必须推进来源代际，避免旧实例套用新地址。 */
    public static boolean compatibleUpdate(String oldProfile, String newProfile) {
        if (!preservesIds(oldProfile, newProfile)) return false;
        Map<String, JsonObject> next = new LinkedHashMap<>();
        for (JsonElement value : object(newProfile).getAsJsonArray("sources")) {
            JsonObject source = value.getAsJsonObject();
            next.put(source.get("id").getAsString(), source);
        }
        for (JsonElement value : object(oldProfile).getAsJsonArray("sources")) {
            JsonObject old = value.getAsJsonObject();
            JsonObject candidate = next.get(old.get("id").getAsString());
            if (!old.get("adapterId").equals(candidate.get("adapterId"))) return false;
            int oldRevision = old.get("configRevision").getAsInt();
            int newRevision = candidate.get("configRevision").getAsInt();
            if (newRevision < oldRevision || (!old.equals(candidate) && newRevision == oldRevision)) return false;
        }
        return true;
    }
    public static String emptySelected() { return "{\"id\":\"selected\",\"version\":1,\"sources\":[]}"; }

    private static List<String> strings(JsonObject object, String key) {
        List<String> values = new ArrayList<>();
        if (object.has(key)) for (JsonElement value : object.getAsJsonArray(key)) values.add(value.getAsString());
        return values;
    }
    private static JsonObject object(String content) {
        if (content == null || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid("目录响应过大或缺失");
        try {
            JsonElement value = JsonParser.parseString(content);
            if (!value.isJsonObject()) throw invalid("目录必须为JSON对象");
            return value.getAsJsonObject();
        } catch (RuntimeException error) { throw invalid("目录格式无效"); }
    }
    private static Set<String> fields(String... names) { return new HashSet<>(Arrays.asList(names)); }
    private static void checkFields(JsonObject value, Set<String> names) {
        for (String key : value.keySet()) if (!names.contains(key)) throw invalid("目录字段未准入");
    }
    private static void requireProfileId(String id) {
        if (!(CMS.equals(id) || SELECTED.equals(id))) throw invalid("目录分组未准入");
    }
    private static JsonArray array(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) throw invalid("目录数组无效");
        return object.getAsJsonArray(key);
    }
    private static int integer(JsonObject object, String key, int fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw invalid("目录整数无效");
        try { return new BigDecimal(value.getAsString()).intValueExact(); }
        catch (RuntimeException error) { throw invalid("目录整数越界"); }
    }
    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (!object.has(key)) return fallback;
        if (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isBoolean()) throw invalid("目录开关无效");
        return object.get(key).getAsBoolean();
    }
    private static String text(JsonObject object, String key, String fallback, int limit) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw invalid("目录文本无效");
        String text = value.getAsString().trim();
        if (text.isEmpty() || text.length() > limit || controls(text)) throw invalid("目录文本超出范围");
        return text;
    }
    private static boolean controls(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
