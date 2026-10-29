package com.github.tvbox.osc.cloud;

import android.content.Context;
import android.security.KeyPairGeneratorSpec;
import android.util.Base64;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Date;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

/** API19–22：系统RSA包裹独立AES/HMAC密钥，使用标准Encrypt-then-MAC。 */
final class LegacyKeyEnvelope {
    private static final String ALIAS = "liuliufufu.session.rsa.v1";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static synchronized KeyStore store(Context context) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");
            long now = System.currentTimeMillis();
            generator.initialize(new KeyPairGeneratorSpec.Builder(context).setAlias(ALIAS)
                    .setSubject(new X500Principal("CN=LiuliuFufuTV Private Storage"))
                    .setSerialNumber(BigInteger.ONE).setStartDate(new Date(now - 86400000L))
                    .setEndDate(new Date(now + 20L * 366 * 86400000L)).setKeySize(2048).build());
            generator.generateKeyPair();
        }
        return store;
    }

    static JsonObject encrypt(Context context, String name, String value) throws Exception {
        byte[] material = new byte[48];
        byte[] iv = new byte[16];
        RANDOM.nextBytes(material);
        RANDOM.nextBytes(iv);
        try {
            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(material, 0, 16), "AES"), new IvParameterSpec(iv));
            byte[] ciphertext = aes.doFinal(value.getBytes(UTF8));
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, store(context).getCertificate(ALIAS).getPublicKey());
            byte[] wrapped = rsa.doFinal(material);
            JsonObject result = new JsonObject();
            result.addProperty("v", 1);
            result.addProperty("wk", encode(wrapped));
            result.addProperty("iv", encode(iv));
            result.addProperty("ct", encode(ciphertext));
            result.addProperty("tag", encode(mac(material, authenticated(name, wrapped, iv, ciphertext))));
            return result;
        } finally { Arrays.fill(material, (byte) 0); }
    }

    static String decrypt(Context context, String name, JsonObject envelope) throws Exception {
        byte[] wrapped = decode(envelope, "wk"), iv = decode(envelope, "iv"), ciphertext = decode(envelope, "ct");
        if (wrapped.length != 256 || iv.length != 16) throw new IllegalArgumentException("认证参数无效");
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, (PrivateKey) store(context).getKey(ALIAS, null));
        byte[] material = rsa.doFinal(wrapped);
        try {
            if (material.length != 48 || !MessageDigest.isEqual(decode(envelope, "tag"), mac(material, authenticated(name, wrapped, iv, ciphertext))))
                throw new SecurityException("私有状态认证失败");
            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(material, 0, 16), "AES"), new IvParameterSpec(iv));
            return new String(aes.doFinal(ciphertext), UTF8);
        } finally { Arrays.fill(material, (byte) 0); }
    }

    private static byte[] authenticated(String name, byte[] wrapped, byte[] iv, byte[] ciphertext) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(1);
        for (byte[] part : new byte[][]{name.getBytes(UTF8), wrapped, iv, ciphertext}) { data.writeInt(part.length); data.write(part); }
        return output.toByteArray();
    }

    private static byte[] mac(byte[] material, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Arrays.copyOfRange(material, 16, 48), "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static String encode(byte[] data) { return Base64.encodeToString(data, Base64.NO_WRAP); }
    private static byte[] decode(JsonObject object, String key) { return Base64.decode(object.get(key).getAsString(), Base64.DEFAULT); }
}
