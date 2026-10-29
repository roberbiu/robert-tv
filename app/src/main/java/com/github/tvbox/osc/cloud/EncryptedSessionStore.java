package com.github.tvbox.osc.cloud;

import android.content.Context;
import android.os.Build;
import android.util.AtomicFile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.Charset;

/** 只写应用私有目录，密钥不可用时失败，不退化为明文。 */
public final class EncryptedSessionStore implements SecretStore {
    private final Context context;
    private final File directory;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public EncryptedSessionStore(Context context) {
        this.context = context.getApplicationContext();
        this.directory = this.context.getDir("liuliufufu_cloud_v1", Context.MODE_PRIVATE);
    }

    private AtomicFile file(String key) {
        if (key == null || !key.matches("[a-zA-Z0-9_-]{1,48}")) throw new IllegalArgumentException("存储标识无效");
        return new AtomicFile(new File(directory, key + ".sealed"));
    }

    @Override public synchronized String read(String key) throws Exception {
        AtomicFile file = file(key);
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) return null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (FileInputStream input = file.openRead()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 262144) throw new IllegalArgumentException("私有状态过大");
                output.write(buffer, 0, count);
            }
        }
        JsonObject envelope = JsonParser.parseString(new String(output.toByteArray(), UTF8)).getAsJsonObject();
        int version = envelope.get("v").getAsInt();
        if (version == 1) return LegacyKeyEnvelope.decrypt(context, key, envelope);
        if (version == 2 && Build.VERSION.SDK_INT >= 23) return ModernKeyEnvelope.decrypt(key, envelope);
        throw new IllegalArgumentException("不支持的私有状态版本");
    }

    @Override public synchronized void write(String key, String value) throws Exception {
        if (value == null || value.getBytes(UTF8).length > 180224) throw new IllegalArgumentException("私有状态无效");
        JsonObject envelope = Build.VERSION.SDK_INT >= 23
                ? ModernKeyEnvelope.encrypt(key, value)
                : LegacyKeyEnvelope.encrypt(context, key, value);
        AtomicFile file = file(key);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(envelope.toString().getBytes(UTF8));
            file.finishWrite(stream);
        } catch (Exception failure) {
            if (stream != null) file.failWrite(stream);
            throw new java.io.IOException("无法安全保存网盘状态");
        }
    }
}
