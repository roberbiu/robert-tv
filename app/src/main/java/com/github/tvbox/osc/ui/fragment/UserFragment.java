package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.VodInfo;
import com.github.tvbox.osc.cache.RoomDataManger;
import com.github.tvbox.osc.ui.activity.CollectActivity;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.HistoryActivity;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.activity.SettingActivity;
import com.github.tvbox.osc.ui.adapter.HomeHotVodAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;

/** 首页只展示本机最近观看；来源变化不将推荐内容重新塞回首页。 */
public class UserFragment extends BaseLazyFragment implements View.OnClickListener {
    public static HomeHotVodAdapter homeHotVodAdapter;
    public static TvRecyclerView tvHotList;
    private View historyEmpty;
    private View historyEmptySearch;
    private LinearLayout searchButton;
    private boolean gridStyle;
    private boolean layoutReady;
    private String selectedKey;

    public static UserFragment newInstance() {
        return new UserFragment();
    }

    /** 兼容旧调用入口，1.0首页不再消费站点推荐。 */
    public static UserFragment newInstance(List<Movie.Video> ignored) {
        return newInstance();
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_user;
    }

    @Override
    protected void init() {
        findViewById(R.id.tvLive).setVisibility(View.GONE);
        findViewById(R.id.tvPush).setVisibility(View.GONE);
        searchButton = findViewById(R.id.tvSearch);
        for (int id : new int[]{R.id.tvSearch, R.id.tvHistory, R.id.tvFavorite, R.id.tvSetting}) {
            View button = findViewById(id);
            button.setOnClickListener(this);
            button.setOnFocusChangeListener((view, focused) -> view.animate()
                    .scaleX(focused ? 1.06f : 1).scaleY(focused ? 1.06f : 1).setDuration(150).start());
        }
        findViewById(R.id.tvHomeListTitle).setVisibility(View.VISIBLE);
        historyEmpty = findViewById(R.id.historyEmpty);
        historyEmptySearch = findViewById(R.id.historyEmptySearch);
        historyEmptySearch.setOnClickListener(this);
        tvHotList = findViewById(R.id.tvHotList);
        homeHotVodAdapter = new HomeHotVodAdapter(null, "", true);
        tvHotList.setAdapter(homeHotVodAdapter);
        homeHotVodAdapter.setOnItemClickListener((adapter, view, position) -> {
            FastClickCheckUtil.check(view);
            Movie.Video video = (Movie.Video) adapter.getItem(position);
            if (video == null) return;
            if (HawkConfig.hotVodDelete) {
                VodInfo info = RoomDataManger.getVodInfo(video.sourceKey, video.id);
                if (info != null) RoomDataManger.deleteVodRecord(video.sourceKey, info);
                refreshRecentHistory();
                Toast.makeText(mContext, "已删除当前观看记录", Toast.LENGTH_SHORT).show();
                return;
            }
            if (ApiConfig.get().getSource(video.sourceKey) == null) {
                Intent search = new Intent(mContext, FastSearchActivity.class);
                search.putExtra("title", video.name);
                search.putExtra("expected_year", video.year);
                startActivity(search);
                return;
            }
            Bundle arguments = new Bundle();
            arguments.putString("id", video.id);
            arguments.putString("sourceKey", video.sourceKey);
            arguments.putString("title", video.name);
            arguments.putString("picture", video.pic);
            arguments.putInt("year", video.year);
            jumpActivity(DetailActivity.class, arguments);
        });
        homeHotVodAdapter.setOnItemLongClickListener((adapter, view, position) -> {
            HawkConfig.hotVodDelete = !HawkConfig.hotVodDelete;
            homeHotVodAdapter.notifyDataSetChanged();
            Toast.makeText(mContext, HawkConfig.hotVodDelete ? "选择记录可删除，按返回退出删除模式" : "已退出删除模式", Toast.LENGTH_SHORT).show();
            return true;
        });
        tvHotList.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View view, int position) {
                view.animate().scaleX(1).scaleY(1).setDuration(150).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View view, int position) {
                Movie.Video video = homeHotVodAdapter.getItem(position);
                if (video != null) selectedKey = video.sourceKey + "|" + video.id;
                view.animate().scaleX(1.04f).scaleY(1.04f).setDuration(150).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View view, int position) {
            }
        });
        configureLayout();
    }

    private void configureLayout() {
        boolean requested = Hawk.get(HawkConfig.HOME_REC_STYLE, false);
        if (layoutReady && requested == gridStyle) return;
        gridStyle = requested;
        tvHotList.setLayoutManager(gridStyle ? new V7GridLayoutManager(mContext, 5)
                : new V7LinearLayoutManager(mContext, V7LinearLayoutManager.HORIZONTAL, false));
        int inset = getResources().getDimensionPixelSize(R.dimen.vs_6);
        int vertical = getResources().getDimensionPixelSize(R.dimen.vs_20);
        tvHotList.setPadding(inset, vertical, inset, vertical);
        layoutReady = true;
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        configureLayout();
        refreshRecentHistory();
    }

    public void refreshRecentHistory() {
        if (historyEmpty == null || homeHotVodAdapter == null) return;
        boolean listFocused = tvHotList.hasFocus();
        boolean emptyFocused = historyEmptySearch.hasFocus();
        List<Movie.Video> rows = new ArrayList<>();
        int focusedIndex = 0;
        for (VodInfo info : RoomDataManger.getAllVodRecord(20)) {
            Movie.Video video = new Movie.Video();
            video.id = info.id;
            video.sourceKey = info.sourceKey;
            video.name = info.name;
            video.pic = info.pic;
            video.year = info.year;
            if (info.playNote != null && !info.playNote.isEmpty()) video.note = "上次看到" + info.playNote;
            if ((video.sourceKey + "|" + video.id).equals(selectedKey)) focusedIndex = rows.size();
            rows.add(video);
        }
        homeHotVodAdapter.setNewData(rows);
        boolean empty = rows.isEmpty();
        tvHotList.setVisibility(empty ? View.GONE : View.VISIBLE);
        historyEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            HawkConfig.hotVodDelete = false;
            if (listFocused) historyEmptySearch.requestFocus();
        } else if (emptyFocused || listFocused) {
            tvHotList.setSelection(focusedIndex);
            tvHotList.requestFocus();
        }
    }

    public boolean focusMainSearch() {
        return searchButton != null && searchButton.requestFocus();
    }

    public boolean handleBack() {
        return rootView != null && rootView.hasFocus() && mActivity instanceof com.github.tvbox.osc.ui.activity.HomeActivity
                && ((com.github.tvbox.osc.ui.activity.HomeActivity) mActivity).focusNavigationBar();
    }

    @Override
    public void onClick(View view) {
        FastClickCheckUtil.check(view);
        HawkConfig.hotVodDelete = false;
        if (view.getId() == R.id.tvSearch || view.getId() == R.id.historyEmptySearch) jumpActivity(SearchActivity.class);
        else if (view.getId() == R.id.tvHistory) jumpActivity(HistoryActivity.class);
        else if (view.getId() == R.id.tvFavorite) jumpActivity(CollectActivity.class);
        else if (view.getId() == R.id.tvSetting) jumpActivity(SettingActivity.class);
    }
}
