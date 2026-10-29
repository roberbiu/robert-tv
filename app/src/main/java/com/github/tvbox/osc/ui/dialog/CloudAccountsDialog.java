package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.cloud.CloudProvider;
import com.github.tvbox.osc.cloud.CloudRuntime;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 管理本机授权；打开页面只读本机状态，扫码和退出均由明确点击触发。 */
public final class CloudAccountsDialog extends BaseDialog {
    private static final class Row {
        final TextView status;
        final Button login;
        final Button logout;
        Row(TextView status, Button login, Button logout) { this.status = status; this.login = login; this.logout = logout; }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<CloudProvider, Row> rows = new EnumMap<>(CloudProvider.class);
    private final Map<CloudProvider, CloudRuntime.LocalAuthorization> states = new EnumMap<>(CloudProvider.class);
    private Future<?> pending;
    private Button refresh;
    private CloudLoginDialog loginDialog;
    private int generation;
    private boolean closed;
    private boolean busy;

    public CloudAccountsDialog(Context context) { super(context); }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout body = new LinearLayout(getContext()); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(16), dp(22), dp(16)); body.setBackgroundColor(Color.rgb(21, 29, 42));
        TextView title = text("网盘账号授权", 23); title.setGravity(Gravity.CENTER); body.addView(title);

        ScrollView scroll = new ScrollView(getContext()); scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(getContext()); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams viewport = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        viewport.topMargin = dp(12); body.addView(scroll, viewport);
        TextView note = text("授权仅保存在本机。播放会临时转存到本应用独立目录并尝试清理，权益以网盘账号为准。退出会停止该网盘播放，保留观看历史。", 14);
        content.addView(note);
        for (CloudProvider provider : CloudProvider.values()) addProvider(content, provider);

        LinearLayout actions = new LinearLayout(getContext()); actions.setGravity(Gravity.CENTER);
        refresh = button("刷新本机状态", actions); refresh.setOnClickListener(v -> loadStates());
        Button close = button("关闭", actions); close.setOnClickListener(v -> dismiss()); body.addView(actions);
        setContentView(body);
        setOnDismissListener(dialog -> closeResources());
        close.requestFocus(); loadStates();
    }

    @Override protected void onStart() {
        super.onStart();
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        Window window = getWindow();
        if (window != null) window.setLayout(Math.min(dp(620), Math.max(1, metrics.widthPixels - dp(32))),
                Math.min(dp(510), Math.max(1, metrics.heightPixels - dp(32))));
    }

    @Override public void dismiss() { closeResources(); super.dismiss(); }
    @Override public void cancel() { closeResources(); super.cancel(); }

    private void closeResources() {
        if (closed) return;
        closed = true; cancelPending();
        if (loginDialog != null) { CloudLoginDialog child = loginDialog; loginDialog = null; child.dismiss(); }
        worker.shutdownNow(); main.removeCallbacksAndMessages(null);
    }

    private void addProvider(LinearLayout parent, CloudProvider provider) {
        LinearLayout section = new LinearLayout(getContext()); section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spacing.topMargin = dp(14); parent.addView(section, spacing);
        section.addView(text(provider.displayName, 20)); TextView status = text("正在读取本机授权状态", 15); section.addView(status);
        LinearLayout actions = new LinearLayout(getContext());
        Button login = button("扫码授权", actions); login.setOnClickListener(v -> openLogin(provider));
        Button logout = button("退出", actions); logout.setOnClickListener(v -> logout(provider)); section.addView(actions);
        rows.put(provider, new Row(status, login, logout));
    }

    private void loadStates() {
        if (closed || loginDialog != null || busy) return;
        cancelPending(); int requested = generation; setBusy(true); states.clear();
        for (Row row : rows.values()) row.status.setText("正在读取本机授权状态");
        pending = worker.submit(() -> {
            Map<CloudProvider, CloudRuntime.LocalAuthorization> loaded = new EnumMap<>(CloudProvider.class);
            Map<CloudProvider, String> failures = new EnumMap<>(CloudProvider.class);
            for (CloudProvider provider : CloudProvider.values()) {
                if (Thread.currentThread().isInterrupted()) return;
                try { loaded.put(provider, CloudRuntime.get(getContext()).readLocalAuthorization(provider)); }
                catch (CloudException failure) { failures.put(provider, failure.getMessage()); }
                catch (RuntimeException ignored) { failures.put(provider, "无法安全读取本机授权状态，请重试"); }
            }
            post(requested, () -> {
                states.putAll(loaded); setBusy(false);
                for (CloudProvider provider : CloudProvider.values()) {
                    Row row = rows.get(provider); CloudRuntime.LocalAuthorization state = loaded.get(provider);
                    boolean stored = state == CloudRuntime.LocalAuthorization.STORED;
                    row.status.setText(failures.containsKey(provider) ? failures.get(provider)
                            : stored ? "已保存本机授权 · 有效性在播放时校验" : "未保存本机授权");
                    row.login.setText(stored ? "重新扫码 / 换号" : "扫码授权");
                    row.logout.setEnabled(stored || failures.containsKey(provider));
                }
            });
        });
    }

    private void openLogin(CloudProvider provider) {
        if (closed || busy || loginDialog != null) return;
        cancelPending(); setBusy(true);
        loginDialog = new CloudLoginDialog(getContext(), provider, new CloudLoginDialog.Listener() {
            @Override public void onAuthorized() { }
            @Override public void onClosed() {
                loginDialog = null;
                if (!closed) { setBusy(false); loadStates(); }
            }
        });
        loginDialog.show();
        if (!loginDialog.isShowing()) { loginDialog = null; setBusy(false); loadStates(); }
    }

    private void logout(CloudProvider provider) {
        if (closed || busy || loginDialog != null) return;
        cancelPending(); int requested = generation; setBusy(true); rows.get(provider).status.setText("正在退出本机授权");
        pending = worker.submit(() -> {
            if (Thread.currentThread().isInterrupted()) return;
            String failure = null;
            try { CloudRuntime.get(getContext()).logout(provider); }
            catch (CloudException error) { failure = error.getMessage(); }
            catch (RuntimeException ignored) { failure = "无法安全保存退出状态，请重试"; }
            String message = failure;
            post(requested, () -> {
                setBusy(false);
                Toast.makeText(getContext(), message == null ? provider.displayName + "已退出本机授权" : message, Toast.LENGTH_SHORT).show();
                loadStates();
            });
        });
    }

    private void setBusy(boolean value) {
        busy = value; refresh.setEnabled(!value);
        for (Map.Entry<CloudProvider, Row> entry : rows.entrySet()) {
            entry.getValue().login.setEnabled(!value);
            entry.getValue().logout.setEnabled(!value && states.get(entry.getKey()) == CloudRuntime.LocalAuthorization.STORED);
        }
    }

    private void cancelPending() { generation++; if (pending != null) { pending.cancel(true); pending = null; } }
    private void post(int requested, Runnable action) { main.post(() -> { if (!closed && requested == generation) action.run(); }); }
    private TextView text(String label, int size) { TextView view = new TextView(getContext()); view.setText(label); view.setTextColor(Color.WHITE); view.setTextSize(size); return view; }
    private Button button(String label, LinearLayout parent) { Button view = new Button(getContext()); view.setText(label); parent.addView(view, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)); return view; }
    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
}
