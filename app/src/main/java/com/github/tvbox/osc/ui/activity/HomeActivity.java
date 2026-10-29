package com.github.tvbox.osc.ui.activity;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.IntEvaluator;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.BounceInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager.widget.ViewPager;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.adapter.HomePageAdapter;
import com.github.tvbox.osc.ui.adapter.SortAdapter;
import com.github.tvbox.osc.ui.fragment.GridFragment;
import com.github.tvbox.osc.ui.fragment.BrowseFragment;
import com.github.tvbox.osc.ui.fragment.TrendingFragment;
import com.github.tvbox.osc.ui.fragment.CatalogPageFragment;
import com.github.tvbox.osc.browse.FixedNavigation;
import com.github.tvbox.osc.ui.fragment.UserFragment;
import com.github.tvbox.osc.ui.tv.widget.DefaultTransformer;
import com.github.tvbox.osc.ui.tv.widget.FixedSpeedScroller;
import com.github.tvbox.osc.ui.tv.widget.NoScrollViewPager;
import com.github.tvbox.osc.ui.tv.widget.ViewObj;
import com.github.tvbox.osc.util.AppManager;
import com.github.tvbox.osc.util.FastClickCheckUtil;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.HomeClock;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class HomeActivity extends BaseActivity {
    private LinearLayout topLayout;
    private LinearLayout contentLayout;
    private TextView tvDate;
    private TextView tvName;
    private TvRecyclerView mGridView;
    private NoScrollViewPager mViewPager;
    private SortAdapter sortAdapter;
    private HomePageAdapter pageAdapter;
    private View currentView;
    private final List<BaseLazyFragment> fragments = new ArrayList<>();
    private boolean isDownOrUp = false;
    private boolean sortChange = false;
    private int currentSelected = 0;
    private int sortFocused = 0;
    public View sortFocusView = null;
    private final Handler mHandler = new Handler();
    private long mExitTime = 0;
    private boolean eventBusRegistered = false;
    private final Runnable mRunnable = new Runnable() {
        @SuppressLint("SetTextI18n")
        @Override
        public void run() {
            tvDate.setText(HomeClock.format(System.currentTimeMillis()));
            mHandler.postDelayed(this, 1000);
        }
    };
    private final Runnable refreshTopInfoTextSizeRunnable = new Runnable() {
        @Override
        public void run() {
            refreshTopInfoTextSize();
        }
    };
    private final Runnable refreshTopLayoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (topLayout == null || isActivityUnavailable() || currentSelected != 0 || topHide != 0) {
                return;
            }
            // 部分设备会延后应用沉浸模式，最终测量后再恢复头部可见区域。
            hideSysBar();
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) topLayout.getLayoutParams();
            params.topMargin = AutoSizeUtils.mm2px(HomeActivity.this, 10.0f);
            params.height = AutoSizeUtils.mm2px(HomeActivity.this, 50.0f);
            topLayout.setLayoutParams(params);
            topLayout.setAlpha(1.0f);
            refreshTopInfoTextSize();
            topLayout.requestLayout();
        }
    };

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_home;
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    boolean useCacheConfig = false;

    @Override
    protected void init() {
        EventBus.getDefault().register(this);
        eventBusRegistered = true;
        initView();
        useCacheConfig = false;
        Intent intent = getIntent();
        if (intent != null && intent.getExtras() != null) {
            Bundle bundle = intent.getExtras();
            useCacheConfig = bundle.getBoolean("useCache", false);
        }
        initData();
    }

    private void initView() {
        this.topLayout = findViewById(R.id.topLayout);
        this.tvDate = findViewById(R.id.tvDate);
        this.tvName = findViewById(R.id.tvName);
        this.contentLayout = findViewById(R.id.contentLayout);
        this.mGridView = findViewById(R.id.mGridView);
        this.mViewPager = findViewById(R.id.mViewPager);
        this.sortAdapter = new SortAdapter();
        this.mGridView.setLayoutManager(new V7LinearLayoutManager(this.mContext, 0, false));
        this.mGridView.setSpacingWithMargins(0, AutoSizeUtils.dp2px(this.mContext, 10.0f));
        this.mGridView.setAdapter(this.sortAdapter);
        sortAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                mGridView.post(() -> {
                    View firstChild = Objects.requireNonNull(mGridView.getLayoutManager()).findViewByPosition(0);
                    if (firstChild != null) {
                        mGridView.setSelectedPosition(0);
                        firstChild.requestFocus();
                    }
                });
            }
        });
        this.mGridView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            public void onItemPreSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null && !HomeActivity.this.isDownOrUp) {
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            TextView textView = view.findViewById(R.id.tvTitle);
                            textView.getPaint().setFakeBoldText(false);
                            if (sortFocused == p) {
                                view.animate().scaleX(1.1f).scaleY(1.1f).setInterpolator(new BounceInterpolator()).setDuration(300).start();
                                textView.setTextColor(HomeActivity.this.getResources().getColor(R.color.color_FFFFFF));
                            } else {
                                view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).start();
                                textView.setTextColor(HomeActivity.this.getResources().getColor(R.color.color_BBFFFFFF));
                                view.findViewById(R.id.tvFilter).setVisibility(View.GONE);
                                view.findViewById(R.id.tvFilterColor).setVisibility(View.GONE);
                            }
                            textView.invalidate();
                        }

                        public final int p = position;
                    }, 10);
                }
            }

            public void onItemSelected(TvRecyclerView tvRecyclerView, View view, int position) {
                if (view != null) {
                    HomeActivity.this.currentView = view;
                    HomeActivity.this.isDownOrUp = false;
                    HomeActivity.this.sortChange = true;
                    view.animate().scaleX(1.1f).scaleY(1.1f).setInterpolator(new BounceInterpolator()).setDuration(300).start();
                    TextView textView = view.findViewById(R.id.tvTitle);
                    textView.getPaint().setFakeBoldText(true);
                    textView.setTextColor(HomeActivity.this.getResources().getColor(R.color.color_FFFFFF));
                    textView.invalidate();
                    MovieSort.SortData sortData = sortAdapter.getItem(position);
                    if (!sortData.filters.isEmpty()) {
                        showFilterIcon(sortData.filterSelectCount());
                    }
                    HomeActivity.this.sortFocusView = view;
                    HomeActivity.this.sortFocused = position;
                    mHandler.removeCallbacks(mDataRunnable);
                    mHandler.postDelayed(mDataRunnable, 200);
                }
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null && currentSelected == position) {
                    BaseLazyFragment baseLazyFragment = fragments.get(currentSelected);
                    if ((baseLazyFragment instanceof GridFragment) && !sortAdapter.getItem(position).filters.isEmpty()) {// 弹出筛选
                        ((GridFragment) baseLazyFragment).showFilter();
                    } else if (baseLazyFragment instanceof CatalogPageFragment) {
                        ((CatalogPageFragment) baseLazyFragment).focusContent();
                    } else if (baseLazyFragment instanceof UserFragment) {
                        ((UserFragment) baseLazyFragment).focusMainSearch();
                    }
                }
            }
        });

        this.mGridView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            public boolean onInBorderKeyEvent(int direction, View view) {
                if (direction == View.FOCUS_UP) {
                    BaseLazyFragment baseLazyFragment = fragments.get(sortFocused);
                    if (baseLazyFragment instanceof UserFragment) {
                        refreshCurrentPage();
                        return true;
                    }
                    if (baseLazyFragment instanceof GridFragment) {
                        ((GridFragment) baseLazyFragment).forceRefresh();
                        return true;
                    }
                    if (baseLazyFragment instanceof CatalogPageFragment) {
                        ((CatalogPageFragment) baseLazyFragment).refreshPage();
                        return true;
                    }
                }
                if (direction != View.FOCUS_DOWN) {
                    return false;
                }
                BaseLazyFragment baseLazyFragment = fragments.get(sortFocused);
                if (baseLazyFragment instanceof CatalogPageFragment) return ((CatalogPageFragment) baseLazyFragment).focusContent();
                if (!(baseLazyFragment instanceof GridFragment)) return false;
                return !((GridFragment) baseLazyFragment).isLoad();
            }
        });
        tvName.setOnClickListener(view -> {
            FastClickCheckUtil.check(view);
            jumpActivity(SettingActivity.class);
        });

        tvName.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                jumpActivity(SettingActivity.class);
                return true;
            }
        });
        setLoadSir(this.contentLayout);
        //mHandler.postDelayed(mFindFocus, 500);
    }

    private boolean dataInitOk = false;

    private boolean configLoading;

    private void initData() {
        if (dataInitOk) {
            showFixedPages();
            return;
        }
        if (configLoading) return;
        configLoading = true;
        tvNameAnimation();
        ApiConfig.get().loadConfig(useCacheConfig, new ApiConfig.LoadConfigCallback() {
            @Override
            public void notice(String message) {
                // 来源状态由各页面显示，不用全局弹窗阻塞免认证内容。
            }

            @Override
            public void success() {
                mHandler.post(() -> {
                    if (isActivityUnavailable()) return;
                    configLoading = false;
                    dataInitOk = true;
                    boolean pagesAlreadyShown = pageAdapter != null;
                    showFixedPages();
                    if (pagesAlreadyShown) refreshCurrentPage();
                });
            }

            @Override
            public void error(String message) {
                mHandler.post(() -> {
                    if (isActivityUnavailable()) return;
                    configLoading = false;
                    showFixedPages();
                    Toast.makeText(mContext, "默认来源暂未准备好，可稍后刷新", Toast.LENGTH_SHORT).show();
                });
            }
        }, this);
    }

    private void showFixedPages() {
        tvName.clearAnimation();
        tvName.setText(getString(R.string.app_name));
        showSuccess();
        if (pageAdapter != null) return;
        List<MovieSort.SortData> tabs = new ArrayList<>();
        for (FixedNavigation.Item item : FixedNavigation.ITEMS) tabs.add(new MovieSort.SortData(item.id, item.label));
        sortAdapter.setNewData(tabs);
        initViewPager();
    }

    private void refreshCurrentPage() {
        if (!dataInitOk) {
            initData();
            return;
        }
        if (currentSelected < 0 || currentSelected >= fragments.size()) return;
        BaseLazyFragment fragment = fragments.get(currentSelected);
        if (fragment instanceof CatalogPageFragment) ((CatalogPageFragment) fragment).refreshPage();
        else if (fragment instanceof UserFragment) ((UserFragment) fragment).refreshRecentHistory();
    }

    public boolean focusNavigationBar() {
        if (sortFocusView != null && sortFocusView.requestFocus()) return true;
        mGridView.setSelectedPosition(currentSelected);
        return mGridView.requestFocus();
    }

    private void initViewPager() {
        if (sortAdapter.getData().isEmpty()) return;
        for (FixedNavigation.Item item : FixedNavigation.ITEMS) {
            if ("my0".equals(item.id)) fragments.add(UserFragment.newInstance());
            else if ("trending".equals(item.id)) fragments.add(TrendingFragment.newInstance());
            else fragments.add(BrowseFragment.newInstance(item.kind, item.country));
        }
        pageAdapter = new HomePageAdapter(getSupportFragmentManager(), fragments);
        try {
            Field field = ViewPager.class.getDeclaredField("mScroller");
            field.setAccessible(true);
            FixedSpeedScroller scroller = new FixedSpeedScroller(mContext, new AccelerateInterpolator());
            scroller.setmDuration(300);
            field.set(mViewPager, scroller);
        } catch (Exception ignored) {
        }
        mViewPager.setPageTransformer(true, new DefaultTransformer());
        mViewPager.setAdapter(pageAdapter);
        mViewPager.setCurrentItem(currentSelected, false);
    }

    @SuppressLint("NotifyDataSetChanged")
    @Override
    public void onBackPressed() {
        if (isLoading()) {
            showFixedPages();
            return;
        }
        // 如果处于 VOD 删除模式，则退出该模式并刷新界面
        if (HawkConfig.hotVodDelete) {
            HawkConfig.hotVodDelete = false;
            if (UserFragment.homeHotVodAdapter != null) UserFragment.homeHotVodAdapter.notifyDataSetChanged();
            return;
        }

        // 检查 fragments 状态
        if (this.fragments.size() <= 0 || this.sortFocused >= this.fragments.size() || this.sortFocused < 0) {
            doExit();
            return;
        }

        BaseLazyFragment baseLazyFragment = this.fragments.get(this.sortFocused);
        if (baseLazyFragment instanceof CatalogPageFragment) {
            if (((CatalogPageFragment) baseLazyFragment).handleBack()) return;
            if (sortFocused != 0) mGridView.setSelection(0);
            else doExit();
            return;
        }
        if (baseLazyFragment instanceof GridFragment) {
            GridFragment grid = (GridFragment) baseLazyFragment;
            // 如果当前 Fragment 能恢复之前保存的 UI 状态，则直接返回
            if (grid.restoreView()) {
                return;
            }
            // 如果 sortFocusView 存在且没有获取焦点，则请求焦点
            if (this.sortFocusView != null && !this.sortFocusView.isFocused()) {
                this.sortFocusView.requestFocus();
            }
            // 如果当前不是第一个界面，则将列表设置到第一项
            else if (this.sortFocused != 0) {
                this.mGridView.setSelection(0);
            } else {
                doExit();
            }
        } else if (baseLazyFragment instanceof UserFragment && UserFragment.tvHotList != null && UserFragment.tvHotList.canScrollVertically(-1)) {
            // 如果 UserFragment 列表可以向上滚动，则滚动到顶部
            UserFragment.tvHotList.scrollToPosition(0);
            this.mGridView.setSelection(0);
        } else if (baseLazyFragment instanceof UserFragment && ((UserFragment) baseLazyFragment).handleBack()) {
            return;
        } else {
            doExit();
        }
    }

    private void doExit() {
        // 如果两次返回间隔小于 2000 毫秒，则退出应用
        if (System.currentTimeMillis() - mExitTime < 2000) {
            AppManager.getInstance().finishAllActivity();
            unregisterEventBus();
            ControlManager.get().stopServer();
            finish();
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        } else {
            // 否则仅提示用户，再按一次退出应用
            mExitTime = System.currentTimeMillis();
            Toast.makeText(mContext, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshTopInfoTextSize();
        mHandler.removeCallbacks(refreshTopInfoTextSizeRunnable);
        mHandler.postDelayed(refreshTopInfoTextSizeRunnable, 350);
        mHandler.removeCallbacks(refreshTopLayoutRunnable);
        mHandler.postDelayed(refreshTopLayoutRunnable, 450);
        mHandler.post(mRunnable);
    }


    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(refreshTopInfoTextSizeRunnable);
        mHandler.removeCallbacks(refreshTopLayoutRunnable);
        mHandler.removeCallbacks(mRunnable);
    }

    private void refreshTopInfoTextSize() {
        if (tvName == null || tvDate == null) {
            return;
        }
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.ts_30));
        tvDate.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimension(R.dimen.ts_26));
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void refresh(RefreshEvent event) {
        if (event.type == RefreshEvent.TYPE_FILTER_CHANGE) {
            if (currentView != null) {
                showFilterIcon((int) event.obj);
            }
        } else if (event.type == RefreshEvent.TYPE_HOME_SOURCE_CHANGE) {
            refreshCurrentPage();
        }
    }

    private void showFilterIcon(int count) {
        boolean visible = count > 0;
        currentView.findViewById(R.id.tvFilterColor).setVisibility(visible ? View.VISIBLE : View.GONE);
        currentView.findViewById(R.id.tvFilter).setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    private final Runnable mDataRunnable = new Runnable() {
        @Override
        public void run() {
            if (sortChange) {
                sortChange = false;
                BaseLazyFragment baseLazyFragment = fragments.get(sortFocused);
                if (sortFocused != currentSelected) {
                    currentSelected = sortFocused;
                    mViewPager.setCurrentItem(sortFocused, false);
                    changeTop(sortFocused != 0);
                    if (baseLazyFragment instanceof GridFragment && ((GridFragment) baseLazyFragment).shouldReloadOnSelect()) {
                        ((GridFragment) baseLazyFragment).forceRefresh();
                    }
                } else if (baseLazyFragment instanceof GridFragment && ((GridFragment) baseLazyFragment).shouldReloadOnSelect()) {
                    ((GridFragment) baseLazyFragment).forceRefresh();
                }
            }
        }
    };

    private long menuKeyDownTime = 0;
    private static final long LONG_PRESS_THRESHOLD = 2000; // 设置长按的阈值，单位是毫秒
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (topHide < 0)
            return false;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                menuKeyDownTime = System.currentTimeMillis();
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                long pressDuration = System.currentTimeMillis() - menuKeyDownTime;
                if (pressDuration >= LONG_PRESS_THRESHOLD) {
                    jumpActivity(SettingActivity.class);
                }else if (currentSelected >= 0 && currentSelected < fragments.size()) {
                    BaseLazyFragment page = fragments.get(currentSelected);
                    if (page instanceof CatalogPageFragment) ((CatalogPageFragment) page).focusControls();
                    else if (page instanceof UserFragment) ((UserFragment) page).focusMainSearch();
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    byte topHide = 0;

    private void changeTop(boolean hide) {
        ViewObj viewObj = new ViewObj(topLayout, (ViewGroup.MarginLayoutParams) topLayout.getLayoutParams());
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                topHide = (byte) (hide ? 1 : 0);
            }

            @Override
            public void onAnimationCancel(Animator animation) {

            }

            @Override
            public void onAnimationRepeat(Animator animation) {

            }
        });
        if (hide && topHide == 0) {
            animatorSet.playTogether(ObjectAnimator.ofObject(viewObj, "marginTop", new IntEvaluator(),
                            AutoSizeUtils.mm2px(this.mContext, 10.0f),
                            AutoSizeUtils.mm2px(this.mContext, 0.0f)),
                    ObjectAnimator.ofObject(viewObj, "height", new IntEvaluator(),
                            AutoSizeUtils.mm2px(this.mContext, 50.0f),
                            AutoSizeUtils.mm2px(this.mContext, 1.0f)),
                    ObjectAnimator.ofFloat(this.topLayout, "alpha", 1.0f, 0.0f));
            animatorSet.setDuration(200);
            animatorSet.start();
            return;
        }
        if (!hide && topHide == 1) {
            animatorSet.playTogether(ObjectAnimator.ofObject(viewObj, "marginTop", new IntEvaluator(),
                            AutoSizeUtils.mm2px(this.mContext, 0.0f),
                            AutoSizeUtils.mm2px(this.mContext, 10.0f)),
                    ObjectAnimator.ofObject(viewObj, "height", new IntEvaluator(),
                            AutoSizeUtils.mm2px(this.mContext, 1.0f),
                            AutoSizeUtils.mm2px(this.mContext, 50.0f)),
                    ObjectAnimator.ofFloat(this.topLayout, "alpha", 0.0f, 1.0f));
            animatorSet.setDuration(200);
            animatorSet.start();
        }
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
        unregisterEventBus();
        if (isFinishing()) {
            ControlManager.get().stopServer();
        }
    }

    private boolean isActivityUnavailable() {
        return isFinishing() || isDestroyed();
    }

    private void unregisterEventBus() {
        if (eventBusRegistered) {
            EventBus.getDefault().unregister(this);
            eventBusRegistered = false;
        }
    }

    private void tvNameAnimation()
    {
        tvName.clearAnimation();
        AlphaAnimation blinkAnimation = new AlphaAnimation(0.0f, 1.0f);
        blinkAnimation.setDuration(500);
        blinkAnimation.setStartOffset(20);
        blinkAnimation.setRepeatMode(Animation.REVERSE);
        blinkAnimation.setRepeatCount(Animation.INFINITE);
        tvName.startAnimation(blinkAnimation);
    }
}
