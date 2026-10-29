# 榴榴芙芙影视品牌资源

用户于 2026-10-29 从三个生成候选中选定 B：双猫电视徽章。参考素材由用户提供，原图和未选候选保留在本地交付目录，不进入公开仓库。

选中设计由专业设计师 agent 使用内置 image 工具生成并逐图检查：金色短毛绿眼猫、奶白金长毛琥珀眼猫、电视轮廓与播放键；中文品牌名准确为“榴榴芙芙影视”。

- `drawable-nodpi/liuliufufu_logo.png`：1254×1254 纯图标，无文字，深绿满铺。
- `drawable-nodpi/liuliufufu_tv_banner.png`：1672×941 横幅，近16:9；左徽章、右侧完整中文名，由 TV 启动器缩放显示。
- `drawable/liuliufufu_launcher.xml`：旧系统使用位图。
- `drawable-v26/liuliufufu_launcher.xml`：Android 8+ 使用自适应图标；背景 `#084640`，前景加12dp inset，为主要内容保留安全区。

Manifest 已指向新图标与横幅；`app_name` 和关于页同步改名，首页复用 `app_name`。包名、代码包、GitHub 仓库与发布密钥沿用，避免破坏正常升级；历史版本文件不重命名。

源图高分辨率资源保留，由 Android 加载缩放，不手工重绘生成内容。后续仍需在电视启动器确认不同 OEM 图标裁切与横幅效果。

2026-10-29 已运行 `:app:assembleJavaDebug -ProbertDebugSuffix=.debug.branding -ProbertVersionName=0.2.0-dev --no-daemon --console=plain`，构建成功（63任务，54秒）。这是隔离调试构建，未覆盖或操作正在试用的模拟器应用，未发布为完整的0.2功能版本；本轮没有为文本/资源替换新增镜像单测。

平台参考：[Android 自适应图标](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)、[TV 应用图标与横幅](https://developer.android.com/training/tv/get-started/create)。
