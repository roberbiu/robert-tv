package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.search.ExpectedWorkIdentity;
import com.github.tvbox.osc.util.ImgUtil;
import com.github.tvbox.osc.util.VersionPolicy;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class FastSearchAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    private ExpectedWorkIdentity expected = ExpectedWorkIdentity.none();

    public FastSearchAdapter() {
        super(R.layout.item_search, new ArrayList<>());
    }

    public void setExpectedIdentity(ExpectedWorkIdentity expected) {
        this.expected = expected == null ? ExpectedWorkIdentity.none() : expected;
        notifyDataSetChanged();
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        SourceBean source = ApiConfig.get().getSource(item.sourceKey);
        helper.setText(R.id.tvName, VersionPolicy.displayTitle(item.name, item.year));
        helper.setText(R.id.tvSite, source == null ? "来源待确认" : source.getName());
        helper.setVisible(R.id.tvVersionMeta, true);
        String versionMeta = VersionPolicy.qualityLabel(item.name, item.note)
                + "\n" + VersionPolicy.subtitleLabel(item.name, item.note)
                + "\n" + VersionPolicy.authorizationLabel(source == null ? "unknown" : source.getAuthorization());
        TextView metadata = helper.getView(R.id.tvVersionMeta);
        metadata.setMaxLines(expected.isActive() ? 7 : 3);
        if (expected.isActive() && !"folder".equals(item.tag)) {
            ExpectedWorkIdentity.Assessment assessment = expected.assess(item.name, item.year, item.type, item.area);
            String identity = assessment.label();
            SpannableString label = new SpannableString(identity + "\n"
                    + ExpectedWorkIdentity.actualLabel(item.year, item.type, item.area) + "\n" + versionMeta);
            label.setSpan(new ForegroundColorSpan(assessment.hasDifference() ? 0xffffb3ab
                    : assessment.allKnownSame() ? 0xffcce8c5 : 0xffffdfa8), 0, identity.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            metadata.setText(label);
        } else {
            metadata.setText(versionMeta);
        }
        helper.setVisible(R.id.tvNote, item.note != null && !item.note.isEmpty());
        if (item.note != null && !item.note.isEmpty()) helper.setText(R.id.tvNote, item.note);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        if (!TextUtils.isEmpty(item.pic)) {
            ImgUtil.load(item.pic, ivThumb, AutoSizeUtils.mm2px(mContext, 10), AutoSizeUtils.mm2px(mContext, 214), AutoSizeUtils.mm2px(mContext, 280), item.name);
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
        }
    }
}
