package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.cloud.CloudProvider;
import com.github.tvbox.osc.cloud.CloudRuntime;
import com.github.tvbox.osc.cloud.QrLoginCoordinator;
import com.github.tvbox.osc.ui.tv.QRCodeGen;

/** 只由明确的遥控器授权动作打开。二维码在内存显示，不提供Cookie输入。 */
public final class CloudLoginDialog extends BaseDialog {
    public interface Listener {
        void onAuthorized();
        /** 组合关闭通知，调用方不要替换内部的dismiss清理监听。 */
        default void onClosed() { }
    }
    private final CloudProvider provider;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private QrLoginCoordinator.Handle handle;
    private ImageView qr;
    private TextView status;
    private Button retry;
    private int qrSize;
    private int generation;
    private boolean delivered;
    private boolean closed;

    public CloudLoginDialog(Context context, CloudProvider provider, Listener listener) {
        super(context); this.provider = provider; this.listener = listener;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout body = new LinearLayout(getContext()); body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL); body.setPadding(dp(20), dp(16), dp(20), dp(16)); body.setBackgroundColor(Color.rgb(21, 29, 42));
        body.addView(text(provider.displayName + "扫码授权", 23));

        ScrollView scroll = new ScrollView(getContext()); scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(getContext()); content.setOrientation(LinearLayout.VERTICAL); content.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams viewport = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        viewport.topMargin = dp(10); body.addView(scroll, viewport);

        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        int availableHeight = Math.max(1, metrics.heightPixels - dp(32));
        qrSize = Math.min(Math.max(dp(112), Math.min(dp(216), availableHeight - dp(240))), Math.max(1, metrics.widthPixels - dp(72)));
        qr = new ImageView(getContext()); qr.setBackgroundColor(Color.WHITE); qr.setPadding(dp(8), dp(8), dp(8), dp(8));
        content.addView(qr, new LinearLayout.LayoutParams(qrSize, qrSize));
        status = text("正在获取二维码", 17); LinearLayout.LayoutParams detail = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detail.topMargin = dp(10); content.addView(status, detail);
        TextView note = text("请用自己的手机账号确认，授权仅保存在本机。播放会临时转存到本应用独立目录并尝试清理；画质、速度等权益以网盘账号为准。", 14);
        LinearLayout.LayoutParams explanation = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        explanation.topMargin = dp(8); content.addView(note, explanation);
        LinearLayout actions = new LinearLayout(getContext()); actions.setGravity(Gravity.CENTER);
        retry = new Button(getContext()); retry.setText("重新获取二维码"); retry.setVisibility(View.GONE); retry.setOnClickListener(v -> start());
        actions.addView(retry, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button cancel = new Button(getContext()); cancel.setText("取消"); cancel.setOnClickListener(v -> dismiss());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); body.addView(actions);
        setContentView(body);
        setOnDismissListener(dialog -> {
            closeResources();
            if (listener != null) listener.onClosed();
        });
        cancel.requestFocus(); start();
    }

    @Override protected void onStart() {
        super.onStart();
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        Window window = getWindow();
        if (window != null) window.setLayout(Math.min(dp(440), Math.max(1, metrics.widthPixels - dp(32))),
                Math.min(dp(540), Math.max(1, metrics.heightPixels - dp(32))));
    }

    @Override public void dismiss() { closeResources(); super.dismiss(); }
    @Override public void cancel() { closeResources(); super.cancel(); }

    private void closeResources() {
        if (closed) return;
        // 先同步失效，再等待Dialog异步派发dismiss通知，阻止关闭后的授权回调。
        closed = true; generation++;
        if (handle != null) { handle.cancel(); handle = null; }
        main.removeCallbacksAndMessages(null);
        if (qr != null) qr.setImageDrawable(null);
    }

    private void start() {
        if (closed) return;
        int requested = ++generation;
        if (handle != null) handle.cancel(); qr.setImageDrawable(null); retry.setVisibility(View.GONE); status.setText("正在获取二维码");
        handle = CloudRuntime.get(getContext()).login(provider, new QrLoginCoordinator.Listener() {
            private boolean current() { return !closed && requested == generation; }
            @Override public void onState(QrLoginCoordinator.State state, String safeMessage) { if (current()) status.setText(safeMessage); }
            @Override public void onQr(String content, long localWaitUntil) {
                if (!current()) return;
                // 不把短期授权票据写入日志、剪贴板或持久化状态。
                qr.setImageBitmap(QRCodeGen.generateBitmap(content, qrSize, qrSize));
            }
            @Override public void onAuthorized() {
                if (!current() || delivered) return;
                delivered = true; dismiss(); if (listener != null) listener.onAuthorized();
            }
            @Override public void onError(CloudException error) {
                if (current() && error.code != CloudException.Code.CANCELLED) { retry.setVisibility(View.VISIBLE); retry.requestFocus(); }
            }
        }, action -> main.post(action));
    }

    private TextView text(String content, int size) { TextView view = new TextView(getContext()); view.setText(content); view.setTextColor(Color.WHITE); view.setTextSize(size); view.setGravity(Gravity.CENTER); return view; }
    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
}
