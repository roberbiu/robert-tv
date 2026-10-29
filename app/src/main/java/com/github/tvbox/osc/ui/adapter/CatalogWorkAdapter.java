package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.ImgUtil;

import java.util.ArrayList;

/** 仅用于聚合分类/榜单；根据剩余视口显示完整海报和片名，不影响历史卡片。 */
public final class CatalogWorkAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    private int cardWidth;
    private int cardHeight;
    private int posterHeight;
    private int captionHeight;

    public CatalogWorkAdapter() {
        super(R.layout.item_catalog_work, new ArrayList<>());
    }

    public void fitViewport(int availableWidth, int availableHeight, int columns, float density) {
        if (availableWidth <= 0 || availableHeight <= 0 || columns <= 0) return;
        int spacing = Math.round(8 * density);
        int inset = Math.max(1, Math.round(2 * density));
        int width = Math.max(1, availableWidth / columns - spacing * 2);
        int caption = Math.round(50 * density);
        // 留出焦点缩放和上下边距，不能只让第一帧海报占满并裁掉底部标题。
        int maximum = Math.max(caption + inset + 1, (int) ((availableHeight - 16 * density) / 1.04f));
        int poster = Math.max(1, Math.min(Math.round(width * 1.3f), maximum - caption - inset));
        int height = poster + caption + inset;
        if (cardWidth == width && cardHeight == height && captionHeight == caption) return;
        cardWidth = width;
        cardHeight = height;
        posterHeight = poster;
        captionHeight = caption;
        notifyDataSetChanged();
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        if (cardWidth > 0 && cardHeight > 0) {
            ViewGroup.LayoutParams rootParams = helper.itemView.getLayoutParams();
            rootParams.width = cardWidth;
            rootParams.height = cardHeight;
            helper.itemView.setLayoutParams(rootParams);
            View poster = helper.getView(R.id.catalogPoster);
            ViewGroup.LayoutParams posterParams = poster.getLayoutParams();
            posterParams.height = posterHeight;
            poster.setLayoutParams(posterParams);
            View caption = helper.getView(R.id.catalogCaption);
            ViewGroup.LayoutParams captionParams = caption.getLayoutParams();
            captionParams.height = captionHeight;
            caption.setLayoutParams(captionParams);
        }
        helper.setText(R.id.catalogWorkTitle, item.name);
        String note = item.note == null ? "" : item.note;
        if (note.isEmpty() && item.year > 0) note = String.valueOf(item.year);
        helper.setText(R.id.catalogWorkNote, note);
        helper.itemView.setContentDescription((item.name == null ? "" : item.name) + (note.isEmpty() ? "" : "，" + note));
        ImageView image = helper.getView(R.id.catalogPoster);
        if (TextUtils.isEmpty(item.pic)) image.setImageDrawable(ImgUtil.createTextDrawable(item.name));
        else ImgUtil.load(item.pic, image, 0, cardWidth > 0 ? cardWidth : 320,
                posterHeight > 0 ? posterHeight : 420, item.name);
    }
}
