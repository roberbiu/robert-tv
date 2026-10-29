package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.github.tvbox.osc.cloud.CloudReference;

import java.util.ArrayList;
import java.util.List;

/** 清晰度是同一分享文件的选择，不把权益失败解释成授权失效。 */
public final class CloudQualityDialog extends BaseDialog {
    public interface Listener { void onSelected(CloudReference updated); }
    private final CloudReference reference;
    private final List<String> available;
    private final Listener listener;
    private static final String[] IDS = {"auto", "low", "normal", "high", "super", "2k", "4k", "original"};
    private static final String[] NAMES = {"自动均衡", "流畅", "标准", "高清", "超清", "2K", "4K", "原画 · 权益由网盘确认"};
    public CloudQualityDialog(Context context, CloudReference reference, List<String> available, Listener listener) {
        super(context); this.reference = reference; this.available = available == null ? null : new ArrayList<>(available); this.listener = listener;
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); LinearLayout body = new LinearLayout(getContext()); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(16), dp(22), dp(16)); body.setBackgroundColor(Color.rgb(21, 29, 42));
        TextView title = new TextView(getContext()); title.setText("选择清晰度"); title.setTextColor(Color.WHITE); title.setTextSize(22); title.setGravity(Gravity.CENTER); body.addView(title);
        List<String> labels = new ArrayList<>(), values = new ArrayList<>();
        for (int i = 0; i < IDS.length; i++) {
            if (!"auto".equals(IDS[i]) && !"original".equals(IDS[i]) && available != null && !available.contains(IDS[i])) continue;
            values.add(IDS[i]); labels.add((IDS[i].equals(reference.quality) ? "✓  " : "    ") + NAMES[i]);
        }
        ListView list = new ListView(getContext()); list.setAdapter(new ArrayAdapter<String>(getContext(), android.R.layout.simple_list_item_1, labels) {
            @Override public View getView(int position, View convertView, ViewGroup parent) { TextView view = (TextView) super.getView(position, convertView, parent); view.setTextColor(Color.WHITE); view.setTextSize(18); return view; }
        });
        body.addView(list, new LinearLayout.LayoutParams(dp(400), dp(300))); setContentView(body);
        list.setOnItemClickListener((parent, view, position, id) -> {
            CloudReference updated = reference.child(reference.folderId, reference.fileId, reference.fileName); updated.quality = values.get(position);
            dismiss(); if (listener != null) listener.onSelected(updated);
        }); list.requestFocus(); list.setSelection(Math.max(0, values.indexOf(reference.quality)));
    }
    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
}
