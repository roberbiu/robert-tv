package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.cloud.CloudReference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只恢复最新详情仍认可的同一分享文件，不把历史临时播放地址当作新详情。 */
public final class CloudHistoryRestore {
    private static final int MAX_PAGE_ITEMS = 101; // 云盘每页100项，另加显式目录入口。
    private static final String CHOOSE_DIRECTORY = "选择其他文件 / 目录 / 页";

    private CloudHistoryRestore() { }

    /**
     * 先按历史偏好处理fresh的逆序，再调用本方法；成功后不再整体reverse。
     * 历史页按用户上次看到的顺序恢复，当前集重新按fileId定位；失败不改fresh。
     */
    public static boolean restore(VodInfo fresh, VodInfo history) {
        try {
            if (fresh == null || history == null || fresh == history
                    || !sameNonempty(fresh.id, history.id) || !sameNonempty(fresh.sourceKey, history.sourceKey)
                    || fresh.seriesMap == null || history.seriesMap == null || history.playFlag == null) return false;
            List<VodInfo.VodSeries> previous = history.seriesMap.get(history.playFlag);
            if (previous == null || previous.size() > MAX_PAGE_ITEMS || history.playIndex < 0
                    || history.playIndex >= previous.size()) return false;
            VodInfo.VodSeries pastEpisode = previous.get(history.playIndex);
            CloudReference selected = decode(pastEpisode);
            if (selected == null || !selected.isFile() || !fresh.sourceKey.equals(selected.sourceId)) return false;

            String latestFlag = null;
            CloudReference latestShare = null;
            for (Map.Entry<String, List<VodInfo.VodSeries>> line : fresh.seriesMap.entrySet()) {
                if (line.getValue() == null || line.getKey() == null) continue;
                for (VodInfo.VodSeries episode : line.getValue()) {
                    CloudReference ref = decode(episode);
                    if (ref != null && !ref.isFile() && sameShare(ref, selected)
                            && fresh.sourceKey.equals(ref.sourceId)) {
                        latestFlag = line.getKey(); latestShare = ref; break;
                    }
                }
                if (latestShare != null) break;
            }
            if (latestShare == null) return false;

            List<VodInfo.VodSeries> restored = new ArrayList<>();
            Set<String> files = new HashSet<>();
            int selectedIndex = -1;
            for (VodInfo.VodSeries episode : previous) {
                CloudReference old = decode(episode);
                if (old == null || !old.isFile() || !sameShare(old, selected)
                        || !selected.folderId.equals(old.folderId) || old.page != selected.page) continue;
                if (!files.add(old.fileId)) return false;
                CloudReference safe = rebuild(latestShare, old.folderId, old.fileId, old.fileName, old.page, old.quality);
                String title = old.fileName.isEmpty() ? episode.name : old.fileName;
                if (title == null || title.isEmpty() || title.length() > 512) title = "未命名视频";
                VodInfo.VodSeries restoredEpisode = new VodInfo.VodSeries(title, safe.encode());
                restoredEpisode.selected = selected.fileId.equals(old.fileId);
                if (restoredEpisode.selected) selectedIndex = restored.size();
                restored.add(restoredEpisode);
            }
            if (selectedIndex < 0) return false;
            CloudReference folder = rebuild(latestShare, selected.folderId, "", "", selected.page, "auto");
            restored.add(new VodInfo.VodSeries(CHOOSE_DIRECTORY, folder.encode()));

            LinkedHashMap<String, List<VodInfo.VodSeries>> lines = new LinkedHashMap<>(fresh.seriesMap);
            lines.put(latestFlag, restored);
            ArrayList<VodInfo.VodSeriesFlag> flags = new ArrayList<>();
            for (String name : lines.keySet()) {
                VodInfo.VodSeriesFlag flag = new VodInfo.VodSeriesFlag(name);
                flag.selected = name.equals(latestFlag);
                flags.add(flag);
            }
            // 完成身份、文件与列表验证后一次提交，不修改历史对象或新详情的其他线路。
            fresh.seriesMap = lines;
            fresh.seriesFlags = flags;
            fresh.playFlag = latestFlag;
            fresh.playIndex = selectedIndex;
            fresh.playNote = restored.get(selectedIndex).name;
            return true;
        } catch (CloudException | RuntimeException invalidHistory) { return false; }
    }

    private static CloudReference decode(VodInfo.VodSeries episode) {
        if (episode == null || episode.url == null || !episode.url.startsWith(CloudReference.PREFIX)) return null;
        try { return CloudReference.decode(episode.url); } catch (CloudException invalid) { return null; }
    }

    private static boolean sameShare(CloudReference left, CloudReference right) {
        return sameNonempty(left.provider, right.provider) && sameNonempty(left.shareId, right.shareId)
                && sameNonempty(left.sourceId, right.sourceId) && sameNonempty(left.postId, right.postId);
    }

    private static boolean sameNonempty(String left, String right) {
        return left != null && !left.isEmpty() && left.equals(right);
    }

    private static CloudReference rebuild(CloudReference latest, String folder, String file, String name, int page, String quality)
            throws CloudException {
        CloudReference safe = new CloudReference();
        safe.provider = latest.provider; safe.shareId = latest.shareId; safe.passcode = latest.passcode;
        safe.sourceId = latest.sourceId; safe.postId = latest.postId;
        safe.folderId = folder; safe.fileId = file; safe.fileName = name; safe.page = page; safe.quality = quality;
        // 使用协议校验后的显式字段，未知旧JSON字段不会回流到新引用。
        return CloudReference.decode(safe.encode());
    }
}
