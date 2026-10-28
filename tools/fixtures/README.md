# 播放回退回归夹具

仅用于开发者在隔离模拟器验证，不是 Robert影视用户需要配置的来源。

`video.mp4` 是本地合成的 30 秒色条与 440Hz 正弦音，640×360、H.264/AAC，没有影视片段或字幕。文件 SHA-256 为 `be796254c1a2e096dd16c005ae25eeed80e3e2e7430b4fb14a2ced8d82717e39`。

服务器仅绑定本机 `127.0.0.1:18080/18081`，有时长和请求数量上限：A 可播且没有字幕声明，B 同片同年、声明“HD中字”，但媒体固定返回 404。A 的 1080P 备注也是测试声明，不能冒充实际分辨率。

~~~powershell
python "tools/fixtures/fixture_server.py" --self-test
python "tools/fixtures/fixture_server.py" --duration 900 --max-requests 500
~~~

在另一个开发终端准备模拟器转发，并只构建独立的 Debug 包：

~~~powershell
adb -s "emulator-5554" reverse "tcp:18080" "tcp:18080"
adb -s "emulator-5554" reverse "tcp:18081" "tcp:18081"
& "./gradlew.bat" :app:assembleJavaDebug -ProbertVersionName=0.1.0-fixture -ProbertDebugSuffix=.debug.fixture -ProbertFixtureAssets=tools/fixtures/assets --no-daemon --console=plain
~~~

首次安装到没有该独立包的模拟器；不要清除或覆盖未知应用数据。打开应用，搜索“Robert自检”，选择来源 A 的版本。应观察到 A 实际播放、自动查找 B、B 媒体 404 后恢复 A。`/stats` 和受限日志提供请求次序，缓存可能减少恢复时的网络请求，因此还需同时检查界面来源、视频画面和进度。

`.debug.fixture` 命名空间的调试包只将固定测试域名映射到模拟器本机，配合 `adb reverse` 排除主机代理 Fake-IP 干扰。普通调试与发布包不改变 DNS，Release 不读取夹具资源目录。交付前仍须检查 APK 内置目录为生产 version 1，且不包含本视频。

2026-10-28 已在 API 36、arm64 翻译模拟器观察到 A → B404 → A 画面恢复。精确回拖游标、用户手动换 C 和下一集等另有单元测试，不能将一次故障测试称作全部设备/来源均已验收。
