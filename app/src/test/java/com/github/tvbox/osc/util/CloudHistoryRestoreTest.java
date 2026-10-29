package com.github.tvbox.osc.util;

import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cloud.CloudReference;
import org.junit.Test;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import static org.junit.Assert.*;

public class CloudHistoryRestoreTest {
    @Test public void sameShareRestoresOnlySameFolderAndPageWithDirectoryEntry() throws Exception {
        VodInfo fresh = fresh("最新线路");
        List<VodInfo.VodSeries> otherLine = fresh.seriesMap.get("另一线路");
        VodInfo history = history("旧线路", file("file_1", "第1集", "folderA", 2), file("file_2", "第2集", "folderA", 2),
                file("other_folder", "不应恢复的目录", "folderB", 2), file("other_page", "不应恢复的页", "folderA", 3));
        history.playIndex = 1;
        assertTrue(CloudHistoryRestore.restore(fresh, history));
        assertEquals("最新线路", fresh.playFlag);
        assertEquals(1, fresh.playIndex);
        List<VodInfo.VodSeries> restored = fresh.seriesMap.get(fresh.playFlag);
        assertEquals(3, restored.size());
        assertEquals("file_2", decode(restored.get(fresh.playIndex)).fileId);
        CloudReference navigation = decode(restored.get(2));
        assertFalse(navigation.isFile());
        assertEquals("folderA", navigation.folderId);
        assertEquals(2, navigation.page);
        assertSame(otherLine, fresh.seriesMap.get("另一线路"));
        assertEquals(4, history.seriesMap.get("旧线路").size());
    }

    @Test public void providerSharePostAndSourceMismatchNeverMutateFresh() {
        for (String field : Arrays.asList("provider", "share", "post", "source", "vod")) {
            VodInfo fresh = fresh("最新线路");
            CloudReference old = file("file_1", "第1集", "folderA", 1);
            if (field.equals("provider")) old.provider = "uc";
            if (field.equals("share")) old.shareId = "other_share";
            if (field.equals("post")) old.postId = "another_post";
            if (field.equals("source")) old.sourceId = "other_source";
            VodInfo history = history("旧线路", old);
            if (field.equals("vod")) history.id = "different_vod";
            assertUnchanged(fresh, history);
        }
        VodInfo fresh = fresh("最新线路");
        VodInfo history = history("旧线路", file("file_1", "第1集", "folderA", 1));
        history.sourceKey = "other_source";
        assertUnchanged(fresh, history);
    }

    @Test public void missingItemInvalidIndexOrPlaceholderCannotBecomeResumeFile() {
        VodInfo negative = history("旧线路", file("file_1", "第1集", "folderA", 1));
        negative.playIndex = -1;
        assertUnchanged(fresh("新线路"), negative);
        VodInfo overflow = history("旧线路", file("file_1", "第1集", "folderA", 1));
        overflow.playIndex = 8;
        assertUnchanged(fresh("新线路"), overflow);
        VodInfo missing = history("旧线路", file("file_1", "第1集", "folderA", 1));
        missing.seriesMap.get("旧线路").set(0, null);
        assertUnchanged(fresh("新线路"), missing);
        VodInfo missingFlag = history("旧线路", file("file_1", "第1集", "folderA", 1));
        missingFlag.playFlag = "已不存在";
        assertUnchanged(fresh("新线路"), missingFlag);
        assertUnchanged(fresh("新线路"), history("旧线路", reference()));
    }

    @Test public void fileIdLocatesResumeAfterFilteringAndLatestFlagRename() throws Exception {
        VodInfo fresh = fresh("重命名的分享线路");
        VodInfo history = history("旧线路名", file("foreign_page", "其他页", "folderA", 2),
                file("file_2", "第2集", "folderA", 1), file("file_1", "第1集", "folderA", 1));
        history.playIndex = 2;
        history.reverseSort = true;
        fresh.reverseSort = true; // 宿主已在调用前处理新详情逆序，helper不再盲目reverse。
        assertTrue(CloudHistoryRestore.restore(fresh, history));
        assertEquals("重命名的分享线路", fresh.playFlag);
        assertEquals(1, fresh.playIndex);
        assertEquals("file_1", decode(fresh.seriesMap.get(fresh.playFlag).get(fresh.playIndex)).fileId);
        assertTrue(fresh.reverseSort);
        assertFalse(fresh.seriesMap.containsKey("旧线路名"));
    }

    @Test public void freshPasscodeReplacesHistoricalValueAndUnknownSecretsAreDropped() throws Exception {
        VodInfo fresh = fresh("新线路");
        CloudReference old = file("file_1", "第1集", "folderA", 1);
        old.passcode = "OLD1";
        old.quality = "high";
        VodInfo history = history("旧线路", old);
        String oldJson = URLDecoder.decode(history.seriesMap.get("旧线路").get(0).url.substring(CloudReference.PREFIX.length()), "UTF-8");
        oldJson = oldJson.substring(0, oldJson.length() - 1) + ",\"cookie\":\"private-cookie\",\"signedURL\":\"private-url\",\"temporaryFid\":\"private-fid\"}";
        history.seriesMap.get("旧线路").get(0).url = CloudReference.PREFIX + java.net.URLEncoder.encode(oldJson, "UTF-8");
        assertTrue(CloudHistoryRestore.restore(fresh, history));
        for (VodInfo.VodSeries episode : fresh.seriesMap.get("新线路")) {
            CloudReference safe = decode(episode);
            assertEquals("NEW2", safe.passcode);
            String json = URLDecoder.decode(episode.url, "UTF-8");
            assertFalse(json.contains("private-"));
            assertFalse(json.contains("OLD1"));
        }
        assertEquals("high", decode(fresh.seriesMap.get("新线路").get(0)).quality);
        assertEquals("file_1", decode(fresh.seriesMap.get("新线路").get(0)).fileId);
    }

    @Test public void otherSharesInHistoryAreFilteredAndDuplicateFileIdentityIsRejected() throws Exception {
        CloudReference foreign = file("foreign", "其他分享", "folderA", 1); foreign.shareId = "other_share";
        VodInfo history = history("旧线路", file("file_1", "第1集", "folderA", 1), foreign);
        VodInfo fresh = fresh("新线路");
        assertTrue(CloudHistoryRestore.restore(fresh, history));
        assertEquals(2, fresh.seriesMap.get("新线路").size());
        assertUnchanged(fresh("新线路"), history("旧线路", file("file_1", "第一名称", "folderA", 1), file("file_1", "重复", "folderA", 1)));
    }

    private static VodInfo fresh(String flag) {
        CloudReference entry = reference(); entry.passcode = "NEW2";
        VodInfo value = vod();
        value.seriesMap.put(flag, new ArrayList<>(Arrays.asList(new VodInfo.VodSeries("选择文件", entry.encode()))));
        value.seriesMap.put("另一线路", new ArrayList<>(Arrays.asList(new VodInfo.VodSeries("其他源", "https://example.org/movie.m3u8"))));
        value.playFlag = flag;
        return value;
    }
    private static VodInfo history(String flag, CloudReference... files) {
        VodInfo value = vod(); value.playFlag = flag;
        List<VodInfo.VodSeries> episodes = new ArrayList<>();
        for (CloudReference file : files) episodes.add(new VodInfo.VodSeries(file.fileName, file.encode()));
        value.seriesMap.put(flag, episodes);
        return value;
    }
    private static VodInfo vod() {
        VodInfo value = new VodInfo(); value.id = "post_123"; value.sourceKey = "fty_wogg";
        value.seriesMap = new LinkedHashMap<>(); value.seriesFlags = new ArrayList<>(); return value;
    }
    private static CloudReference reference() {
        CloudReference value = new CloudReference(); value.provider = "quark"; value.shareId = "share123";
        value.postId = "post_123"; value.sourceId = "fty_wogg"; return value;
    }
    private static CloudReference file(String id, String name, String folder, int page) {
        CloudReference value = reference(); value.fileId = id; value.fileName = name; value.folderId = folder; value.page = page; return value;
    }
    private static CloudReference decode(VodInfo.VodSeries episode) throws Exception { return CloudReference.decode(episode.url); }
    private static void assertUnchanged(VodInfo fresh, VodInfo history) {
        Object map = fresh.seriesMap, flags = fresh.seriesFlags;
        String playFlag = fresh.playFlag;
        int index = fresh.playIndex;
        assertFalse(CloudHistoryRestore.restore(fresh, history));
        assertSame(map, fresh.seriesMap); assertSame(flags, fresh.seriesFlags);
        assertEquals(playFlag, fresh.playFlag); assertEquals(index, fresh.playIndex);
    }
}
