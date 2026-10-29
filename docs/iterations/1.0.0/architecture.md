# 榴榴芙芙影视 1.0.0 迭代技术方案

状态：**阶段2架构候选 v1，等待独立审校**。日期：2026-10-29。本文不代表运行代码已经实现，也不代表正式验收通过。

产品基线为用户明确确认的[1.0.0产品需求v1](product-requirements.md)，原确认稿快照 `40d38d3884fd85c67fc0aa7205301a804b98cee8`，SHA-256 `acdcc0192490140c42b02cc8bf0ff323d1cce2646b63bee93da58146972c66f5`。实现不得静默降低20部固定片单、至少16部正确版本候选、同片单12部各至少3分钟实播、玩偶连续60分钟、会话跨日观察24小时等已确认门槛。阶段2独立审校可行后进入阶段3；改变P0、费用或设备/交付前提时，带具体影响和替代回用户。

## 1. 总体决定与事实边界

继续使用现有 Java/TVBoxOS 基座、播放器、Room历史、遥控组件、VersionPolicy和SubtitleTrial，不重写播放器、不迁移应用包名、不增加用户必须安装的运行模块或家庭服务。保持 Gradle 7.5、AGP 7.2.2、JDK17、Java8源码兼容、compileSdk33、targetSdk28、minSdk19与ARM32/64单APK；支持范围以实际电视验证为准，不从minSdk推导全机型兼容。

1. **玩偶优先采用应用内可审查适配器与自主管理会话的路线**：匿名浏览/搜索/详情只提取作品与分享描述，用户选中网盘线路后才授权、转存和取播放地址。饭太硬作为精选站点组合与更新线索，不要求执行其原版全部插件。实际饭太硬共享Guard路线单独保留研究入口，不能靠MD5、类名匹配或只选一个站就直接放行。
2. **共存不复用“顺序导入两份配置”**：将CMS与精选站点目录分别规范化、校验、缓存，再合并为不可变来源快照。固定导航、DNS、解析器和手机输入规则由应用拥有，第三方目录不能覆盖。
3. **统一导航与播放来源解耦**：一级十项保持用户顺序；浏览按类型/国家解析叶子分类与分页；选片后用统一作品身份检索可播放版本，保留质量更好的候选与免认证后备。
4. **热播独立取元数据**：使用可核查的五品类热度参考，分开标来源、指标和日期，不混合不同机构分值，不用站点点击量或手工片单冒充榜单。具体数据合同和访问边界须有本轮证据，见第7节。
5. **朋友分享的正式边界**：不预置账号，不把个人会话放公共配置/仓库/日志；只在候选包通过已确认验收后发布1.0.0，同签名和数据保持必须实测。

### 当前源码与必须改变的位置

| 源码事实 | 不能沿用的行为 | 改造决定 |
| --- | --- | --- |
| CatalogPolicy只允许type1、最多16站、无spider/jar/ext；BuiltinCatalog单目录/单缓存 | 把饭太硬49站直接交给该校验器，或为兼容任意放开字段 | 增加版本化受控描述符与已编入适配器白名单，保留现有CMS策略 |
| ApiConfig.parseJson先resetConfigData，再设置全局spider、解析器、hosts、rules、DNS/DOH | 依次parse两份配置会覆盖；新增目录可改变旧来源行为 | SourceRegistry先合并数据，ApiConfig仅应用一次受控快照；不接受第三方全局设置 |
| HomeActivity以dataInitOk与jarInitOk作为加载首页前置 | 一个新增插件卡住整个应用与旧CMS | CMS/内置描述符就绪即显示首页；适配器按站点懒加载并单独失败 |
| SourceViewModel.getList依赖全局homeSource，列表回调没有完整代际 | 聚合浏览/切地区时旧结果串入新列表 | 独立BrowseRepository/BrowseViewModel，查询与响应带来源、游标、请求代际 |
| JarLoader使用应用Context、同进程DexClassLoader和直接日志；代理可fallback到recent/其他loader | 精选站不等于沙箱；并发时不能猜当前代理来源 | 正式主路径使用本地适配；每个播放/代理固定完整运行身份，缺失时拒绝而非跨源回退 |
| Room数据库version1，历史键为sourceKey+vodId；旧迁移注册被注释 | 同签名不自动保证数据迁移；不能更换数据库名或破坏性重建 | 本轮保留现有Room表、文件名与旧sourceKey；新增状态用独立版本化私有文件 |

## 2. 可行性证据与开发门槛

下表区分“有实现输入”与“真实账号/媒体已通过”。阶段2不提前要求完成阶段3的全部正式片单、真机、60分钟和跨日测试，但必须有具体可开发依赖。

| 项目 | 本轮可复核证据 | 判定与边界 |
| --- | --- | --- |
| 饭太硬目录 | 匿名静态解包当前入口得到49个type3站，包括csp_WoGGGuard；并非CMS | 可实现配置数据解码/精选映射；不等于任意插件可执行或已可播 |
| 饭太硬制品 | 外层ZIP含小DEX、arm32/64 SO和受保护负载；接口描述符部分对应当前Spider/ProtectedInitJar | 不作为唯一正式路径。保护层实际网络、凭据、文件行为及分发许可未建立充分证据，不自动执行或连真实账号 |
| 独立玩偶元数据 | 公开索引提供wogg.live等候选。虽标题含维护中，实际有导航、128唯一详情；选定详情有Quark/Baidu分享按钮；“老友记”搜索有6个结果卡 | 有本地适配开发输入，不能因标题放弃；只证明匿名元数据和分享描述存在，不证明分享有效或无广告 |
| 网盘协议输入 | 固定参考mymine/CatVodSpider@b2d12690ad02383d008b12509a4db65e0367c246可读Quark/UC取码、轮询、会话、转存和播放流程 | 自主管理的受控实现可开发；真实扫码、权益、播放、会话恢复留阶段3本人授权后验证 |
| 云盘旧实现缺陷 | 详情期可强制登录、手填Cookie分支、任意异常清Cookie、finally无限等待、清理原有TV目录及凭据日志 | 不整套复制；按本方案拆分状态机、错误、文件账本与日志。这是参考fork的事实，不推断饭太硬Guard也如此 |
| 五品类热榜 | 本轮匿名读取骨朵已观察枚举的日榜JSON取得剧/综艺/动漫/网络电影真实条目；百度电影榜有纯JSON数据；红果短剧有20条SSR数据且有AppStore发布者反链 | 有明确端点、枚举、结构、时间与解析输入，见第7节；没有执行脚本、登录或绕限制。可审开发设计，仍不等于正式候选实时取数/长期稳定/任意复用许可已验收 |

参考来源：[Wogg](https://github.com/mymine/CatVodSpider/blob/b2d12690ad02383d008b12509a4db65e0367c246/app/src/main/java/com/github/catvod/spider/Wogg.java)、[Cloud](https://github.com/mymine/CatVodSpider/blob/b2d12690ad02383d008b12509a4db65e0367c246/app/src/main/java/com/github/catvod/spider/Cloud.java)、[QuarkApi](https://github.com/mymine/CatVodSpider/blob/b2d12690ad02383d008b12509a4db65e0367c246/app/src/main/java/com/github/catvod/api/QuarkApi.java)、[UCApi](https://github.com/mymine/CatVodSpider/blob/b2d12690ad02383d008b12509a4db65e0367c246/app/src/main/java/com/github/catvod/api/UCApi.java)。仓库根MIT许可可读，但直接复用前逐文件核查继承来源、保留版权；无法确认的代码不直接打入正式分发包。

本轮详细事实与工具边界见[玩偶/饭太硬可行性报告](wogg-feasibility.md)与[五品类热播可行性报告](trending-feasibility.md)。报告中的未测状态不因本技术方案形成而自动转为通过。

## 3. 模块、接口与职责

保持app/player/quickjs现有工程边界；新逻辑放app内的小包，不创建独立安装模块。下列为内部契约草案，开发首批先冻结模型与错误，不增加万能框架。

| 模块与拟建路径 | 单一职责及主要接口 |
| --- | --- |
| catalog/SourceRegistry、SourceDescriptor、SourceSnapshot | snapshot()返回带revision的不可变来源集合；合并旧CMS与精选来源，保持旧ID，管理能力与启停，不持有账号 |
| catalog/CatalogV2Policy、CatalogProfileStore | 规范化目录/字段白名单/各profile的AtomicFile与最近可用版本；不执行代码、不修改网络和导航 |
| source/AdapterRegistry、SourceAdapter、NativeSpiderBridge | 以固定adapterId选择APK内实现；home/browse/search/detail/resolve返回统一模型；桥接现有Spider JSON，异常转领域错误，不改变全局Spider checked-throws契约 |
| source/RequestContext、AdapterRuntime | 每站请求身份、懒初始化、取消、超时、并发配额及运行状态；不使用currentHome/recent猜请求来源 |
| browse/BrowseCategoryPolicy、CountryMatcher、BrowseRepository | 统一类型/国家映射与每叶子独立分页，严格复核响应，不承担账号和播放器职责 |
| trending/TrendingRepository、ProviderAdapter、TrendingStore | 五品类榜单获取、结构校验、来源周期、缓存与过期状态；只产生作品发现条目 |
| cloud/CloudSessionManager、SessionStore、QrLoginCoordinator | 按provider/account管理会话和可取消扫码；只在播放选择阶段授权，区分网络与鉴权错误 |
| cloud/ShareDescriptor、TransferService、TransferLedger | 分享树/季集识别、应用独占文件夹与精确文件ID转存账本；不接管用户网盘管理 |
| playback/PlaybackResolver、MediaLease、ProxyRouter | 将明确版本解析为可播放租约；携带不可变来源/账号/代际，处理媒体URL更新与Range代理，不输出秘密 |
| ui/固定HomePage、BrowseFragment、TrendingFragment、授权页 | 懒加载页面、遥控焦点、加载/空/错误/取消；复用UserFragment历史和已有详情播放，不在UI解析外部目录 |

核心接口所需字段：

- `RequestContext`：sourceId、adapterId、sourceConfigRevision、requestGeneration、cancelToken；涉及账号/播放时增加provider、accountId、sessionGeneration、playbackGeneration、proxyLeaseId。
- `SourceResult<T>`：data或DomainError，包含上述身份，不能只返回null；错误为NETWORK、SOURCE_UNAVAILABLE、NO_MATCH、AUTH_REQUIRED、SESSION_EXPIRED、ENTITLEMENT_REQUIRED、UNSUPPORTED、CANCELLED、INVALID_RESPONSE。
- `BrowseQuery`：kind、country可空、year可空、sort、registryRevision、queryGeneration；`BrowsePage`返回条目、各叶子新游标及hasMore，不伪造全网总页数。
- `ShareDescriptor`：provider、受限的shareId/提取码、显示作品身份、原始sourceId；不是用户Cookie，不在公共日志中记录完整带提取码URL。
- `MediaLease`：sourceId/version/episode、provider/account/sessionGeneration、streamUrl与必要headers的私有持有、expiresAt可未知、proxyLeaseId；播放器和用户页面不回显凭据。

## 4. 来源共存与目录更新

### 4.1 版本化兼容

新增 `sources/v2/catalog.json` 与APK内v2目录；原 `sources/catalog.json` 和v1验证能力继续服务旧预览版，不将新schema直接推给旧客户端。首次新APK以本地目录启动，网络更新只影响下一次安全重载，不重建正在播放的运行上下文。

v2来源描述至少包含稳定sourceId、origin/originKey、adapterId、sourceConfigRevision、displayName、启停、可用能力（浏览/搜索/解析/国家过滤模式）、允许站点与受控参数、授权类型、质量证据摘要。字段按adapterId分别白名单；不得携带任意className、脚本、JAR URL、私有凭据或全局DNS/rules/parses。

保留 `robert_liangzi`、`robert_feifan`、`robert_baofeng` 原键。新增使用稳定ASCII命名空间，例如 `fty_wogg`，不能以可能变化的中文显示名作身份；同站重复条目规范化后去重，冲突拒绝，不覆盖旧站。N是通过审核的启用名单，总启用站点先维持16上限；读取饭太硬49站只是候选解析，不全开、不同时预热。

### 4.2 运行与故障

CMS与精选profile分别保存内置、有效缓存、上一有效版本和更新记录。更新：下载到临时文件 → 大小/结构/来源及单调版本校验 → 合成候选快照并验证 → AtomicFile提交；失败不触碰当前快照。目录TLS使用系统信任、不关闭校验；允许来源请求的重定向仅在受控主机规则内进行。凭据只发送到对应provider官方HTTPS域名。

首页在内置CMS/来源描述符加载完成即进入可用状态；删除对全局spider初始化的启动依赖。适配器仅在该来源被浏览、搜索或选中时初始化。每站维护UNINITIALIZED→INITIALIZING→READY或TEMP_UNAVAILABLE状态；失败有有限冷却和手动重试，CMS不等待它。

来源快照更新与当前查询/播放隔离：已在进行的操作持有旧快照及租约直至完成/取消；新查询取得新revision。来源被暂时禁用不删除历史，历史记录可显示暂不可用并按作品重新搜索。保留原sourceKey+vodId，不能把旧vodId直接指向另一来源。

`ApiConfig`作为受控快照到旧SourceBean的兼容层，不再作为外部配置合并器。应用默认网络/解析规则单独构造；饭太硬根部spider、wallpaper、hosts、rules、doh、parses、lives、广告/脚本规则均不自动继承。原Guard路径若后续通过审查，应另有精确制品版本、SHA256、审查/许可记录与回退清单，不能因作者MD5更新就执行；当前正式主路线不依赖该路径。

## 5. 统一导航、国家与分页

一级固定：首页、热播、电视剧、电影、综艺、动漫、韩剧、美剧、英剧、日剧。首屏不等网络分类响应才出现，来源切换不改变顺序。首页继续最近观看，空记录只引导搜索。四地区页二级固定电视剧/电影/综艺/动漫，使用可见且可遥控的类型栏；返回保留当前类型、列表位置与焦点。

`ContentKind`内部区分TV、MOVIE、VARIETY、ANIMATION、DOCUMENTARY、SHORT_DRAMA、UNKNOWN。纪录片和短剧继续参加全局搜索，不能因为四大浏览类型把原内容过滤掉；短剧有独立热播入口，纪录片可作为电影页子类。现有ContentPolicy及各站分类白名单继续生效。

每source先读实际分类结构，将审查过的叶子类别名称映射到类型及ID，不共享ID常量。父ID1/2/3/4返回0、多ID逗号返回全库已被证伪，必须禁止两种捷径。量子/非凡可将国家作为服务器过滤条件，但返回后仍按type和area复核；暴风的area被忽略，初版不参加国家专区请求，不以全库扫描补齐。

国家字段按完整token及固定别名匹配，例如美国/USA/United States、英国/UK/United Kingdom、日本/Japan、韩国/South Korea。合拍可进入多个适当专区并显示完整地区；“日韩/欧美”等不可分的混合标签不能直接判单一国家，“美国萨摩亚”不能因子串匹配进入美国。缺类型、缺国家、错误响应记录为未知，不混入目标列表。

每个 `(sourceId, leafId, country, year, sort)` 独立游标；一次界面翻页有限并发请求，默认最多3个浏览请求在途，取得约20个有效条目或本轮预算耗尽即返回已得数据。调度在可用叶子/来源间轮转并保存下一起点，不让前3叶子永远吃满首屏而其他类型饥饿。每轮总请求/耗时预算在A批冻结并用慢源、空页夹具验证。去重按已确认作品身份，未知年份/季集不盲目合并。页内未命中不等于全部耗尽，有界续取后可显示“当前未取得匹配内容”，不得宣称不存在。长时间缺某地区×类型成功样本时，按产品覆盖缺口回报。

所有异步响应必须匹配queryGeneration及registryRevision；切页、切地区、切类型、下拉刷新或离开页面时取消旧请求并使代际失效。禁止旧listResult的晚到结果覆盖新筛选；加载/成功部分/空/错误/取消均需可恢复焦点。现有GridFragment用于传统单站内容时保留，新聚合页不通过修改全局homeSource来路由。

## 6. 玩偶、扫码、会话与临时文件

### 6.1 本地适配与明确网络边界

以固定参考快照和当前真实HTML结构独立实现Wogg首页、分类、搜索、详情；使用现有Jsoup/OkHttp/Gson，不搬入整套WebDAV/QuickJS等无关依赖。默认/备用站点来自已追溯的候选列表并校验结构、详情链接和分享域名，不以标题是否含“维护中”或“玩偶哥哥”一项决定可信或停站。

匿名详情只提取元数据和分享入口，不在详情加载时调用旧Cloud.getVod触发登录。Quark/UC官方请求边界来自已读参考：Quark取码/票据在uop.quark.cn、扫码su.quark.cn、会话pan.quark.cn、云盘drive-pc.quark.cn；UC相应为api.open.uc.cn、su.uc.cn、drive.uc.cn、pc-api.uc.cn。以上是具体实现输入，不声称真实扫码和播放已通过。百度/Ali等未知或本轮未支持的分享入口明确标不支持，不能伪装成夸克可播；若有效玩偶内容依赖尚未支持provider，作为范围/可行性问题报告。

公开分享描述在授权前可以显示，完整文件树/选集若需要登录则在用户选择该网盘线路后再按需分页获取。只遍历该share及用户主动展开的目录，不扫描用户整个网盘；每页/请求有预算和取消，不靠无界递归收全集。文件名/目录名与站点作品信息共同用于年份、季集和版本核对，信息不全时允许用户明确选择，但不能自动承诺与另一版本等价。

桥接现有Spider时，在边界内部捕获并映射异常，不向全局Spider增加checked throws。所有新增代码检查Java8/min19 API，不原样使用List.of/Map.of/streams等较新平台方法。

### 6.2 授权状态机

状态为SIGNED_OUT→QR_LOADING→QR_WAITING→AUTHORIZED；服务端二维码到期回可重取码；取消进入CANCELLED并立即取消轮询。网络故障进入RETRYABLE_NETWORK，保留已有会话；只有明确鉴权失效才进入EXPIRED。没有finally无限sleep、递归重新登录或手填Cookie入口。

同provider同账号只允许一个授权事务，UI生命周期与请求generation绑定，离开/取消后晚到轮询不能写入会话。用户只在选择明确需授权的线路时扫码，浏览、搜索、首页和免认证播放不需要登录；夸克和UC按需分别授权，可统一使用的适配器复用本应用会话，不要求每站重复扫。

现有DetailActivity默认小窗预览会在详情返回时jumpToPlay，不能让该自动路径隐式启动扫码。新增播放意图区分PREVIEW、AUTOMATIC（失败/字幕替代）与USER_INITIATED：自动预览或自动替代可使用已有效会话，但收到AUTH_REQUIRED只显示可聚焦的“授权播放”选择，不弹二维码、不让仍可用的原片因等待扫码停止；用户明确点击播放/授权或选需登录版本后才启动授权事务。扫码取消/解析失败不写成已观看；最近观看的更新时间与集数以实际开始/进度回调更新，保留旧记录不作破坏清理。

区分三个有效期：二维码ticket、provider登录session、媒体地址lease。二维码或媒体URL到期不等于登录失效；有有效session时先更新媒体地址/合法服务端会话，不能直接清Cookie要求重扫。账号权益不足单列ENTITLEMENT_REQUIRED，不能当会话过期反复登录，也不自动购买权益。观察24小时是测试窗口，不是登录有效期保证。

### 6.3 会话存储与升级

新会话放应用私有独立SessionStore，不放公共配置、Room通用Cache/Hawk cache_命名空间、外置存储或可经手机端点读取的位置。记录provider、账号标识、sessionGeneration、更新时间、服务端明确有效期（可未知）和加密载荷。有效Set-Cookie/令牌轮换成功后原子持久化，重启和正常覆盖升级复用；会话缺失/真实失效/主动退出才清理对应provider状态。

`sessionGeneration`定义为授权epoch，仅在主体改变、退出/撤销、确认失效或重新授权时递增；正常同账号Set-Cookie轮换使用独立`credentialRevision`原子更新，不使有效MediaLease全部失效。请求捕获epoch及revision，晚到401只有仍对应当前凭据版本时才可使会话失效；旧revision失败不得覆盖已更新的有效会话，可在同epoch下按预算用新凭据重试一次。敏感会话API/凭据更新串行协调，媒体代理按固定账号/epoch在每次受控请求取当前凭据，不把旧Cookie永久冻结到媒体租约。测试覆盖正常轮换不中断、旧401晚到、退出后旧成功不能复活会话。

使用AndroidKeyStore保护密钥；API23+可用系统AES-GCM，API19–22需要经测试的RSA封装独立AES及MAC密钥方案（随机IV、加密后认证），不能因旧系统不支持某安全库就改存明文或每次启动清会话。不得把加密实现写成自定义算法；使用平台密码原语并校验认证失败/损坏/密钥丢失行为。异常时仅该provider要求重授权，不破坏历史。签名、appId与存储命名保持稳定；allowBackup=false不变。

兼容分支使用KeyGenParameterSpec（API23+）与KeyPairGeneratorSpec（API18+）各自明确的实现类，类加载和调用按SDK分开，避免min19验证器提前解析高版本API。旧系统封装方案使用平台RSA密钥封装随机主密钥，再分离AES与HMAC密钥，认证覆盖版本/provider/account/IV/密文；先验认证再解密，比较使用恒定时间接口。不给“硬件保护/所有旧电视安全芯片”作未验证保证。参考[AndroidKeyStore](https://developer.android.com/privacy-and-security/keystore)，实现后的加密夹具与API19/23+运行检查是阶段3必测项。

### 6.4 转存账本

`TransferLedger`记录installationId、provider/account、远端folderId、仅本应用创建的fileId、操作ID与活动播放引用。首次需要时创建本应用独占目录，若同名目录已存在不按名称直接复用；保存返回的目录ID。切账号不能复用上一账号ID。

只按账本中的精确ID及所属目录清理本应用临时副本，不列举后整目录清空，不删除分享者原文件，不触碰用户已有TV目录。转存为幂等操作，超时结果未知时先核对本操作已创建项，不能无界重存；播放中持有lease，引用未释放不清理。无法确认归属时保留并报告，不以清理失败阻止其他来源播放。

## 7. 热播数据合同与访问边界

### 7.1 统一模型

`TrendingSnapshot`：schemaVersion、providerId、category、metricName、periodLabel、sourceDataDate/period（统计或数据归属日期）、sourceUpdatedAt及其精度（可未知）、sourceUpdatedLabel、fetchedAt、contentFirstSeenAt、contentLastChangedAt、officialUrl、entries。条目含providerSubjectId、title、year/season可未知、kind、原始rank、原始score及单位可空、官方详情URL、封面可空。只展示来源原指标；不把不同提供方的分数平均或混排成综合播放率。`DAILY.endDay`属于数据日期，不能改称真实发布/更新时刻；源只给日期/文字时保留该精度，未知的实际更新时刻明确显示未提供，获取时间单列，不能补造小时。

点击条目构造WorkQuery，再调用统一搜索和VersionPolicy核对年份/季集；不将榜单URL当媒体、不自动跳过授权/字幕信息。未知年份不能自动等同任何重名作品。

### 7.2 五品类具体取数合同

主路线为各品类的真实热度参考，页面标题必须显示所用指标与具体覆盖范围；不同机构/子榜不合成新分数。以下请求均为本轮公开匿名只读观察所得，不需要用户账号或在APK预置长期数据密钥。

| 品类 | 1.0.0主数据与取数 | 本轮实际响应与界面口径 |
| --- | --- | --- |
| 电视剧 | 骨朵日榜NETWORK_DRAMA和TV_DRAMA，作为两个可切换子榜 | 配置最新日期2026-10-27时分别20、17条；显示“骨朵网络剧热度/电视剧热度”，不自行合并重排 |
| 电影 | 具备源日期的骨朵NETWORK_MOVIE为主子榜；百度电影榜为额外参考 | 骨朵网络电影20条且有DAILY数据日期，界面明确“网络电影热度”，不冒充全部院线电影。百度纯JSON10条显示“百度电影热搜”，时间未知单列，不作为时间合同已完成的替代 |
| 综艺 | 骨朵日榜NETWORK_VARIETY和TV_VARIETY两个子榜 | 相同配置日期分别20、18条；保留网络/电视综艺范围和原始名次 |
| 动漫 | 骨朵日榜ALL_ANIME | 20条真实gdi榜位，显示骨朵动漫热度，不宣称全国播放市占 |
| 短剧 | 红果热播榜SSR中的rank_hot-drama/page数据 | 20条rankList、原始热度文字及“10月29日已更新”说明；显示“红果短剧热度”，指标为站内观看/互动综合热度，统计窗口未披露则明确标注 |

**骨朵合同**：先GET `https://d2.guduomedia.com/m/v3/billboard/configDay`，读取`DAILY.endDay`，本轮为2026-10-27，不能硬编码“今天/昨天”。再GET同host的 `/m/v3/billboard/list?type=DAILY&category=<枚举>&date=<endDay>&attach=gdi&orderTitle=gdi&platformId=0`。枚举仅取上表实际验证的六种，`ALL_DRAMA`/`ALL_VARIETY`本轮返回空，不能使用这两个快捷聚合。成功要求code=0、data为数组；提取showId/name/category/gdi/rank，保留releaseDate但不把它误当榜单日期。空数据不覆盖旧有效快照。正式页显示实际数据日期，不把10月27日伪称10月29日实时。

**百度合同**：GET [电影榜](https://top.baidu.com/board?tab=movie)，从HTML注释 `s-data` 内只解析纯JSON，取`data.curBoardName`确认电影榜、`data.cards[0].content`中的word/hotScore/index/url/img。不得eval整页脚本。本轮有10条，但没有可核实源更新时间；显示“源更新时间未提供，获取于……”并保留原指标，不将本机获取时刻伪作官方更新时间，也不由当天时钟硬猜统计日期。

**红果合同与身份**：Apple官方AppStore/iTunes记录trackId6451407032的sellerName为Beijing Bimo Liuxiang，sellerUrl `https://novelquickapp.com/hongguo`重定向到 `https://hongguoduanju.com/`；首页直接链接 [热播短剧榜](https://hongguoduanju.com/rank/hot-drama)。这是发布者反链证据，不仅是网站自称官网。匿名HTML中 `script[data-script-src="modern-run-router-data-fn"][data-fn-name="r"]` 的`data-fn-args`属性是HTML转义后的JSON数组；只接受首两项为`rank_hot-drama/page`、`content`，第三项isSuccess=true、rankList非空的记录。Jsoup解码属性后用Gson解析，提取id/seriesId/rank/title/heatText/href/cover；不执行script、runWindowFn、路由函数，不使用SSR context暴露的内部host/IP。源日期原文字保留月日精度，统计窗口未披露不捏造；获取时间单列。播放仍通过应用统一搜索，绝不把红果播放页当本项目可绕账号的媒体接口。

上述来源提供已确认的真实热度参考输入；不得把覆盖差异隐藏成“全网统一榜”，也不声称包含所有平台作品。某品类实际长期无数据或覆盖不足仍按P0缺口报告，不用永久空页代替。

对照候选：百度官方规则只覆盖电影/电视剧等6榜，不包含本需求全部五类；360kan主站本轮返回502；云合公开页未取得实际数据。爱奇艺官方首页/风云榜SSR可见真实热度和短剧热榜线索，但本轮从其已引用静态客户端文件观察到的 `mesh.if.iqiyi.com/portal/pcw/rankList/comRankList`，按官方默认/客户端server参数匿名请求都只返回30字节空items，不能据路径存在称接口已可用。没有eval或执行这些脚本，也不通过账号、验证码或指纹伪造补数据。

这些是已核实的公开匿名读取路径，缺少文档化开放API不自动等于无法开发，也不等于可任意用途再分发。适配器记录实际访问边界和来源说明、尊重服务限制、不绕登录/验证码/付费/访问控制；仅低频消费必要标题/榜位/指标等元数据，不分发整页文章或视频，不以AGPL客户端许可代替第三方数据条件。若提供方后续关闭访问或要求新增付费/秘密服务，先保留可核查缓存并报告具体替代，不在APK写长期密钥，不静默改成假榜或要求用户配置数据服务。

### 7.3 缓存与失效

每provider/category独立AtomicFile，网络请求异步且有界；默认软缓存6小时，失败15分钟退避，同一份缓存刷新互斥。刷新下载→结构/日期/类型/非空/重复校验→原子替换，失败保留上一有效快照。新鲜度优先使用源数据日期/更新时间，而不是fetchedAt：成功GET同一旧日榜不能续命。源只提供月日时保留原文字和精度；源时间未知时记录规范化内容指纹、firstSeenAt和lastChangedAt，反复取得相同内容不重置首次观察时间，不将变化观察时间冒作官方更新时间。源日期已知且超过7天、或未知日期且同一内容已观察超过7天时，标为历史参考，不作为默认近期榜；无历史数据明确空态和重试。夹具必须覆盖“刚获取但源数据很旧”及“未知源日期重复GET不续命”。不能以缓存永久替代近期榜，也不能把五个空页当正式验收完成。更新失败不影响首页/搜索/播放。

请求只对已确认入口与参数，不扫描网站、执行网页脚本或下载远端适配程序。开发测试以合成结构夹具验证异常/日期/类型，实际取数证据单独留脱敏摘要和响应摘要，不把整页受保护内容打进APP。

## 7A. 具体补充来源开发候选

玩偶仍先完成核心闭环，补充来源开发名单首选 **动漫巴士Dm84**，覆盖全局动漫浏览与搜索；备选为厂长直连，但只有进一步验证当前入口/列表/搜索合同后才可进入实现，不能因旧代码存在便默认启用。

Dm84的固定参考同为mymine快照。当前匿名只读核查得到：`https://dm84.tv/`主页56张具备标题/链接/图片字段的完整卡片，4类导航；源码分类模板`/show-1--time----1.html`有36张完整卡片及3组过滤；搜索“海贼王”一次15秒超时保留为失败，唯一追加“仙逆”请求返回3张完整卡片，详情路径形如`/v/4342.html`。这给出主页/分类/搜索的可开发输入，不是媒体、字幕或广告验收。

本地Dm84实现只用Jsoup/OkHttp/Gson/现有Spider桥接，修复参考源码首页doc覆盖、分类截字、UTF8搜索及无pagecount分页处理；旧playerContent只给iframe，阶段3必须验证解析、真实起播、字幕与片中广告，不能只把iframe交给播放器就判完成。地区信息不足时不参加国家专区，不以日漫/欧美模糊标签猜单一国家。

厂长备用输入来自已追溯发布页`https://www.czzy.site/`及其实际链接，当前列表/搜索/播放合同仍需有限验证；不猜新域名、不直接使用旧默认站。Dm84若真实解析/质量或依赖许可失败，停止将它纳入优先启用名单，再审厂长等备用；若最终不能提供已确认的补充来源范围，带具体缺口回用户，不能拿旧广告CMS或名称占位凑N。最终正式启用名单需经阶段3实际样本形成，阶段2开发候选不等于已通过准入。

## 8. 搜索、播放与回退集成

保留现有搜索并发/分批/超时机制，将新SourceRegistry快照作为启用来源集合。每次搜索持有searchToken，取消、换词或离开后晚到结果丢弃；单源失败产生来源级错误，不将整个结果置空。按作品身份保留不同年份/季集，未知不自动合并。新来源质量证据与版本元数据分别展示，不因源名或4K标记直接优先。

沿用VersionPolicy对同名、年份、季集、剪辑差异的校验和SubtitleTrial事务。尝试中文替代时保留原版本的媒体地址、headers、真实游标和运行身份；只有实际进度推进才确认新版本，失败/超时恢复原先存在的可用版本。用户手动换C、换集、停止等使旧generation失效，不恢复已被用户放弃的A；全部候选失败则显示失败、返回/重试。

所有播放解析与本机代理必须固定`sourceId+adapterId+playbackGeneration+provider/account+sessionGeneration+proxyLeaseId`；禁止依赖JarLoader.recent、homeSource或currentPlaySourceKey猜路由。缺身份、过期租约、账号不匹配时拒绝，不遍历其他loader尝试。媒体URL过期可在原有效会话下更新一次并保留同版本进度；不能把刷新失败自动变成跨片换源。

播放器仍使用内置Exo/IJK已支持能力，优先检测确认的中文轨、区分来源声明/未知与硬字幕；无文字轨不等于无中文。手动选轨优先，外挂字幕按版本匹配；不加入未确认的自动翻译交付承诺。视频、声音、拖动后的持续播放和字幕证据须在最终候选包实测。

## 9. 宿主网络端点与凭据边界

当前ControlManager启动的RemoteServer是通用局域网服务，存在无统一鉴权的文件读取/上传/建删文件夹、`/cache`读写及`/action`配置推送路径。这些不是用户本轮需要的远程文件管理能力，正式版必须关掉或拒绝；不能只修云盘临时目录而继续开放宿主写文件接口。

所有HTTP路由默认拒绝，再分别允许必要静态资源、有效媒体租约、有效手机输入配对请求；不能保留一个旧路由兜底。`/file`、上传/建删文件、`/cache`、通用push和配置写操作关闭；`/media`、`/proxy`、`/proxyM3u8`、`/dash`等已有媒体路径也必须经过统一租约检查，不能作为未鉴权旁路导出任意URL或文件。

手机辅助输入单独保留：用户在搜索页主动开启 → 生成短时随机配对能力 → QR展示本机地址 → 手机页只允许提交搜索文本/必要输入动作。关闭/超时/离开页面即失效；限制请求大小、频率和字符长度。拒绝不匹配的Host/Origin，不使用通配CORS，状态变更必须是已配对POST。禁止api/liveApi/danmuApi、通用push、cache和文件管理写动作；不在手机页或公共日志显示网盘会话。

本机媒体代理与手机输入分离。媒体代理仅loopback可达或要求强随机单播放租约能力，固定身份校验、范围化URL/Range；目标从私有租约查找，不接受调用方提交任意URL或任意文件路径。签名媒体请求凭据只发到对应官方provider允许的HTTPS主机；跨host重定向默认去除Cookie/Authorization等敏感头，不在不能确认主机归属时继续携带凭据。新上游请求拒绝本机/私网探测、非法scheme和路径穿越。入站本机播放器访问与上游目标校验分别处理，不能递归代理本机服务。旧公开CMS的HTTP兼容范围保持精确，不用于发送账号凭据。

新增代码使用领域错误和脱敏日志，不输出Cookie、ServiceTicket、二维码票据、提取码、完整签名URL或带私密内容的异常。审查并替换相关路径中直接Log/printStackTrace；发布包LOG开关和禁止备份不是唯一保护。新会话不进入可被现有/cache读取的Hawk cache_空间。若未来采用共享Guard，必须另外证明该二进制没有绕过这些边界，不能把同进程视为隔离。

## 10. 数据、升级与正式版本

本轮不改现有Room实体/数据库名/Schema版本，历史与收藏仍用旧sourceKey+vodId；无需为目录/会话/转存账本增加表。新私有JSON存储分别带schemaVersion与显式小步文件迁移，使用原子写和损坏回退。若开发发现必须改Room schema，要先给显式非破坏Migration与升级夹具，禁止fallbackToDestructiveMigration或换DB文件绕过。

preview.2→候选：验证历史、续播、已保存设置；它没有夸克/UC会话，记不适用。具备扫码能力的同签名候选A→候选B：验证有效会话、转存账本与历史保留。清数据、卸载、新appId、独立Debug首装都不能冒充覆盖升级成功。

最终appId仍为`io.github.roberbiu.roberttv`、沿用私有签名、versionName=1.0.0，versionCode至少3并高于已发布2；不使用公开测试密钥。现有B图标/横幅与HomeClock修复保留，补测不同启动器裁切、日期/星期以及搜索输入/焦点。

候选包先供已声明测试设备验收，未通过不打正式标签。冻结buildCommit、目录/适配器版本和最终二进制SHA；若验收后改代码或重构建，重新核对身份并补测影响面。正式发布提升已经验收的同一二进制，不以新构建替换未测文件；发布清单对应APK、签名摘要、buildCommit和证据记录，后补文档提交可另列reportCommit。

## 11. 实施批次与文件所有权

| 批次 | 主要交付与文件边界 | 依赖/完成条件 |
| --- | --- | --- |
| A 基础契约与宿主边界 | source/统一模型、catalog/v2与Registry；ApiConfig受控安装/移除全局Jar启动Gate；RemoteServer/Input/Cache路由限制；私有存储接口 | 先冻结接口与合成夹具，旧CMS/历史行为不退化；拒绝恶意目录/越界端点 |
| B 玩偶优先闭环 | 本地Wogg、CloudSession、扫码、转存账本、MediaLease/ProxyRouter | 先匿名浏览/搜索/详情，再本人扫码候选验播放；强制取消/超时/文件归属/日志测试。不是先做动漫站替代玩偶 |
| C 固定导航与浏览 | HomeActivity固定导航、BrowseFragment/ViewModel、分类/国家与分页策略、共同详情导航 | 依赖A来源接口，可与B并行；跨来源ID、过滤失效、旧结果、空页/去重测试通过 |
| D 五品类热播 | provider适配、TrendingRepository/Store、TrendingFragment及榜单到搜索 | 只在第7节数据合同证据明确后实施；五类实际结果、日期/指标显示与失败缓存通过 |
| E 补充来源与质量 | 以Dm84为具体首候选，修其当前HTML/搜索/iframe解析；厂长为需补合同验证的备选；实播广告样本后更新精选名单 | 依赖第7A输入；不能以添加数量完成，失败先停止该站准入并评估备选，旧高广告CMS只作后备；正式名单进入发布证据 |
| F 集成与正式候选 | 搜索/字幕/回退/遥控、升级、真机、固定片单、连续及跨日、签名包与说明 | 全部P0完成证据；有缺口按产品流程回报，不静默降门槛 |

多人开发按路径划分，ApiConfig/SourceViewModel/统一模型这类共享文件由一个集成负责人维护；其他实现通过已冻结接口接入，避免各agent同时重写全局状态。保留hooks、精确暂存和既有main，不新建未经要求的分支或改历史。

## 12. 测试与发布证据

1. 纯策略：目录profile合并/冲突/回滚/升级，叶子映射和国家完整token，父ID/逗号退全库负例，分页去重与陈旧响应；授权错误分类、取消代际、媒体到期与会话失效区分；转存账本精确归属和错误恢复；日志脱敏与端点授权。
2. HTTP/HTML合成夹具：Wogg主页/搜索/详情/分享、各provider公开数据契约、授权轮询成功/过期/取消、网络失败不清会话、播放地址轮换、失败回退；不执行外部JS，不把私人账号/媒体写入测试资源。
3. 集成：App初装无缓存/无账号时CMS与主页可用，精选profile和适配器故障分别注入；手机输入只可搜索，文件/cache/配置推送、路径穿越、无租约代理被拒；并发搜索/播放/换账号不跨source路由。
4. 最终候选真实设备：至少雷鸟鹤6 Ultra，建议另有一台不同AndroidTV/盒子；记录设备/系统/网络/支持范围。固定20片单至少16正确候选，12实播各至少3分钟、6内容类各建议2、一次拖动后继续；玩偶代表作品连续60分钟。保持失败分母与身份核对，不临时换成易成功作品。
5. 会话跨日观察至少24小时，正常使用而非持续保活；服务端有效应复用，真实失效应能重新扫码恢复，频繁重扫如实报告；覆盖重启和具备该能力的同签名候选升级。preview.2只验其已有历史/续播。
6. 发布：JavaDebug构建/必要单测，JavaRelease独立签名，最终APK无夹具/秘密/私史，版本code/name/appId/ABI、证书和hash与manifest一致；交付安装升级、独立授权/退出、支持设备/限制、片单/故障证据、对应源码/许可/第三方声明及脱敏反馈模板。

阶段2签审应按22项追踪确认设计和依赖可开发，不以测试数量、已画页面或HTTP200代替可行性。阶段3发布门槛独立完成，不要求在实现前虚构通过记录。

## 13. 产品需求逐项追踪

| 需求 | 对应设计与开发批次 | 发布验收证据 |
| --- | --- | --- |
| V100-01 单APK开箱 | 第3/4节，A/B/F | 无缓存/账号的最终APK可浏览搜索免认证播放，无导源/模块/服务器 |
| V100-02 品牌版本 | 第10节，F | 已选B资源、名称/关于/桌面一致，1.0.0与递增code及同签名 |
| V100-03 历史首页 | 第5/10节，C/F | 最近顺序、返回刷新、空态、点击续播、升级保持 |
| V100-04 十项导航 | 第5节，C | 顺序固定、切来源不乱、焦点/返回可达 |
| V100-05 地区四类型 | 第5节，C | 严格国家/类型、合拍、缺失与长时缺口报告、分页样本 |
| V100-06 五类真实热播 | 第7节，D | 五类具体取数、真实榜位/来源/口径/周期/时间，不能永久空页 |
| V100-07 聚合搜索 | 第3/8节，A/B/F | 同词多源、单源故障不锁其他、同名季集正确、取消无晚到覆盖 |
| V100-08 来源共存 | 第4/9节，A/F | 旧key/history保持、各profile故障、无第三方全局覆盖 |
| V100-09 玩偶优先 | 第2/6节，B | 当前站点/搜索/详情、本人授权、真实玩偶版本播放 |
| V100-10 来源质量 | 第4/8/11节，E/F | 实播声画/拖动/字幕/广告样本，优先名单有证据 |
| V100-11 默认维护 | 第4/7节，A/D | 有界刷新、版本校验、损坏/断网保留最近有效，无用户重导 |
| V100-12 正确替代 | 第8节，F | 同片同集、A→B失败恢复已存在A、用户C/换集/取消、全失败提示 |
| V100-13 中文优先 | 第8节，F | 检测/声明/未知、硬字幕保守、替代与手选优先 |
| V100-14 均衡选版 | 第8节，B/E/F | 可用/流畅/画质/中文/授权可确认标签，4K不强制 |
| V100-15 扫码保持 | 第6节，B/F | 选择后才授权、取消、真实失效恢复、网络不登出、跨日/升级 |
| V100-16 个人数据 | 第6/9节，A/B/F | 无共享秘密、仅app自建文件ID、独立账号、关闭宿主危险路由 |
| V100-17 遥控输入 | 第5/9/10节，C/F | 全主链遥控/焦点、无触控笔提示、正常输入和短时手机输入 |
| V100-18 北京时间 | 第10节，F | 既有HomeClock测试+候选跨日/年份及设备GMT显示 |
| V100-19 同签名升级 | 第10节，F | 旧历史/续播、具备扫码候选的有效会话，未有数据记N/A |
| V100-20 失败可恢复 | 第3/6/7节，A/B/C/D/F | 分级错误、有限重试/取消/返回、无永久loading和错误登录墙 |
| V100-21 正式交付 | 第10/12节，F | 已验收同一APK、签名/hash/source、支持设备/限制、说明/许可证 |
| V100-22 条件直播 | 维持现隐藏入口，F | 低成本且频道与切台实测后才纳入；否则说明不含直播，不展示坏入口 |

## 14. 独立审校结论入口

主架构不自行签核。提交独立审校时附上：产品确认记录、本轮Wogg/FAT可行性事实、五品类热榜数据合同证据、实际源码风险与模块/文件计划、迁移和验收追踪表。本轮已建立可开发的站点/扫码参考协议和五品类公开元数据输入，仍需独立审校确认设计可行；保护Guard运行、外部协议真实账号实播及长时/跨日测试没有被伪装为通过，它们按主路线选择或阶段3发布门槛分别处理。
