# 1.0.0 开发公共契约（A批）

本文件是开发协作接口，不代表功能或正式版验收已经通过。基础模块复用现有 `Spider` JSON协议，不增加一套镜像站点接口；账号、媒体和UI由各自模块负责。

## 来源快照

- `SourceRegistry.get(Context).loadLocal(): SourceSnapshot`：由ApiConfig后台安全加载，仅磁盘/内置资源，无网络/登录。
- `SourceRegistry.snapshot()`：读取当前不可变快照，未加载时为空revision0；`refreshIfDue()`只写未来加载的缓存，不热替换快照。
- `SourceSnapshot.getRevision(): long`、`getSources()`：后者仅启用来源，按明确priority排序；`getAllSources()/getSource(id)`含禁用来源供历史状态解释。
- `SourceDescriptor`：`getId()/getSourceId/getAdapterId/getConfigRevision/getPrimaryEndpoint/getSites/getAlternateEndpoints/getCategories/getAuthorization/getCountryFilterMode/supportsServerCountryFilter/getPriority/getQualityStatus/getTimeoutSeconds`。
- `getSourceBean()`返回防御副本。旧CMS键不变，新站`fty_wogg/fty_dm84`，api为`builtin:wogg/builtin:dm84`，type3；ext只有sites/sourceId/configRevision，不含凭据。
- native站点主备URL来自本项目自管v2目录（HTTPS公开域名根地址），不直接继承饭太硬spider/全局参数。新增域名由维护目录版本审查，HTTP客户端仍须按该descriptor主机清单处理重定向。

## 本地适配与请求

- `AdapterRegistry.get().register("wogg"或"dm84", Factory)`，`Factory.create(Context applicationContext, SourceDescriptor): Spider`；在App启动注册一次，禁止同名覆盖。init只能设置上下文和配置，不能网络、弹UI或登录。
- `getSpider(Context, SourceDescriptor)`适用于持有捕获快照的调用；兼容`getSpider(Context, SourceBean)`会核对当前目录身份，ApiConfig.getCSP已转入该桥且拒绝外部执行程序。
- `AdapterRuntime.get().execute(descriptor, context, Operation<T>): SourceResult<T>`是同步后台等待接口，不能UI线程调用；每站最多2任务在途/4排队，按来源timeout(5—30秒)限制，取消信号会取消Future并清理等待项。
- `RequestContext(descriptor, registryRevision, requestGeneration)`；播放时调用`.withPlayback(playbackGeneration, explicitUserAction)`，不把请求代际与跨Activity播放身份盲目混用。Getter包括sourceId、adapterId、configRevision、registryRevision、requestGeneration、playbackGeneration、isExplicitUserAction、isCancelled。
- 操作线程通过`AdapterRuntime.currentContext()`读取当前身份；finally清除。取消`context.cancel()`；HTTP操作可`onCancel(call::cancel)`并在完成时移除监听。返回后UI仍核对自己的当前generation/revision。
- `SourceResult`区分data与`DomainError`；错误code为NETWORK/SOURCE_UNAVAILABLE/NO_MATCH/AUTH_REQUIRED/SESSION_EXPIRED/ENTITLEMENT_REQUIRED/UNSUPPORTED/CANCELLED/INVALID_RESPONSE，不透传原异常/响应。AUTH_REQUIRED是无副作用结果，只有Root确认显式用户动作后启动扫码。
- `PublicNetworkPolicy.requirePublicUrl(url, allowHttp)`与`publicDns(delegate)`供外部HTTP调用复用。检查连接实际使用的DNS结果、系统TLS与重定向；此上游规则不用于本机租约入口或手机LAN配对。
- 传统type0/type1 CMS分类、列表、详情、搜索改用`source.net.CmsMetadataHttp.get(SourceBean[, timeoutSeconds])`构造OkGo请求，type4维持旧分支而受管目录不准入。该专用客户端从新Builder开始，系统TLS、实际公共DNS、直连、无Cookie/鉴权器或继承请求头；最多两次同host重定向且HTTPS不降级，响应含未知长度流最多4MiB。请求总预算5—30秒，fallback详情6秒，callStart定时取消也覆盖排队，关闭OkGo重试与磁盘缓存。仅共享dispatcher保留既有`cancelTag`，不修改媒体或手机LAN的网络规则。

## 手机输入与媒体边界

- `ControlManager.openInputSession(): String`返回完整QR地址，短时5分钟，只允许搜索文本；UI不打印日志或泄露token。
- `closeInputSession(String sessionUrl)`只关闭对应配对，旧dialog不能关新配对；在Dialog停止/关闭时调用。旧`getAddress(false)`只给裸地址，不隐式授权。
- RemoteServer仅允许有效配对的GET根页与POST `/input`；POST必须有正确Host/Origin、`X-Input-Token`、form内容类型与1—2048字节Content-Length，不接受chunked/跨源CORS。搜索文本最多160字符、20次/分钟。
- 所有旧file/cache/action/config/push/media/proxy/proxyM3u8/dash路由拒绝，无索引页兜底；旧请求处理类本身也拒绝，避免误注册。
- 媒体由B的独立loopback ProxyRouter签发租约，不依赖9978或全局recent。Root需处理VodController旧BOM和purifyM3U8裸代理转换，不能关闭旧路由后仍发原URL。

## v2目录数据

`sources/v2/catalog.json`与APK `assets/robert/v2/catalog.json`是schemaVersion2 bundle，profiles为`cms/selected`两个独立版本组。旧v1文件保留不改。每组独立AtomicFile/LKG和更新时钟；远端仅受控GitHub固定URL，无重定向/执行程序。来源ID不能在更新中静默移除，禁用保留元数据；总启用来源上限16。CMS组必须保留至少一个启用且无需账号的描述。配置内容变化须推进对应`configRevision`，同一ID不能更换adapterId，避免复用陈旧实例。

当前候选站质量均标unverified，不能因目录启用或metadata可读取宣称无广告或真实播放通过；最终优先名单必须由正式验收证据更新。
