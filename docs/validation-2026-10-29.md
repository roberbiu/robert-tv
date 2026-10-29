# 0.1.0-preview.2 反馈修复验证

日期：2026-10-29。对应版本 code 2，包名 `io.github.roberbiu.roberttv`；沿用首版私有发布签名，ARM32/ARM64 单 APK。

## 改动与边界

- 首页默认最近观看，沿用 Room 的 `updateTime desc, id desc` 顺序、既有记录与详情续播；返回时刷新，最多展示最近 20 条。
- 首次启动执行一次首页默认值迁移，之后尊重设置页选择。不更改数据库结构或删除历史，也不引入账号同步。
- 无记录显示“暂无观看记录”和“去搜索”，删除最后记录后恢复空态和遥控焦点；历史模式不回填豆瓣或站点推荐。
- 搜索输入框仅在 Android 34+ 关闭自动手写，不更改系统输入法或普通键盘/遥控器 Enter 处理。编译 SDK 仍为 33，通过反射调用公开 View API，缺失接口时保留普通输入。

Gboard 日志先记录 Robert影视的 EditorInfo，再进入 `STYLUS` 模式，与英文触控笔提示反馈相符。[Android 官方说明](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/stylus-input-in-text-fields)确认 Android 14+ 的 EditText 默认启用自动手写，可对单个输入框关闭。

## 构建与静态检查

JDK 17、Gradle 7.5、编译 SDK 33：

```powershell
& "./gradlew.bat" :app:testJavaDebugUnitTest :app:assembleJavaDebug :app:assembleJavaRelease "-ProbertDebugSuffix=.debug.feedback" "-ProbertVersionName=0.1.0-preview.2" --no-daemon --console=plain
```

- 构建成功，143 个任务，33 项 JUnit、0 失败、0 错误。
- 私有签名 v1/v2 核验通过；证书 SHA256 与 preview.1 一致：`8bc3c1c1cfb020104c8c10addc081b2775856ecd36b70d0abb0dcd2644b93e7c`。
- 发布包 versionCode 2、minSdk19、targetSdk28；检查内置目录 version 1，量子/非凡/暴风三源，无夹具视频或夹具目录。
- APK SHA256：`dc10c8bb07945ffd2636c08e74d7bde02aca347a07458947354c6b98e33730a2`，33,306,611 字节。
- 此次复用既有历史排序和续播，不为常量或 setter 新增镜像测试；交互通过模拟器单独检查。

## 模拟器验证

运行环境为 API36 手机横屏模拟器，x86_64 经 ARM64 翻译；不能代替雷鸟真机。旧会话出现黑屏且用户确认卡死并关闭后，按用户要求跳过快照冷启动，使用软件渲染；未执行清数据命令。只读会话重启后确认包名不存在，重新首次安装本轮已核验的发布 APK，未执行覆盖安装。

| 检查项 | 本轮实际结果 |
| --- | --- |
| 新会话画面 | 正常，截图已人工查看，非黑屏 |
| 首次首页 | 显示“最近观看 / 暂无观看记录 / 去搜索”，没有推荐影片 |
| 遥控空态入口 | 两次向下聚焦“去搜索”，Enter 打开搜索页 |
| 普通输入、内置字母键与触控笔动作 | 已发出输入动作，但其间画面切到播放，无法确认完整结果；不计为通过 |
| 观看后首页刷新、历史点击与顺序 | 源码复核沿用既有 Room/续播；本轮未完成交互验收，待继续试用 |

界面在验证中进入播放后停止了全部自动输入，保留运行窗口供用户操作。没有把播放截图当作搜索页证据，也没有把“未看到弹窗”当作已确认关闭手写的完整证明。此前 JDB 尝试未取得可靠的属性回读，不列为通过项。

## 未验证范围

搜索输入弹窗完整回归、观看后的首页刷新、真机遥控、硬解、可听声音、中文字幕覆盖和长时间稳定性仍待继续试用。首页升级迁移、删除最后一条后的空态和旧系统 API 分支经过源码复核，未进行覆盖安装或旧系统真机实验。首版实播与字幕回退证据见[首版验证](validation-2026-10-28.md)，不冒充本轮重新验收。
