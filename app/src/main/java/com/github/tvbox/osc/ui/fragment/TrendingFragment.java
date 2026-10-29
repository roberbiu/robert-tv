package com.github.tvbox.osc.ui.fragment;

import android.view.View;

import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.browse.ContentKind;
import com.github.tvbox.osc.trending.TrendingBoard;
import com.github.tvbox.osc.trending.TrendingEntry;
import com.github.tvbox.osc.trending.TrendingFreshness;
import com.github.tvbox.osc.trending.TrendingRepository;
import com.github.tvbox.osc.trending.TrendingSnapshot;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public final class TrendingFragment extends CatalogPageFragment {
    private static final ContentKind[] KINDS = {ContentKind.TV, ContentKind.MOVIE, ContentKind.VARIETY, ContentKind.ANIMATION, ContentKind.SHORT_DRAMA};
    private ContentKind kind = ContentKind.TV;
    private TrendingBoard board = TrendingBoard.NETWORK_DRAMA;
    private TrendingRepository repository;
    private TrendingSnapshot snapshot;
    private boolean showHistorical;
    private boolean resumePending;

    public static TrendingFragment newInstance() {
        return new TrendingFragment();
    }

    @Override
    protected void initPage() {
        repository = new TrendingRepository(mContext);
        buildFilters();
        request(false);
    }

    private void buildFilters() {
        List<String> kindNames = new ArrayList<>();
        int kindIndex = 0;
        for (int index = 0; index < KINDS.length; index++) {
            kindNames.add(KINDS[index].label);
            if (KINDS[index] == kind) kindIndex = index;
        }
        chips(primaryFilters, kindNames, kindIndex, index -> {
            kind = KINDS[index];
            board = TrendingBoard.forKind(kind).get(0);
            showHistorical = false;
            snapshot = null;
            setRows(new ArrayList<>());
            buildFilters();
            request(false);
        });
        List<TrendingBoard> boards = TrendingBoard.forKind(kind);
        List<String> labels = new ArrayList<>();
        for (TrendingBoard value : boards) labels.add(value.label);
        chips(secondaryFilters, labels, boards.indexOf(board), index -> {
            board = boards.get(index);
            showHistorical = false;
            snapshot = null;
            setRows(new ArrayList<>());
            buildFilters();
            request(false);
        });
    }

    private void request(boolean force) {
        if (repository == null) return;
        loading(board.label + " · 正在读取公开榜单…");
        resumePending = false;
        repository.load(board, force, (result, cached, warning) -> {
            if (!isAdded()) return;
            finished();
            snapshot = result;
            render(cached, warning);
        });
    }

    private void render(boolean cached, String warning) {
        if (snapshot == null) {
            setRows(new ArrayList<>());
            String message = warning.isEmpty() ? "暂无可用榜单，可刷新或选择其他品类" : warning;
            showStatus(board.label + "\n" + message, "来源：" + board.label + "\n官方页面：" + board.officialUrl()
                    + "\n指标：" + board.metric + "\n状态：" + message + "\n尚未取得有效数据，不提供虚构日期或榜位");
            more.setVisibility(View.GONE);
            return;
        }
        boolean historical = TrendingFreshness.isHistorical(snapshot, System.currentTimeMillis());
        String cacheLabel = historical ? "历史参考" : cached ? "缓存" : "已更新获取";
        String details = "来源：" + board.label + "\n官方页面：" + board.officialUrl() + "\n指标：" + board.metric
                + "\n周期/源数据日期：" + snapshot.periodLabel + "\n源更新时间及精度：" + snapshot.updatedLabel
                + "\n获取时间（北京时间）：" + displayTime(snapshot.fetchedAt) + "\n显示状态：" + cacheLabel
                + (warning.isEmpty() ? "" : "\n提示：" + warning);
        showStatus(board.label + " · " + board.metric + " · " + cacheLabel + "\n"
                + snapshot.periodLabel + " · " + snapshot.updatedLabel + " · 北京时间获取 " + displayTime(snapshot.fetchedAt)
                + (warning.isEmpty() ? "" : " · " + warning), details);
        List<Movie.Video> rows = new ArrayList<>();
        if (!historical || showHistorical) {
            for (TrendingEntry entry : snapshot.entries) {
                Movie.Video video = new Movie.Video();
                video.name = entry.title;
                video.pic = entry.cover;
                video.type = board.kind.label;
                video.note = "#" + entry.rank + " · " + entry.score;
                rows.add(video);
            }
        }
        setRows(rows);
        more.setText(showHistorical ? "隐藏历史参考" : "查看历史参考");
        more.setVisibility(historical ? View.VISIBLE : View.GONE);
    }

    private String displayTime(long timestamp) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.CHINA);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date(timestamp));
    }

    @Override
    public void refreshPage() {
        if (pageInitialized) request(true);
    }

    @Override
    protected void onMoreClicked() {
        showHistorical = !showHistorical;
        render(true, "");
    }

    @Override
    protected void onCardClicked(int position) {
        if (position >= 0 && position < adapter.getData().size()) search(adapter.getData().get(position));
    }

    @Override
    protected void onFragmentPause() {
        super.onFragmentPause();
        resumePending = loading;
        loading = false;
        if (repository != null) repository.cancel();
    }

    @Override
    protected void onFragmentResume() {
        super.onFragmentResume();
        if (repository != null && !loading && resumePending) request(false);
    }

    @Override
    public void onDestroy() {
        if (repository != null) repository.close();
        super.onDestroy();
    }
}
