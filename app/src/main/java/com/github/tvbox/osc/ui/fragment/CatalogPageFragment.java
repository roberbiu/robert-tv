package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.ui.activity.FastSearchActivity;
import com.github.tvbox.osc.ui.activity.HomeActivity;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.adapter.CatalogWorkAdapter;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import java.util.ArrayList;
import java.util.List;

/** 新浏览页共用电视卡片、可见状态与焦点恢复，不参与播放器或账号处理。 */
public abstract class CatalogPageFragment extends BaseLazyFragment {
    protected LinearLayout primaryFilters;
    protected LinearLayout secondaryFilters;
    protected TextView status;
    protected TextView refresh;
    protected TextView more;
    protected TextView searchAction;
    private TextView information;
    protected TvRecyclerView grid;
    protected CatalogWorkAdapter adapter;
    protected boolean loading;
    protected boolean pageInitialized;
    private int selectedPosition;
    private V7GridLayoutManager gridLayoutManager;
    private String fullStatusDetails = "";
    private AlertDialog informationDialog;

    public interface ChipAction {
        void select(int index);
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_catalog_page;
    }

    @Override
    protected void init() {
        primaryFilters = findViewById(R.id.catalogPrimaryFilters);
        secondaryFilters = findViewById(R.id.catalogSecondaryFilters);
        status = findViewById(R.id.catalogStatus);
        refresh = findViewById(R.id.catalogRefresh);
        more = findViewById(R.id.catalogMore);
        searchAction = findViewById(R.id.catalogSearch);
        information = findViewById(R.id.catalogInfo);
        grid = findViewById(R.id.catalogGrid);
        gridLayoutManager = new V7GridLayoutManager(mContext, 5);
        grid.setLayoutManager(gridLayoutManager);
        adapter = new CatalogWorkAdapter();
        grid.setAdapter(adapter);
        grid.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) fitCards();
        });
        adapter.setOnItemClickListener((quickAdapter, view, position) -> {
            FastClickCheckUtil.check(view);
            onCardClicked(position);
        });
        grid.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View view, int position) {
                view.animate().scaleX(1).scaleY(1).setDuration(150).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View view, int position) {
                selectedPosition = position;
                view.animate().scaleX(1.04f).scaleY(1.04f).setDuration(150).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View view, int position) {
            }
        });
        grid.setOnInBorderKeyEventListener((direction, view) -> {
            if (direction == View.FOCUS_UP) return focusControls();
            if (direction == View.FOCUS_DOWN && more.getVisibility() == View.VISIBLE) return more.requestFocus();
            return false;
        });
        refresh.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            refreshPage();
        });
        more.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            onMoreClicked();
        });
        searchAction.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            jumpActivity(SearchActivity.class);
        });
        information.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            if (fullStatusDetails.isEmpty() || mActivity == null || mActivity.isFinishing() || mActivity.isDestroyed()) return;
            informationDialog = new AlertDialog.Builder(mContext).setTitle("来源与榜单信息")
                    .setMessage(fullStatusDetails).setPositiveButton("关闭", (dialog, which) -> dialog.dismiss()).create();
            informationDialog.setOnDismissListener(dialog -> {
                informationDialog = null;
                if (currentVisibleState && getUserVisibleHint() && information.getVisibility() == View.VISIBLE) information.requestFocus();
            });
            informationDialog.show();
        });
        View.OnKeyListener actionKeys = (view, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (findViewById(R.id.catalogSecondaryScroll).getVisibility() == View.VISIBLE) return focusBar(secondaryFilters);
                return focusContent();
            }
            if (key == KeyEvent.KEYCODE_DPAD_UP) return focusNavigation();
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && view == refresh) return focusBar(primaryFilters);
            return false;
        };
        refresh.setOnKeyListener(actionKeys);
        more.setOnKeyListener(actionKeys);
        searchAction.setOnKeyListener(actionKeys);
        information.setOnKeyListener(actionKeys);
        pageInitialized = true;
        initPage();
    }

    protected abstract void initPage();
    public abstract void refreshPage();
    protected abstract void onMoreClicked();
    protected abstract void onCardClicked(int position);

    protected void chips(LinearLayout bar, List<String> labels, int selected, ChipAction action) {
        View previouslyFocused = bar.findFocus();
        bar.removeAllViews();
        if (bar == secondaryFilters) findViewById(R.id.catalogSecondaryScroll).setVisibility(labels.size() > 1 ? View.VISIBLE : View.GONE);
        for (int index = 0; index < labels.size(); index++) {
            final int position = index;
            TextView button = new TextView(mContext);
            button.setText((index == selected ? "● " : "") + labels.get(index));
            button.setTextColor(index == selected ? 0xffffdfa8 : Color.WHITE);
            button.setTextSize(bar == secondaryFilters ? 15 : 16);
            button.setGravity(Gravity.CENTER);
            button.setFocusable(true);
            button.setFocusableInTouchMode(true);
            button.setBackgroundResource(R.drawable.shape_user_focus);
            button.setPadding(dp(10), 0, dp(10), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
            params.setMargins(0, 0, dp(6), 0);
            bar.addView(button, params);
            button.setOnClickListener(view -> {
                FastClickCheckUtil.check(view);
                if (!loading || position != selected) action.select(position);
            });
            button.setOnKeyListener((view, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) {
                    if (bar == secondaryFilters) return focusBar(primaryFilters);
                    return focusNavigation();
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (bar == primaryFilters && findViewById(R.id.catalogSecondaryScroll).getVisibility() == View.VISIBLE)
                        return focusBar(secondaryFilters);
                    return focusContent();
                }
                if (bar == primaryFilters && key == KeyEvent.KEYCODE_DPAD_RIGHT && position == labels.size() - 1)
                    return refresh.isEnabled() ? refresh.requestFocus() : searchAction.requestFocus();
                return false;
            });
        }
        if (previouslyFocused != null && selected >= 0 && selected < bar.getChildCount()) bar.getChildAt(selected).requestFocus();
    }

    private boolean focusBar(LinearLayout bar) {
        if (bar.getChildCount() == 0) return false;
        for (int index = 0; index < bar.getChildCount(); index++) {
            TextView button = (TextView) bar.getChildAt(index);
            if (button.getText().toString().startsWith("● ")) return button.requestFocus();
        }
        return bar.getChildAt(0).requestFocus();
    }

    public boolean focusControls() {
        if (!pageInitialized) return false;
        if (findViewById(R.id.catalogSecondaryScroll).getVisibility() == View.VISIBLE && focusBar(secondaryFilters)) return true;
        if (focusBar(primaryFilters)) return true;
        return refresh.requestFocus();
    }

    public boolean focusContent() {
        if (!pageInitialized) return false;
        if (adapter.getData().size() > 0 && grid.getVisibility() == View.VISIBLE) return grid.requestFocus();
        if (more.getVisibility() == View.VISIBLE && more.isEnabled()) return more.requestFocus();
        return refresh.isEnabled() ? refresh.requestFocus() : searchAction.requestFocus();
    }

    protected boolean focusNavigation() {
        return mActivity instanceof HomeActivity && ((HomeActivity) mActivity).focusNavigationBar();
    }

    public boolean handleBack() {
        if (!pageInitialized || rootView == null || !rootView.hasFocus()) return false;
        if (grid.hasFocus() && grid.canScrollVertically(-1)) {
            grid.scrollToPosition(0);
            return true;
        }
        return focusNavigation();
    }

    protected void loading(String message) {
        loading = true;
        showStatus(message, "");
        refresh.setEnabled(false);
        more.setEnabled(false);
    }

    protected void finished() {
        loading = false;
        refresh.setEnabled(true);
        more.setEnabled(true);
    }

    protected void setRows(List<Movie.Video> rows) {
        boolean restoreFocus = grid.hasFocus();
        adapter.setNewData(new ArrayList<>(rows));
        grid.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        if (rows.isEmpty()) selectedPosition = 0;
        else if (restoreFocus) {
            selectedPosition = Math.min(selectedPosition, rows.size() - 1);
            grid.setSelection(selectedPosition);
            grid.requestFocus();
        }
        grid.post(this::fitCards);
    }

    private void fitCards() {
        if (grid == null || adapter == null || grid.isComputingLayout()) {
            if (grid != null && grid.isComputingLayout()) grid.post(this::fitCards);
            return;
        }
        int width = grid.getWidth() - grid.getPaddingLeft() - grid.getPaddingRight();
        int height = grid.getHeight() - grid.getPaddingTop() - grid.getPaddingBottom();
        if (width <= 0 || height <= 0) return;
        int columns = Math.max(3, Math.min(5, width / Math.max(1, dp(145))));
        if (gridLayoutManager.getSpanCount() != columns) gridLayoutManager.setSpanCount(columns);
        adapter.fitViewport(width, height, columns, getResources().getDisplayMetrics().density);
    }

    /** 两行概览保留常用信息；完整原始口径通过可遥控打开的说明呈现。 */
    protected void showStatus(String summary, String fullDetails) {
        status.setText(summary);
        fullStatusDetails = fullDetails == null ? "" : fullDetails;
        status.setContentDescription(fullStatusDetails.isEmpty() ? summary : fullStatusDetails);
        information.setVisibility(fullStatusDetails.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onFragmentPause() {
        if (informationDialog != null) informationDialog.dismiss();
        super.onFragmentPause();
    }

    protected void search(Movie.Video work) {
        if (work == null || work.name == null || work.name.trim().isEmpty()) return;
        Intent intent = new Intent(mContext, FastSearchActivity.class);
        intent.putExtra("title", work.name);
        intent.putExtra("expected_year", work.year);
        intent.putExtra("expected_type", work.type);
        intent.putExtra("expected_area", work.area);
        intent.putExtra("picture", work.pic);
        startActivity(intent);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
