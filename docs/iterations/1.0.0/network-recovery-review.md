# 1.0.0 DNS 恢复独立审校

- 审查日期：2026-10-29。
- 结论：设计及已落盘实现通过本轮静态审校，已发现的响应形状 P1 已修复；已读范围内没有遗留的已确认代码阻断项，可进入统一构建和候选包验证。
- 本变更修复 Fake-IP 环境与公网校验冲突，不放宽公网限制，不改变已确认 P0、费用、设备或单 APK 交付范围，因此不增加用户架构确认步骤。
- 原架构文件未改动，SHA-256 仍为 `f821adcb60c764fa5b173a36dcbc939b4345634fb65fa307348cad6aa34547b6`。

## 审查范围与关闭依据

静态阅读 `net/PublicAddressPolicy.java`、`net/RecoveringDns.java`、`net/AliDnsHttpsResolver.java`，以及 A/B 网络边界和 CMS、热榜、在线目录、Wogg 页面、Dm84 解析、网盘协议、媒体上游共七个专用客户端的接线。未复用旧 DoH 的无界 `await` 或失败后返回 SYSTEM 结果的逻辑。

| 检查点 | 已确认实现 |
| --- | --- |
| 统一公网限制 | A/B 共同使用 `PublicAddressPolicy`，拒绝 `198.18.0.0/15`、私网、共享地址、文档地址、组播及受限 IPv6 空间；系统和恢复答案均逐项校验，混合公网与非公网答案整体拒绝。 |
| 恢复触发与失败边界 | 合法系统答案直接使用；系统解析失败、超时或答案不合规后才进入 HTTPS 恢复。恢复失败返回安全网络错误，不回退连接被拒绝的 SYSTEM 地址。 |
| 固定恢复服务 | 仅构造 `https://dns.alidns.com/resolve`，bootstrap 固定为 `223.5.5.5` 和 `223.6.6.6`；内层不调用系统或恢复 DNS，不接受目录下发的解析器地址。使用系统默认 TLS 校验，无信任全部证书或关闭主机名校验的实现。 |
| 凭据与代理隔离 | 恢复客户端独立创建，设置 NO_PROXY、NO_COOKIES、无鉴权器及无重定向；请求参数只有经校验的域名和 A／AAAA 类型。七个外层专用客户端也设置 NO_PROXY，避免系统 HTTP 代理绕过连接所用 DNS 的公网校验。 |
| 本地目标与字面地址 | 本地、单标签和特殊后缀域名不送往恢复服务；数值 IP 使用受限字面解析及公网校验，不执行外部 DNS 查询。手机配对 LAN 和本机媒体租约监听不经此上游解析器。 |
| 预算与资源上限 | 系统与 HTTPS 各使用 2 线程、队列 8 的独立有界池；系统最多等待 750 毫秒，两阶段连同排队共用单调时钟 3 秒预算。超时或调用线程中断会取消任务及已经创建的恢复 Call；响应最多 64 KiB。没有每次超时重建线程池的路径。 |
| 答案完整性 | 严格校验 Status、Question 的域名和类型、截断标记、记录类型、数值 IP、CNAME 链归属和循环；不将任意 Answer.data 交给域名解析。只使用请求域名可达链终点的合规答案。 |
| 全局与持久化边界 | 不修改设备、系统代理、Hawk 或全局 OkGo DNS，不持久化 DNS 状态，也不改变网盘会话、账本、密钥别名或历史存储。 |

## 本轮发现与修复

初稿将 `Question` 固定当作数组，而集成负责人提供的匿名 AliDNS 实见响应使用单对象，会导致真实恢复结果被全部拒绝。最终实现受限兼容单对象或仅一个元素的数组，两种形式仍严格核对域名和类型。`AliDnsHttpsResolverTest.observedOfficialObjectQuestionAndSingleArrayBothValidateCnameChain` 已覆盖实见形状及多跳 CNAME；该 P1 关闭。

已阅读三组新增测试源码：`PublicAddressPolicyTest`、`RecoveringDnsTest`、`AliDnsHttpsResolverTest`，覆盖 Fake-IP、混合答案、本地信息不外送、固定传输边界、总预算、中断、Call 取消、队列拒绝及不响应中断的系统解析。实现者报告连同原 A 边界测试共 21／21 通过；本独立审校没有重跑测试，统一 Gradle 结果由集成负责人记录。

## 明确的验证边界

原生系统解析可能不响应中断，但占用受有界池限制。OkHttp 的 DNS 接口没有父 Call 取消参数；外层 Call.cancel 若未中断正在解析的线程，内部恢复最多仍可能工作至三秒期限。此限制已记入开发契约，不能宣称父 Call 取消等于内部查询立即终止。父操作仍须在返回后检查取消和代际，禁止发布晚到结果。

本轮未运行 Gradle、Git、真实账号、远端媒体、转存或删除，也未自行调用远端 DNS 服务。提供的匿名 JSON 只证明某次端点响应形状，不证明服务稳定性、设备连通性或媒体播放成功。仍须在最终 R8 候选包上验证 API19／23+ 加载、当前 Fake-IP 网络中的元数据恢复、取消行为及原有 LAN／loopback 功能；真实扫码、片单、连续播放、跨日会话和真机门槛继续按 PRD 执行。

审校对象 SHA-256：`PublicAddressPolicy.java` 为 `04d645010e9dcc01233ea501e8e6446e1caed8bc2280e1f3ad10c1bca3e8edf9`；`RecoveringDns.java` 为 `3bb20622efe149ce35139d3657c31e9927c1db25dedff7e1a2f0dfcafe0c0b6d`；`AliDnsHttpsResolver.java` 为 `50498feab7618c5707f18749d7aad379494ea908167f017fcc664b3dd4f1dc3f`。
