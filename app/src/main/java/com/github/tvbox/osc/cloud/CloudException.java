package com.github.tvbox.osc.cloud;

/** 只携带可安全展示的领域错误，不把上游响应和凭据放进异常。 */
public final class CloudException extends Exception {
    public enum Code {
        NETWORK, AUTH_REQUIRED, SESSION_EXPIRED, ENTITLEMENT_REQUIRED, STORAGE_FULL,
        QR_EXPIRED, CANCELLED, UNSUPPORTED, INVALID_RESPONSE, NO_MATCH, OWNERSHIP_UNKNOWN, PRIVATE_STORAGE
    }

    public final Code code;
    public final CloudProvider provider;

    public CloudException(Code code, CloudProvider provider) {
        super(messageFor(code));
        this.code = code;
        this.provider = provider;
    }

    private static String messageFor(Code code) {
        switch (code) {
            case NETWORK: return "网络请求暂时失败，请重试";
            case AUTH_REQUIRED: return "此线路需要网盘授权";
            case SESSION_EXPIRED: return "网盘登录已失效，请重新扫码";
            case ENTITLEMENT_REQUIRED: return "当前账号权益不支持此版本，可选择其他版本";
            case STORAGE_FULL: return "网盘空间不足，可选择其他版本";
            case QR_EXPIRED: return "二维码已过期，请重新获取";
            case CANCELLED: return "操作已取消";
            case NO_MATCH: return "未找到可播放的视频文件";
            case OWNERSHIP_UNKNOWN: return "临时文件归属未确认，已停止文件操作";
            case PRIVATE_STORAGE: return "无法安全读取或保存网盘状态，请重试或选择其他版本";
            case UNSUPPORTED: return "此网盘或播放方式暂未支持";
            default: return "来源返回的数据暂时无法识别";
        }
    }
}
