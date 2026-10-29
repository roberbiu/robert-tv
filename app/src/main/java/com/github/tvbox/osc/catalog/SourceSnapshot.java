package com.github.tvbox.osc.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次请求持有整个快照，目录更新不修改已在运行的来源描述。 */
public final class SourceSnapshot {
    private final long revision;
    private final Map<String, SourceDescriptor> all;
    private final List<SourceDescriptor> enabled;

    public SourceSnapshot(long revision, List<SourceDescriptor> sources) {
        this.revision = revision;
        Map<String, SourceDescriptor> index = new LinkedHashMap<>();
        List<SourceDescriptor> active = new ArrayList<>();
        for (SourceDescriptor source : sources) {
            if (index.put(source.getId(), source) != null) throw new IllegalArgumentException("来源标识重复");
            if (source.isEnabled()) active.add(source);
        }
        Collections.sort(active, (left, right) -> Integer.compare(right.getPriority(), left.getPriority()));
        all = Collections.unmodifiableMap(index);
        enabled = Collections.unmodifiableList(active);
    }

    public static SourceSnapshot empty() { return new SourceSnapshot(0, Collections.emptyList()); }
    public long getRevision() { return revision; }
    public List<SourceDescriptor> getSources() { return enabled; }
    public List<SourceDescriptor> getAllSources() { return Collections.unmodifiableList(new ArrayList<>(all.values())); }
    public SourceDescriptor getSource(String id) { return all.get(id); }
}
