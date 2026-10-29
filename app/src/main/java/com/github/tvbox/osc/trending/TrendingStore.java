package com.github.tvbox.osc.trending;

import android.content.Context;
import android.util.AtomicFile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 每榜单独私有原子文件，只保存规范化字段，不缓存网页或请求上下文。 */
public final class TrendingStore {
    public static final class Record {
        public final TrendingSnapshot snapshot;
        public final long lastAttemptAt;

        Record(TrendingSnapshot snapshot, long lastAttemptAt) {
            this.snapshot = snapshot;
            this.lastAttemptAt = lastAttemptAt;
        }
    }

    private final File directory;

    public TrendingStore(Context context) {
        directory = new File(context.getFilesDir(), "trending-v1");
    }

    public synchronized Record read(TrendingBoard board) {
        File file = new File(directory, board.name() + ".json");
        if (!file.exists() || file.length() > PublicMetadataHttp.MAX_BYTES) return new Record(null, 0);
        try (InputStream input = new AtomicFile(file).openRead(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096];
            int count;
            while ((count = input.read(bytes)) != -1) {
                if (output.size() + count > PublicMetadataHttp.MAX_BYTES) throw new IOException("榜单缓存过大");
                output.write(bytes, 0, count);
            }
            JsonObject root = JsonParser.parseString(new String(output.toByteArray(), "UTF-8")).getAsJsonObject();
            if (root.get("schema").getAsInt() != 1 || !board.name().equals(root.get("board").getAsString())) return new Record(null, 0);
            long attempt = root.get("lastAttemptAt").getAsLong();
            if (!root.has("snapshot")) return new Record(null, attempt);
            JsonObject value = root.getAsJsonObject("snapshot");
            JsonArray rawEntries = value.getAsJsonArray("entries");
            if (rawEntries == null || rawEntries.size() == 0 || rawEntries.size() > 100) return new Record(null, attempt);
            List<TrendingEntry> entries = new ArrayList<>();
            for (JsonElement element : rawEntries) {
                JsonObject item = element.getAsJsonObject();
                String id = bounded(item, "id", 140);
                String title = bounded(item, "title", 140);
                int rank = item.get("rank").getAsInt();
                if (id.isEmpty() || title.isEmpty() || rank < 1 || rank > 500) return new Record(null, attempt);
                String imageProvider = board == TrendingBoard.BAIDU_MOVIE ? "baidu" : "hongguo";
                String detailHost = board == TrendingBoard.HONGGUO_SHORT ? "hongguoduanju.com"
                        : board == TrendingBoard.BAIDU_MOVIE ? "baike.baidu.com" : "d2.guduomedia.com";
                entries.add(new TrendingEntry(id, title, rank, bounded(item, "score", 80),
                        TrendingParsers.imageUrl(bounded(item, "cover", 2048), imageProvider),
                        TrendingParsers.safeLink(bounded(item, "detailUrl", 2048), detailHost, board.officialUrl())));
            }
            TrendingSnapshot snapshot = new TrendingSnapshot(board, bounded(value, "dataDate", 10),
                    bounded(value, "updatedLabel", 160), bounded(value, "periodLabel", 160),
                    value.get("fetchedAt").getAsLong(), value.get("firstSeenAt").getAsLong(),
                    value.get("lastChangedAt").getAsLong(), bounded(value, "fingerprint", 64), entries);
            if (snapshot.fetchedAt <= 0 || snapshot.firstSeenAt <= 0 || snapshot.lastChangedAt <= 0
                    || !snapshot.fingerprint.matches("[a-f0-9]{64}")) return new Record(null, attempt);
            return new Record(snapshot, attempt);
        } catch (Exception ignored) {
            return new Record(null, 0);
        }
    }

    public synchronized void write(TrendingBoard board, TrendingSnapshot snapshot, long lastAttemptAt) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法保存榜单缓存");
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("board", board.name());
        root.addProperty("lastAttemptAt", lastAttemptAt);
        if (snapshot != null) {
            JsonObject value = new JsonObject();
            value.addProperty("dataDate", snapshot.dataDate);
            value.addProperty("updatedLabel", snapshot.updatedLabel);
            value.addProperty("periodLabel", snapshot.periodLabel);
            value.addProperty("fetchedAt", snapshot.fetchedAt);
            value.addProperty("firstSeenAt", snapshot.firstSeenAt);
            value.addProperty("lastChangedAt", snapshot.lastChangedAt);
            value.addProperty("fingerprint", snapshot.fingerprint);
            JsonArray entries = new JsonArray();
            for (TrendingEntry entry : snapshot.entries) {
                JsonObject item = new JsonObject();
                item.addProperty("id", entry.id);
                item.addProperty("title", entry.title);
                item.addProperty("rank", entry.rank);
                item.addProperty("score", entry.score);
                item.addProperty("cover", entry.cover);
                item.addProperty("detailUrl", entry.detailUrl);
                entries.add(item);
            }
            value.add("entries", entries);
            root.add("snapshot", value);
        }
        AtomicFile file = new AtomicFile(new File(directory, board.name() + ".json"));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(root.toString().getBytes("UTF-8"));
            file.finishWrite(stream);
        } catch (IOException error) {
            if (stream != null) file.failWrite(stream);
            throw error;
        }
    }

    private static String bounded(JsonObject object, String key, int max) {
        String value = object.get(key).getAsString();
        if (value.length() > max || value.matches("(?s).*\\p{Cntrl}.*")) throw new IllegalArgumentException("缓存字段无效");
        return value;
    }
}
