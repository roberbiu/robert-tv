package com.github.tvbox.osc.browse;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 国家按完整字段匹配，避免把欧美或美国萨摩亚误归入美国。 */
public final class CountryMatcher {
    private static final Map<String, Set<String>> ALIASES = new HashMap<>();

    static {
        add("美国", "美国", "美國", "usa", "us", "u.s.a.", "united states", "united states of america");
        add("英国", "英国", "英國", "uk", "u.k.", "united kingdom", "great britain");
        add("日本", "日本", "japan", "jp");
        add("韩国", "韩国", "韓國", "south korea", "republic of korea", "kr");
    }

    private CountryMatcher() {
    }

    private static void add(String canonical, String... aliases) {
        ALIASES.put(canonical, new HashSet<>(Arrays.asList(aliases)));
    }

    public static boolean matches(String area, String country) {
        if (country == null || country.isEmpty()) return true;
        if (area == null || area.trim().isEmpty()) return false;
        Set<String> expected = ALIASES.get(country);
        if (expected == null) return false;
        String normalized = Normalizer.normalize(area, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+(?:and|&amp;)\\s+", "/");
        for (String token : normalized.split("[,，、/|;；&]+")) {
            if (expected.contains(token.trim())) return true;
        }
        return false;
    }
}
