package com.github.tvbox.osc.catalog;

import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 每组目录独立保留最近有效与前一版本，不借用会话或历史存储。 */
final class CatalogProfileStore {
    private final File directory;
    CatalogProfileStore(File directory) { this.directory = directory; }

    synchronized String load(String id, String bundled) throws IOException {
        String current = read(id, false);
        try { return CatalogV2Policy.chooseLocalProfile(id, bundled, current, read(id, true)); }
        catch (IllegalArgumentException unavailable) { throw new IOException("没有可用的来源目录"); }
    }

    synchronized void save(String id, String content) throws IOException {
        String canonical = CatalogV2Policy.validateProfile(content, id);
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法保存来源目录");
        String current = read(id, false);
        if (current != null) write(file(id, true), current);
        write(file(id, false), canonical);
    }

    private String read(String id, boolean previous) {
        try (InputStream input = file(id, previous).openRead()) {
            return valid(readLimited(input), id);
        } catch (IOException | IllegalArgumentException error) { return null; }
    }
    private AtomicFile file(String id, boolean previous) {
        if (!(CatalogV2Policy.CMS.equals(id) || CatalogV2Policy.SELECTED.equals(id))) {
            throw new IllegalArgumentException("目录分组未准入");
        }
        return new AtomicFile(new File(directory, id + (previous ? "-previous.json" : ".json")));
    }
    private static String valid(String value, String id) {
        if (value == null) return null;
        try { return CatalogV2Policy.validateProfile(value, id); }
        catch (IllegalArgumentException error) { return null; }
    }
    private static void write(AtomicFile file, String content) throws IOException {
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(content.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException error) {
            if (output != null) file.failWrite(output);
            throw error;
        }
    }
    static String readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > CatalogV2Policy.MAX_BYTES) throw new IOException("目录响应过大");
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
