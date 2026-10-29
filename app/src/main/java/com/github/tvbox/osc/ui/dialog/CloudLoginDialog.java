package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.cloud.CloudProvider;
import com.github.tvbox.osc.cloud.CloudRuntime;
import com.github.tvbox.osc.cloud.QrLoginCoordinator;
import com.github.tvbox.osc.ui.tv.QRCodeGen;

/** 只由明确的遥控器授权动作打开。二维码在内存显示，不提供Cookie输入。 */
public final class CloudLoginDialog extends BaseDialog {
    public interface Listener { void onAuthorized(); }
    private final CloudProvider provider;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private QrLoginCoordinator.Handle handle;
    private ImageView qr;
    private TextView status;
    private Button retry;
    private boolean delivered;

    public CloudLoginDialog(Context context, CloudProvider provider, Listener listener) {
        super(context); this.provider = provider; this.listener = listener;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout body = new LinearLayout(getContext()); body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER); body.setPadding(dp(24), dp(20), dp(24), dp(20)); body.setBackgroundColor(Color.rgb(21, 29, 42));
        TextView title = text(provider.displayName + "扫码授权", 23); body.addView(title);
        qr = new ImageView(getContext()); qr.setBackgroundColor(Color.WHITE); qr.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams image = new LinearLayout.LayoutParams(dp(216), dp(216)); image.topMargin = dp(14); body.addView(qr, image);
        status = text("正在获取二维码", 17); LinearLayout.LayoutParams detail = new LinearLayout.LayoutParams(dp(390), ViewGroup.LayoutParams.WRAP_CONTENT);
        detail.topMargin = dp(12); body.addView(status, detail);
        TextView note = text("请用自己的手机账号确认。授权仅保存在本机；网络失败不会要求重新登录。", 14);
        body.addView(note, new LinearLayout.LayoutParams(dp(390), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout actions = new LinearLayout(getContext()); actions.setGravity(Gravity.CENTER);
        retry = new Button(getContext()); retry.setText("重新获取二维码"); retry.setVisibility(View.GONE); retry.setOnClickListener(v -> start()); actions.addView(retry);
        Button cancel = new Button(getContext()); cancel.setText("取消"); cancel.setOnClickListener(v -> dismiss()); actions.addView(cancel); body.addView(actions);
        setContentView(body); setOnDismissListener(dialog -> { if (handle != null) handle.cancel(); qr.setImageDrawable(null); });
        cancel.requestFocus(); start();
    }

    private void start() {
        if (handle != null) handle.cancel(); qr.setImageDrawable(null); retry.setVisibility(View.GONE);
        handle = CloudRuntime.get(getContext()).login(provider, new QrLoginCoordinator.Listener() {
            @Override public void onState(QrLoginCoordinator.State state, String safeMessage) { status.setText(safeMessage); }
            @Override public void onQr(String content, long localWaitUntil) {
                // 不把短期授权票据写入日志、剪贴板或持久化状态。
                qr.setImageBitmap(QRCodeGen.generateBitmap(content, dp(216), dp(216)));
            }
            @Override public void onAuthorized() {
                if (delivered) return; delivered = true; dismiss(); if (listener != null) listener.onAuthorized();
            }
            @Override public void onError(CloudException error) {
                if (error.code != CloudException.Code.CANCELLED) { retry.setVisibility(View.VISIBLE); retry.requestFocus(); }
            }
        }, action -> main.post(action));
    }

    private TextView text(String content, int size) { TextView view = new TextView(getContext()); view.setText(content); view.setTextColor(Color.WHITE); view.setTextSize(size); view.setGravity(Gravity.CENTER); return view; }
    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
}
