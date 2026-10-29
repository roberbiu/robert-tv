package com.github.tvbox.osc.catalog;

import com.github.tvbox.osc.bean.SourceBean;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 校验后的来源数据，不持有账号；旧模型每次按防御副本交给兼容层。 */
public final class SourceDescriptor {
    private final String id;
    private final String name;
    private final String adapterId;
    private final int configRevision;
    private final String origin;
    private final boolean enabled;
    private final String api;
    private final List<String> sites;
    private final List<String> categories;
    private final String authorization;
    private final String countryFilterMode;
    private final String qualityStatus;
    private final int priority;
    private final int timeout;

    public SourceDescriptor(String id, String name, String adapterId, int configRevision,
                            String origin, boolean enabled, String api, List<String> sites,
                            List<String> categories, String authorization, String countryFilterMode,
                            String qualityStatus, int priority, int timeout) {
        this.id = id;
        this.name = name;
        this.adapterId = adapterId;
        this.configRevision = configRevision;
        this.origin = origin;
        this.enabled = enabled;
        this.api = api == null ? "" : api;
        this.sites = Collections.unmodifiableList(new ArrayList<>(sites));
        this.categories = Collections.unmodifiableList(new ArrayList<>(categories));
        this.authorization = authorization;
        this.countryFilterMode = countryFilterMode;
        this.qualityStatus = qualityStatus;
        this.priority = priority;
        this.timeout = timeout;
    }

    public String getId() { return id; }
    public String getSourceId() { return id; }
    public String getName() { return name; }
    public String getAdapterId() { return adapterId; }
    public int getConfigRevision() { return configRevision; }
    public String getOrigin() { return origin; }
    public boolean isEnabled() { return enabled; }
    public String getApi() { return api; }
    public List<String> getSites() { return sites; }
    public String getPrimaryEndpoint() { return sites.isEmpty() ? api : sites.get(0); }
    public List<String> getAlternateEndpoints() {
        return sites.size() < 2 ? Collections.emptyList() : sites.subList(1, sites.size());
    }
    public List<String> getCategories() { return categories; }
    public String getAuthorization() { return authorization; }
    public String getCountryFilterMode() { return countryFilterMode; }
    public boolean supportsServerCountryFilter() { return "verified_server".equals(countryFilterMode); }
    public String getQualityStatus() { return qualityStatus; }
    public int getPriority() { return priority; }
    public int getTimeoutSeconds() { return timeout; }

    public SourceBean getSourceBean() {
        SourceBean bean = new SourceBean();
        bean.setKey(id);
        bean.setName(name);
        bean.setAdapterId(adapterId);
        bean.setConfigRevision(configRevision);
        bean.setCountryFilterMode(countryFilterMode);
        bean.setQualityStatus(qualityStatus);
        bean.setPriority(priority);
        bean.setType("cms".equals(adapterId) ? 1 : 3);
        bean.setApi("cms".equals(adapterId) ? api : "builtin:" + adapterId);
        bean.setSearchable(enabled ? 1 : 0);
        bean.setQuickSearch(enabled ? 1 : 0);
        bean.setChangeable(enabled ? 1 : 0);
        bean.setFilterable(1);
        bean.setPlayerType(2);
        bean.setTimeout(timeout);
        bean.setAuthorization(authorization);
        bean.setCategories(new ArrayList<>(categories));
        if (!"cms".equals(adapterId)) {
            JsonObject ext = new JsonObject();
            JsonArray endpoints = new JsonArray();
            for (String site : sites) endpoints.add(site);
            ext.add("sites", endpoints);
            ext.addProperty("sourceId", id);
            ext.addProperty("configRevision", configRevision);
            bean.setExt(ext.toString());
        }
        return bean;
    }
}
