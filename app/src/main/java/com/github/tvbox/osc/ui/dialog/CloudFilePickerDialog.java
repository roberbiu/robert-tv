package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.github.tvbox.osc.cloud.Cancellation;
import com.github.tvbox.osc.cloud.CloudException;
import com.github.tvbox.osc.cloud.CloudFile;
import com.github.tvbox.osc.cloud.CloudProviderClient;
import com.github.tvbox.osc.cloud.CloudReference;
import com.github.tvbox.osc.cloud.CloudRuntime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 仅列出当前分享目录；进入文件夹、翻页和播放均需要用户选择。 */
public final class CloudFilePickerDialog extends BaseDialog {
    public interface Listener {
        void onSelected(Selection selection);
        void onError(CloudException error);
    }
    public static final class Choice {
        public final String reference;
        public final String displayName;
        public final String shareFileId;
        Choice(CloudFile file, CloudReference parent) {
            reference = file.reference(parent).encode(); displayName = file.name; shareFileId = file.id;
        }
    }
    public static final class Selection {
        public final Choice selected;
        public final List<Choice> choices;
        public final String folderReference;
        public final int page;
        public final boolean hasMore;
        Selection(Choice selected, List<Choice> choices, CloudReference folder, int page, boolean hasMore) {
            this.selected = selected; this.choices = Collections.unmodifiableList(new ArrayList<>(choices));
            this.folderReference = folder.encode(); this.page = page; this.hasMore = hasMore;
        }
    }
    private static final class Folder {
        final CloudReference reference; final int page;
        Folder(CloudReference reference, int page) { this.reference = reference; this.page = page; }
    }
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Stack<Folder> parents = new Stack<>();
    private CloudReference folder;
    private int page = 1;
    private int generation;
    private boolean closed;
    private Cancellation cancellation = new Cancellation();
    private TextView status;
    private ListView files;
    private Button previous;
    private Button next;
    private List<CloudFile> current = Collections.emptyList();
    private boolean more;

    public CloudFilePickerDialog(Context context, CloudReference reference, Listener listener) {
        super(context); this.folder = reference.child(reference.folderId, "", ""); this.page = reference.page; this.listener = listener;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(getContext()); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(22), dp(18), dp(22), dp(18)); body.setBackgroundColor(Color.rgb(21, 29, 42));
        TextView title = text(folder.cloudProvider().displayName + " · 选择文件与集数", 22); body.addView(title);
        status = text("正在读取目录", 15); body.addView(status);
        files = new ListView(getContext()); files.setItemsCanFocus(false); files.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        body.addView(files, new LinearLayout.LayoutParams(dp(580), dp(230)));
        files.setOnItemClickListener((parent, view, position, id) -> choose(position));
        LinearLayout actions = new LinearLayout(getContext()); actions.setGravity(Gravity.CENTER);
        Button up = button("上层目录", actions); up.setOnClickListener(v -> up());
        previous = button("上一页", actions); previous.setOnClickListener(v -> { if (page > 1) { page--; load(); } });
        next = button("下一页", actions); next.setOnClickListener(v -> { if (more && page < 20) { page++; load(); } });
        Button retry = button("重试", actions); retry.setOnClickListener(v -> load());
        Button close = button("取消", actions); close.setOnClickListener(v -> dismiss()); body.addView(actions);
        setContentView(body); setOnDismissListener(dialog -> { closed = true; generation++; cancellation.cancel(); worker.shutdownNow(); });
        setOnKeyListener((dialog, code, event) -> {
            if (code == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP && !parents.empty()) { up(); return true; }
            return false;
        }); load();
    }

    private void load() {
        cancellation.cancel(); cancellation = new Cancellation(); Cancellation request = cancellation;
        int requested = ++generation; CloudReference requestedFolder = folder; int requestedPage = page;
        status.setText("正在读取目录，第" + page + "页"); files.setEnabled(false); previous.setEnabled(false); next.setEnabled(false);
        worker.execute(() -> {
            try {
                CloudProviderClient.SharePage result = CloudRuntime.get(getContext()).listShare(requestedFolder, requestedPage, request);
                main.post(() -> { if (!closed && requested == generation) showPage(result); });
            } catch (CloudException error) {
                main.post(() -> {
                    if (closed || requested != generation) return;
                    current = Collections.emptyList(); more = false; files.setAdapter(null); status.setText(error.getMessage());
                    if (error.code == CloudException.Code.AUTH_REQUIRED || error.code == CloudException.Code.SESSION_EXPIRED) { dismiss(); if (listener != null) listener.onError(error); }
                });
            }
        });
    }

    private void showPage(CloudProviderClient.SharePage result) {
        current = result.items; more = result.hasMore; List<String> names = new ArrayList<>();
        for (CloudFile file : current) names.add((file.directory ? "文件夹  " : "视频  ") + file.name);
        files.setAdapter(new ArrayAdapter<String>(getContext(), android.R.layout.simple_list_item_1, names) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent); view.setTextColor(Color.WHITE); view.setTextSize(18); return view;
            }
        });
        files.setEnabled(true); previous.setEnabled(page > 1); next.setEnabled(more && page < 20);
        status.setText(current.isEmpty() ? "此目录没有可播放视频；可返回上层选择其他目录" : "第" + page + "页 · 选择实际文件后开始播放");
        if (!current.isEmpty()) { files.requestFocus(); files.setSelection(0); }
    }

    private void choose(int position) {
        if (position < 0 || position >= current.size()) return;
        CloudFile selected = current.get(position);
        if (selected.directory) { parents.push(new Folder(folder, page)); folder = selected.reference(folder); page = 1; load(); return; }
        CloudReference selectedPage = folder.child(folder.folderId, "", "");
        selectedPage.page = page;
        List<Choice> choices = new ArrayList<>(); Choice choice = null;
        for (CloudFile file : current) if (file.video && !file.directory) {
            Choice item = new Choice(file, selectedPage); choices.add(item); if (file.id.equals(selected.id)) choice = item;
        }
        if (choice == null) return;
        Selection result = new Selection(choice, choices, selectedPage, page, more); dismiss(); if (listener != null) listener.onSelected(result);
    }

    private void up() { if (!parents.empty()) { Folder parent = parents.pop(); folder = parent.reference; page = parent.page; load(); } }
    private TextView text(String label, int size) { TextView text = new TextView(getContext()); text.setText(label); text.setTextSize(size); text.setTextColor(Color.WHITE); return text; }
    private Button button(String label, LinearLayout actions) { Button view = new Button(getContext()); view.setText(label); actions.addView(view); return view; }
    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
}
