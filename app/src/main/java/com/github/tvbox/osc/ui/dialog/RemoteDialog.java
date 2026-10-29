package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.server.ControlManager;
import com.github.tvbox.osc.ui.tv.QRCodeGen;

import org.jetbrains.annotations.NotNull;

import me.jessyan.autosize.utils.AutoSizeUtils;

public class RemoteDialog extends BaseDialog {
    private ImageView ivQRCode;
    private TextView tvAddress;
    private String inputSessionUrl;

    public RemoteDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_remote);
        setCanceledOnTouchOutside(false);
        ivQRCode = findViewById(R.id.ivQRCode);
        tvAddress = findViewById(R.id.tvAddress);
    }

    @Override
    protected void onStart() {
        super.onStart();
        refreshQRCode();
    }

    @Override
    protected void onStop() {
        if (inputSessionUrl != null) {
            ControlManager.get().closeInputSession(inputSessionUrl);
            inputSessionUrl = null;
        }
        super.onStop();
    }

    private void refreshQRCode() {
        try {
            inputSessionUrl = ControlManager.get().openInputSession();
            tvAddress.setText("在同一网络下，用手机扫码输入片名\n二维码5分钟内有效，离开页面即失效");
            ivQRCode.setImageBitmap(QRCodeGen.generateBitmap(inputSessionUrl,
                    AutoSizeUtils.mm2px(getContext(), 240), AutoSizeUtils.mm2px(getContext(), 240)));
        } catch (RuntimeException ignored) {
            inputSessionUrl = null;
            ivQRCode.setImageBitmap(null);
            tvAddress.setText("手机辅助输入暂不可用，请返回后重试");
        }
    }
}
