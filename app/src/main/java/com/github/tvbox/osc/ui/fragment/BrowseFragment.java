package com.github.tvbox.osc.ui.fragment;

import android.os.Bundle;
import android.view.View;

import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.browse.BrowseRepository;
import com.github.tvbox.osc.browse.ContentKind;
import com.github.tvbox.osc.catalog.SourceRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class BrowseFragment extends CatalogPageFragment {
    private static final ContentKind[] REGION_KINDS = {ContentKind.TV, ContentKind.MOVIE, ContentKind.VARIETY, ContentKind.ANIMATION};
    private BrowseRepository repository;
    private ContentKind kind;
    private String country;
    private final List<Movie.Video> rows = new ArrayList<>();
    private boolean hasMore;
    private boolean resumePending;
    private boolean pendingReset;
    private long loadedRevision = -1;

    public static BrowseFragment newInstance(ContentKind kind, String country) {
        BrowseFragment fragment = new BrowseFragment();
        Bundle arguments = new Bundle();
        arguments.putString("kind", kind.name());
        arguments.putString("country", country == null ? "" : country);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    protected void initPage() {
        kind = ContentKind.valueOf(getArguments().getString("kind", ContentKind.TV.name()));
        country = getArguments().getString("country", "");
        repository = new BrowseRepository(mContext);
        buildFilters();
        request(true);
    }

    private void buildFilters() {
        List<String> names = new ArrayList<>();
        if (!country.isEmpty()) {
            for (ContentKind value : REGION_KINDS) names.add(value.label);
            int selected = Arrays.asList(REGION_KINDS).indexOf(kind);
            chips(primaryFilters, names, Math.max(0, selected), index -> {
                kind = REGION_KINDS[index];
                getArguments().putString("kind", kind.name());
                buildFilters();
                request(true);
            });
        } else if (kind == ContentKind.MOVIE || kind == ContentKind.DOCUMENTARY) {
            chips(primaryFilters, Arrays.asList("电影", "纪录片"), kind == ContentKind.MOVIE ? 0 : 1, index -> {
                kind = index == 0 ? ContentKind.MOVIE : ContentKind.DOCUMENTARY;
                getArguments().putString("kind", kind.name());
                buildFilters();
                request(true);
            });
        } else {
            chips(primaryFilters, Arrays.asList(kind.label), 0, index -> { });
        }
        chips(secondaryFilters, new ArrayList<>(), 0, index -> { });
    }

    private void request(boolean reset) {
        if (repository == null) return;
        pendingReset = reset;
        resumePending = false;
        if (reset) {
            rows.clear();
            setRows(rows);
        }
        loading((country.isEmpty() ? "" : country + " · ") + kind.label + " · 正在查找匹配内容…");
        repository.load(kind, country, reset, result -> {
            if (!isAdded()) return;
            finished();
            loadedRevision = result.revision;
            rows.addAll(result.videos);
            hasMore = result.hasMore;
            setRows(rows);
            String heading = (country.isEmpty() ? "全部地区" : country) + " · " + kind.label;
            status.setText(heading + (result.message.isEmpty() ? " · 已取得 " + rows.size() + " 项，可选片后查找版本" : "\n" + result.message));
            more.setText(rows.isEmpty() ? "继续查找" : "加载更多");
            more.setVisibility(hasMore ? View.VISIBLE : View.GONE);
        });
    }

    @Override
    public void refreshPage() {
        if (pageInitialized) request(true);
    }

    @Override
    protected void onMoreClicked() {
        if (!loading && hasMore) request(false);
    }

    @Override
    protected void onCardClicked(int position) {
        if (position >= 0 && position < rows.size()) search(rows.get(position));
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
        if (repository != null && !loading && (resumePending
                || loadedRevision >= 0 && SourceRegistry.get(mContext).snapshot().getRevision() != loadedRevision)) request(resumePending ? pendingReset : true);
    }

    @Override
    public void onDestroy() {
        if (repository != null) repository.close();
        super.onDestroy();
    }
}
