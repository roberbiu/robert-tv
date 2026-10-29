package com.github.tvbox.osc.server;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import com.github.tvbox.osc.receiver.SearchReceiver;

import java.io.IOException;

/** 手机辅助输入的生命周期入口，不再接收远程文件或配置管理动作。 */
public final class ControlManager {
    private static volatile ControlManager instance;
    private volatile RemoteServer server;
    public static Context mContext;
    private final InputPairing pairing = new InputPairing(SystemClock::elapsedRealtime);

    private ControlManager() { }

    public static ControlManager get() {
        if (instance == null) {
            synchronized (ControlManager.class) {
                if (instance == null) instance = new ControlManager();
            }
        }
        return instance;
    }

    public static void init(Context context) { mContext = context.getApplicationContext(); }

    /** 旧调用只取得地址，不隐式授权输入；裸地址下所有路由都拒绝。 */
    public String getAddress(boolean local) {
        startServer();
        RemoteServer active = server;
        if (active == null || !active.isStarting()) return "";
        return local ? active.getLoadAddress() : active.getServerAddress();
    }

    /** 只有用户主动打开手机输入窗口时调用，返回完整扫码地址，不写日志。 */
    public synchronized String openInputSession() {
        startServer();
        if (server == null || !server.isStarting()) return "";
        String address = server.getServerAddress();
        if (address.isEmpty()) return "";
        return pairing.open(address);
    }

    /** 旧窗口只能关闭自己创建的配对，不能关闭后来打开的新窗口。 */
    public void closeInputSession(String address) { pairing.close(address); }

    public synchronized void startServer() {
        if (server != null && server.isStarting()) return;
        if (mContext == null) return;
        for (int attempt = 0; attempt < 22 && RemoteServer.serverPort < 10000; attempt++) {
            RemoteServer candidate = new RemoteServer(RemoteServer.serverPort, mContext, pairing);
            candidate.setDataReceiver(new DataReceiver() {
                @Override public void onTextReceived(String text) {
                    String word = InputPairing.normalizeText(text);
                    if (word.isEmpty()) return;
                    Intent intent = new Intent(SearchReceiver.action);
                    intent.setPackage(mContext.getPackageName());
                    intent.setComponent(new ComponentName(mContext, SearchReceiver.class));
                    Bundle data = new Bundle();
                    data.putString("title", word);
                    intent.putExtras(data);
                    mContext.sendBroadcast(intent);
                }
                @Override public void onApiReceived(String url) { }
                @Override public void onLiveApiReceived(String url) { }
                @Override public void onDanmuApiReceived(String url) { }
                @Override public void onPushReceived(String url) { }
            });
            try {
                candidate.start();
                server = candidate;
                return;
            } catch (IOException unavailable) {
                candidate.stop();
                RemoteServer.serverPort++;
            }
        }
    }

    public synchronized void stopServer() {
        pairing.closeAll();
        if (server != null) server.stop();
        server = null;
    }
}
