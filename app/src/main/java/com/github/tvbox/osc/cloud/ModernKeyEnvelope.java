package com.github.tvbox.osc.cloud;

import android.annotation.TargetApi;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.google.gson.JsonObject;

import java.nio.charset.Charset;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 单独类隔离API23符号，旧系统不会装载此实现。 */
@TargetApi(23)
final class ModernKeyEnvelope {
    private static final String ALIAS = "liuliufufu.session.aes.v1";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    static JsonObject encrypt(String name, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        cipher.updateAAD(("2:" + name).getBytes(UTF8));
        JsonObject result = new JsonObject();
        result.addProperty("v", 2);
        result.addProperty("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        result.addProperty("ct", Base64.encodeToString(cipher.doFinal(value.getBytes(UTF8)), Base64.NO_WRAP));
        return result;
    }

    static String decrypt(String name, JsonObject envelope) throws Exception {
        byte[] iv = Base64.decode(envelope.get("iv").getAsString(), Base64.DEFAULT);
        if (iv.length != 12) throw new IllegalArgumentException("认证参数无效");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        cipher.updateAAD(("2:" + name).getBytes(UTF8));
        return new String(cipher.doFinal(Base64.decode(envelope.get("ct").getAsString(), Base64.DEFAULT)), UTF8);
    }
}
