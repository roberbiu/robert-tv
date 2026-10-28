# 发布签名

Robert影视使用项目自己的发布密钥，不使用上游仓库中的公开测试密钥。

本机签名位于 `.signing/`，该目录已加入 Git 忽略规则。`release.properties` 包含以下键：

~~~properties
storeFile=.signing/robert-tv.jks
storePassword=本机保存的密码
keyAlias=robert-tv
keyPassword=本机保存的密码
~~~

上面的文字不是有效密码。不要将实际文件、密钥或密码上传仓库、聊天或问题反馈；不要把它们放入 APK 交付目录。

发布命令：

~~~powershell
& "./gradlew.bat" :app:assembleJavaRelease -ProbertVersionName=0.1.0 --no-daemon --console=plain
~~~

构建完成后必须使用 Android SDK 的 `apksigner verify --verbose --print-certs` 检查签名，并核实包名、版本和 ARM 架构。构建成功不替代安装和播放验收。

后续升级必须保留同一密钥，并递增 `app/build.gradle` 的 `versionCode`。丢失发布密钥后无法直接覆盖升级已安装的同包应用，因此需在本机外另行保管安全备份，备份不进入 Git。

CI 目前只构建调试包，不持有发布密钥。调试包和发布包签名不同；不要把调试包当作用户家中安装的正式交付包。
