package com.github.tvbox.osc.cloud;

import com.google.gson.Gson;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 可持久化的分享/文件身份。没有Cookie、下载地址、stoken或文件票据。 */
public final class CloudReference {
    public static final String PREFIX = "cloudref:";
    private static final Pattern SHARE_ID = Pattern.compile("[A-Za-z0-9_-]{4,128}");
    public String provider;
    public String shareId;
    public String passcode = "";
    public String folderId = "0";
    public String fileId = "";
    public String fileName = "";
    public String sourceId = "";
    public String postId = "";
    public int page = 1;
    public String quality = "auto";

    public CloudProvider cloudProvider() { return CloudProvider.fromId(provider); }

    public boolean isFile() { return fileId != null && !fileId.isEmpty(); }

    public CloudReference child(String folder, String fid, String name) {
        CloudReference copy = new Gson().fromJson(new Gson().toJson(this), CloudReference.class);
        copy.folderId = folder;
        copy.fileId = fid == null ? "" : fid;
        copy.fileName = name == null ? "" : name;
        return copy;
    }

    public String encode() {
        try { return PREFIX + URLEncoder.encode(new Gson().toJson(this), "UTF-8"); }
        catch (Exception ignored) { throw new IllegalArgumentException("无法编码文件身份"); }
    }

    public static CloudReference decode(String value) throws CloudException {
        try {
            if (value == null || !value.startsWith(PREFIX) || value.length() > 8192) throw new IllegalArgumentException();
            CloudReference ref = new Gson().fromJson(URLDecoder.decode(value.substring(PREFIX.length()), "UTF-8"), CloudReference.class);
            ref.validate();
            return ref;
        } catch (Exception ignored) { throw new CloudException(CloudException.Code.INVALID_RESPONSE, null); }
    }

    public static CloudReference fromShareUrl(String value, String sourceId, String postId) throws CloudException {
        try {
            if (value == null || value.length() > 4096) throw new IllegalArgumentException();
            // 页面可能在链接后附提取码；URL部分只允许网盘正式主机。
            Matcher link = Pattern.compile("https://(?:pan\\.quark\\.cn|drive\\.uc\\.cn)/s/[A-Za-z0-9_-]+(?:\\?[^\\s|]*)?").matcher(value);
            if (!link.find()) throw new IllegalArgumentException();
            URI uri = new URI(link.group());
            CloudReference ref = new CloudReference();
            ref.provider = "pan.quark.cn".equalsIgnoreCase(uri.getHost()) ? "quark" : "uc";
            ref.shareId = uri.getPath().substring(3);
            ref.sourceId = sourceId == null ? "" : sourceId;
            ref.postId = postId == null ? "" : postId;
            String query = uri.getRawQuery();
            if (query != null) for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2 && ("pwd".equals(pair[0]) || "passcode".equals(pair[0]))) ref.passcode = URLDecoder.decode(pair[1], "UTF-8");
            }
            Matcher password = Pattern.compile("(?:提取码|提取碼|密码|密碼)[:：\\s]*([A-Za-z0-9]{4,8})").matcher(value);
            if (ref.passcode.isEmpty() && password.find()) ref.passcode = password.group(1);
            ref.validate();
            return ref;
        } catch (Exception ignored) { throw new CloudException(CloudException.Code.UNSUPPORTED, null); }
    }

    private void validate() {
        cloudProvider();
        if (shareId == null || !SHARE_ID.matcher(shareId).matches()) throw new IllegalArgumentException();
        if (folderId == null || !folderId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException();
        if (fileId == null || (!fileId.isEmpty() && !fileId.matches("[A-Za-z0-9_-]{1,128}"))) throw new IllegalArgumentException();
        if (passcode == null || passcode.length() > 16 || fileName == null || fileName.length() > 512) throw new IllegalArgumentException();
        if (sourceId == null || sourceId.length() > 64 || postId == null || postId.length() > 512) throw new IllegalArgumentException();
        if (page < 1 || page > 20 || quality == null || !quality.matches("auto|low|normal|high|super|2k|4k|original")) throw new IllegalArgumentException();
    }

    @Override public String toString() { return "CloudReference(" + provider + ",redacted)"; }
}
