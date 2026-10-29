# 1.0.0 玩偶／饭太硬接入可行性证据

日期：2026-10-29（北京时间）。范围：有限匿名公开 GET、公开源码及本地制品的离线数据分析。**没有加载或执行插件，没有启动目标程序，没有请求网盘二维码令牌、登录用户账号、访问分享API、转存、删除文件或播放媒体。** 调查过程中没有修改应用代码、依赖、用户模拟器或系统设置，未创建发布标签；本文件由主任务保存为架构审校材料。

## 1. 给架构师的结论

1. **饭太硬不是因基座“不支持”而不可接。** 原有 `ApiConfig.loadJar`、`JarLoader` 和 `Spider` 确实有插件加载能力；本项目的 `CatalogPolicy`、自管目录与单一全局配置是当前共存改造边界。但“已有加载器”不能证明当前 Guard 制品的初始化、保护层、云盘行为和正式分发已经可接受。
2. **建议将本地可审查的 Wogg 站点适配＋由应用管理的网盘会话／文件服务作为正式版主路线。** 当前公开玩偶站点的首页、搜索、分类、详情与分享入口均有可复核元数据证据，原先标题中的“维护中”不足以认定站点停用。饭太硬配置可以作为精选站点线索，不能自动变成任意插件执行许可。
3. **当前饭太硬原版 Guard 不足以直接作为1.0.0默认可信路径。** 其实际核心实现位于保护壳／原生加载链内，当前可用工具没有获得其站点地址、登录凭据处理、文件操作范围、完整初始化行为及明确分发许可。不能用MD5相同、仅选玩偶或ARM库存在替代这些证据。
4. **独立路线已具备开始实现的具体输入，尚不等于玩偶端到端可播已通过。** Quark/UC二维码、会话轮换、转存及播放的参考流程可读，但不能原样搬用；真实扫码、服务端兼容、账号权限、声音／字幕／广告、60分钟播放和24小时跨日观察，仍须由后续受控实现与用户本人扫码验证。
5. 若架构审校要求“所选云盘协议当前实际扫码／播放已证实”才算可行，本报告不能替代该证据。需要明确早期验证门槛和停止条件，而不是把尚未登录的静态分析写成P0通过；也不需要因此静默删去玩偶或请求用户放宽需求。

## 2. 当前饭太硬配置：观察事实

- 入口：[http://www.饭太硬.cc/tv](http://www.饭太硬.cc/tv)，User-Agent `okhttp/3.12.11`。200响应20,063字节，JFIF封面＋`**`后Base64 JSON；仅数据解码，49个站点均为type3。
- 玩偶项：`key=玩偶`、`api=csp_WoGGGuard`，ext **只有 `Cloud-drive` 键**；未给 `site` 或 `siteUrl`。本轮没有读取 Cloud-drive 指向的实际文件，更没有获取任何Cookie。
- spider来源：`https://xiuxiu-pro-new.meitudata.com/posters/e05f19091b8106dd81c34becbae3ad1c.jpg`。响应实际为ZIP（`application/zip`、PK文件头），不是通过改后缀即可建立源码信任的图片。
- 制品：1,448,574字节；配置MD5与实测均为 `b55978c4fe6d12e2fea9075f17c8a67a`；SHA256 `88e67402129e7db92669fb351397ecd4d09709e6c70b577f22a19cfb0ad5f204`。校验只证明本次字节匹配，**不证明代码可信、作者身份、未来更新或许可**。

### REA离线制品证据

REA `open_binary`按真实文件头识别为ZIP。`inspect_artifact`：

- 外层证据 `ev_8d7604fab3fff088ce2942a208451a8e97c9d009f4ecfaf8a7e59c10be3c14ea`。
- inventory证据 `ev_51cc4a61570fa58e2cf57c8f87e1030a174e9a4eab65c9685143834e407b67b7`。
- manifest `agm_e766a35dcfee1dc42f28bf5063ac3f48b4525d7611effe0758216b1d22b97211`。
- 完整Evidence已导出 `rea-artifact-evidence.json`，然后关闭了REA活动目标。

| 成员 | 大小 | 本轮可确认的事实 |
| --- | ---: | --- |
| `classes.dex` | 36,972 B | DEX035，外层定义76个类；有BaseSpiderGuard、WoGGGuard、DexNative和Init |
| `assets/ftyguard_v7.so` | 81,520 B | ELF32，e_machine=40（ARM） |
| `assets/ftyguard_v8.so` | 102,440 B | ELF64，e_machine=183（AArch64） |
| `assets/ftyshinidie.guard` | 1,328,895 B | PK开头；标准ZIP解析器拒绝，内容仍不透明 |
| `META-INF/MANIFEST.MF` | 73 B | 仅清单成员，未发现外层APK Manifest或明确许可证成员 |

注意：REA依文件头将 `.guard` 标为嵌套ZIP，并未建立它的真实内容图；普通标准ZIP解析器返回BadZipFile。不能把“inventory子图无剩余unknown”误读为保护负载已审完，也不能仅凭头部断言具体加密算法。

### 接口及初始化覆盖

普通静态DEX数据解析（不是REA反编译、不是执行）观察到：

- `WoGGGuard extends BaseSpiderGuard extends com.github.catvod.crawler.Spider`。
- BaseSpiderGuard提供与现基座相符的 `homeContent(boolean)`、`categoryContent(String,String,boolean,HashMap)`、`searchContent(String,boolean[,String])`、`detailContent(List)`、`playerContent(String,String,List)`、`init(Context,String)` 方法描述符。
- DexNative含原生 `getLoader(Object):Object`、`getSpider(Object,String):Object`、`decrypt/encrypt` 等方法；外层DEX的URL字符串仅检出xmlpull.org，未从这里找到实际玩偶域名或云盘处理地址。
- 本项目 `ProtectedInitJar` 会绑定Context并反射 `DexNative.getLoader(Object)`；当前外层Init确有get/loader/init等方法。其可选replaceCloudDiskNames/startGoProxy方法本轮未出现在外层Init元数据；不能据名称匹配推出运行成功。
- 现基座 `JarLoader` 初始化在应用进程、使用应用Context，并非沙箱。只选择玩偶不会隔离共享插件初始化。

工具边界：REA Android代码工具只接独立APK，拒绝本次ZIP目标；JEB provider未配置。没有安装引擎、伪造APK、执行目标或patch保护层。分析脚本 `static_artifact_scan.py`只用标准库读取ZIP/DEX/ELF数据，其观察保存于 `static-artifact-metadata.json`。未知：保护负载、原生实现、网络行为、最小Android版本、实际兼容、凭据/文件范围、明确分发许可。

## 3. 玩偶公开站点：维护标题不能当停站证据

公开来源线索是 [mymine索引](https://github.com/mymine/CatVodSpider/blob/b2d12690ad02383d008b12509a4db65e0367c246/json/index.json) 的Wogg `ext.site`列表，不是猜域名扫描。

| 公开索引中的地址 | 本轮匿名读取 |
| --- | --- |
| `woggpan.888484.xyz` | 200，标题含“维护中”，但有8个nav-link、255个video-name、773个voddetail文本匹配 |
| `www.wogg.net` | 同样200及实质首页结构 |
| `www.wogg.live` | 同样200及实质首页结构，进一步用于下面的有限样本 |
| `woggpan.xxooo.cf` | 本机TLS连接失败，未忽略证书验证 |
| `wogg.xxooo.cf` | 本机TLS连接失败，旧Wogg源码默认这个地址不能原样采用 |

对 `www.wogg.live` 的实际元数据样本：

- 首页解析得到128个唯一 `/voddetail/...html` 地址；导航包括电影、剧集、臻彩、短漫剧、动漫、综艺、纪录片、音乐。
- 从首页观察到的 [详情 `/voddetail/132096.html`](https://www.wogg.live/voddetail/132096.html) 返回真实详情结构及4个 `data-clipboard-text` 分享入口，域名包含 `pan.quark.cn`、`pan.baidu.com`。没有访问分享链接或网盘API。
- 根据已读Wogg源码的搜索模板请求“老友记”第1页：26,256字节，6个module-search-item、6个video-serial、18个详情链接，包含搜索词，未见验证码文本。仅元数据，无媒体请求。
- 根据源码分类模板请求电影第1页：199,051字节，210个详情链接，未见验证码文本。
- 证据文件：`wogg-public-sample.json`、对应公开首页/详情/搜索/分类HTML，以及仅作匿名GET的 `probe_wogg_metadata.py`。这些原始HTML不应直接打入公开APP或替代合成测试夹具。

边界：上述证明页面可解析和存在分享入口，不证明分享有效、资源无广告、有中文、能被任何账号播放、或主力玩偶线路已满足正式验收；也不证明FAT Guard使用同一个站点地址。当前Guard的ext没有siteURL。

## 4. 独立开源Wogg参考：可复用部分与必须改造部分

审阅快照：`mymine/CatVodSpider@b2d12690ad02383d008b12509a4db65e0367c246`，HEAD提交时间2026-10-22。root [LICENSE](https://github.com/mymine/CatVodSpider/blob/b2d12690ad02383d008b12509a4db65e0367c246/LICENSE)是MIT、copyright 2024 lushunming；类另有zhixc、ColaMint、Adam、FongMi作者注释。根许可不是对整个继承链自动背书。本轮未完成全部历史来源权属核实，也未从当前FongMi主仓取得可对应这些旧文件的许可证材料。

### 可作为实现参考

- [Wogg.java](https://github.com/mymine/CatVodSpider/blob/b2d12690ad02383d008b12509a4db65e0367c246/app/src/main/java/com/github/catvod/spider/Wogg.java)：站点列表、分类分页、详情元数据和分享按钮提取。当前站点结构与其主要CSS类一致，旧默认域名与纯文本身份判断必须更新。
- [Cloud.java](https://github.com/mymine/CatVodSpider/blob/b2d12690ad02383d008b12509a4db65e0367c246/app/src/main/java/com/github/catvod/spider/Cloud.java)：按分享域名和播放标志分派Quark/UC/Ali的思路。它不是独立可用的网盘实现。
- [QuarkApi](https://github.com/mymine/CatVodSpider/blob/b2d12690ad02383d008b12509a4db65e0367c246/app/src/main/java/com/github/catvod/api/QuarkApi.java) / [UCApi](https://github.com/mymine/CatVodSpider/blob/b2d12690ad02383d008b12509a4db65e0367c246/app/src/main/java/com/github/catvod/api/UCApi.java)：取码、轮询ServiceTicket、换Cookie、分享遍历、转存、播放及Set-Cookie轮换的参考流程。

### 最低适配工作，不可原样搬用

1. **现基座签名不同**：参考Wogg/Cloud等override声明checked `throws Exception`，本项目Spider契约未声明这些异常。应在本地适配/组合层映射错误，不直接修改全局Spider接口牵连旧源。
2. **Java/API差异**：参考工程Java11、min16；有List.of/Map.of、String.join、streams等，不能由它的min声明推导本工程Java8/min19可用。采用现有Gson、OkHttp和Jsoup等最小依赖，并将新代码写成实际支持的API；不要搬入整套QuickJS/WebDAV等无关依赖。
3. **按需授权与可取消**：Wogg详情会走Cloud→Quark/UC `getVod`，缺Cookie后旧API会进入登录流程。源码默认是“输入Cookie”对话框，二维码为其分支；`initUserInfo`捕获任意异常后清缓存并重新开始，finally在Cookie空时无限sleep。这不满足无手填Cookie、网络错误不登出、可取消及无全局登录墙要求，必须重写状态机并延迟到用户选择需授权线路。
4. **会话更新落盘**：两API从缓存恢复Cookie，响应Set-Cookie时在内存更新`__puus`。不能据此声称跨升级/24小时已通过；必须将有效更新一致持久化、区分缺失/撤销/真实失效/网络故障，日志与UI不能显示凭据。
5. **文件范围**：参考实现会寻找名为`TV`的既有网盘目录，列出并删除目录项，转存后使用file/v2/play。正式实现必须创建并记录本APP独占目录ID，仅处理本APP创建并记录的精确文件ID；禁止按名称复用用户旧目录再整目录清理。
6. **凭据输出**：参考Quark和UC源码都有ServiceTicket日志/Notify，部分异常使用Cookie作为消息。必须移除和脱敏，不通过公开配置或其他使用者共享账号。上述问题是该开源参考实现的事实，**不是对饭太硬Guard行为的推断**。
7. **依赖**：除站点HTML解析，还需Cache/User/Item/ShareData、Provider API、QR渲染及轮询、播放代理/headers、字幕映射等。建议拆本地站点适配器、ProviderSession、扫码控制、临时文件账本、播放代理，不靠共享Spider单例直接持有跨用户全局状态。

### 参考源码中的网络边界（仅静态读取，未调用）

| Provider | 取码／票据来源 | 账号换会话／云盘API |
| --- | --- | --- |
| Quark | `uop.quark.cn/cas/ajax/getTokenForQrcodeLogin`、`getServiceTicketByQrcodeToken`；扫码跳转`su.quark.cn` | `pan.quark.cn/account/info`、`drive-pc.quark.cn/1/clouddrive/` |
| UC | `api.open.uc.cn/cas/ajax/getTokenForQrcodeLogin`、`getServiceTicketByQrcodeToken`；扫码跳转`su.uc.cn` | `drive.uc.cn/account/info`、`pc-api.uc.cn/1/clouddrive/` |

这些是源码中声明的域名和协议，不是本轮已证明仍兼容、获正式开放API承诺或完成用户授权的证据。没有请求其匿名二维码Token，以免把架构只读核查扩为生产认证流程。

## 5. 架构与审校应保留的判定

| 问题 | 结论类别 | 下一步门槛 |
| --- | --- | --- |
| 配置可否解包，是否含玩偶 | 已观察 | 配置规范化与来源共存仍需实现 |
| Guard外层接口和目标ABI是否部分对应 | 已观察／部分符合 | 不等于保护负载、最小API、许可或实际初始化通过 |
| Guard可否用于真实账号及正式朋友包 | 未建立充分证据 | 不能作为默认可信路线直接放行；不能绕过保护或未审代码先连账号 |
| 本地Wogg站点元数据是否能取得 | 已观察 | 可实现可测的首页/分类/搜索/详情适配，不因维护标题静默放弃玩偶 |
| 参考云盘协议是否有明确实现输入 | 公开源码可读 | 自有受控实现、合法授权扫码、分级错误/会话/文件边界验证 |
| 真实扫码及媒体播放是否通过 | 未验证 | Stage3候选包由本人扫码后验证；若失败须停止正式放行并给具体替代，不改P0 |
| 少广告、中文、60分钟、跨日和升级是否通过 | 未验证 | 使用确认的固定样本和最终候选完成产品验收 |
| 可否宣布完整1.0.0架构无条件可行 | 本报告不能单独支持 | 主架构需连同热播五类数据证据、实现计划和独立审校作结论 |

热播由主架构／root并行核查，本报告不重复抓取榜单，也不代替该P0证据。
