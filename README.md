# 榴榴芙芙影视

面向电视遥控器的影视点播应用，基于 [TVBoxOS](https://github.com/q215613905/TVBoxOS) 改造。

应用原名 Robert影视。当前源码已按用户选择切换为“榴榴芙芙影视”和 B 版双猫电视徽章；包名、仓库地址与发布签名沿用。已发布的 preview.2 保留其原有名称与验证记录。

**1.0.0 正式版迭代：需求v1已由用户确认，当前进入阶段2架构方案与独立审校。** 审校可行后交开发工程师实施；目前没有发布1.0.0，也未将预览版测试视为正式验收。

[1.0.0 产品需求确认稿](docs/iterations/1.0.0/product-requirements.md) · [阶段状态](docs/iterations/1.0.0/README.md) · [前期导航与来源调研](docs/navigation-sources-plan.md) · [品牌资源说明](docs/branding.md)

**当前已发布版本：0.1.0-preview.2 试装版。** 首页默认最近观看，搜索框关闭自动触控笔手写。33 项单元测试与独立签名构建通过；本次与首版的验证范围分别记录，雷鸟鹤 6 Ultra 真机仍待安装验收。

[获取安装包](https://github.com/roberbiu/robert-tv/releases) · [安装说明](docs/install.md) · [本次验证](docs/validation-2026-10-29.md) · [首版验证](docs/validation-2026-10-28.md)

首版内置三个普通点播来源，正常使用无需导入源、填写接口、部署服务或登录网盘。专用夸克／UC 网盘适配和直播未纳入首版默认体验。

## 产品目标

在雷鸟鹤 6 Ultra 上提供安装即可使用的单 APK。用户负责浏览、搜索和观看，不需要寻找或导入来源、填写接口和字幕凭据，也不需要部署 NAS、常开电脑或家庭服务。

- 搜索电影、国产剧、美剧、综艺、动画、纪录片等点播内容。
- 首页按最近观看时间显示记录，点击可继续观看；空记录提供搜索入口。
- 自动处理常见来源变化与失败，提供同片、同季集的可用替代版本。
- 默认均衡选择，展示可确认的清晰度、中文字幕及授权要求，并允许切换。
- 中文字幕优先；没有时尝试其他版本，最终仍没有则标注并允许播放。
- 必要认证采用手机扫码，尽量保留有效会话；无需认证的内容不被登录阻断。
- 直播仅在可低成本复用并验证稳定时纳入，不阻塞点播交付。

产品目标的实际完成与验证范围见验证记录。中文字幕须区分检测确认、来源标注与未知；内容覆盖、字幕与认证持续性受外部服务影响，不承诺全片库、永久可用或永久免登录。

## 文档

- [已确认需求与验收标准](docs/requirements.md)
- [架构与来源更新边界](docs/architecture.md)
- [开发、构建和交付计划](docs/development.md)
- [协作约定](AGENTS.md)
- [安装与使用](docs/install.md)
- [当前版本验证记录](docs/validation-2026-10-29.md)

## 工程与上游

| 项目 | 取值 |
| --- | --- |
| 应用名称 | 榴榴芙芙影视 |
| GitHub 仓库 | [roberbiu/robert-tv](https://github.com/roberbiu/robert-tv) |
| 本地仓库 | C:/coding/myPro/robert-tv |
| 固定上游 | q215613905/TVBoxOS |
| 上游基线 | [ab11d289e09963a9daf65ca7f6b7a9a8cbe184e1](https://github.com/q215613905/TVBoxOS/commit/ab11d289e09963a9daf65ca7f6b7a9a8cbe184e1) |
| 许可证 | [GNU AGPL v3](LICENSE) |

保留上游历史、版权与许可声明。原始接口格式说明可查看[固定基线的 README](https://github.com/q215613905/TVBoxOS/blob/ab11d289e09963a9daf65ca7f6b7a9a8cbe184e1/README.md)。客户端许可不自动覆盖第三方来源脚本、JAR 或媒体内容；新增依赖与适配器需单独记录来源和许可。

## 开发构建

基线采用 Gradle Wrapper 7.5、Android Gradle Plugin 7.2.2、Android SDK 33，优先使用 JDK 17。先验证 Java flavor；它包含 ARM 32 位与 64 位库，关闭 ABI 分包。

在 PowerShell 中运行：

~~~powershell
Set-Location -LiteralPath "C:/coding/myPro/robert-tv"
& "./gradlew.bat" :app:assembleJavaDebug --stacktrace
~~~

发布构建目标为 :app:assembleJavaRelease，但必须先完成项目独立签名配置。不要使用上游公开测试密钥交付，不要用未签名包或 debug 构建冒充正式发布。

不要直接使用 assembleRelease 作为首版构建命令：它会包含 Python 变体；上游 Python 模块具有机器相关路径和额外依赖。完整构建说明与验证状态见[开发文档](docs/development.md)。

## 数据与交付

签名密钥、密码、Cookie、token、带凭据的播放地址和个人观看记录不得进入公开仓库。发布包应附校验值、安装升级说明、已知限制及对应源码提交；雷鸟真机安装后的问题进入修复和再次交付流程。
