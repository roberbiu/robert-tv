# 1.0.0 五品类热播数据可行性证据

日期：2026-10-29（北京时间）。阶段2材料；仅对公开页面、官网引用脚本文本与匿名JSON作有限只读请求，没有执行站点JS、登录账户、绕验证码/付费、请求媒体或修改应用运行代码。本文证明当前数据路径与可解析结构，不表示App已接入、接口永久稳定、获得完整再分发许可或正式验收已通过。

## 1. 可作为开发输入的五类路径

| 产品品类 | 已观察的数据来源 | 取数结果与口径 |
| --- | --- | --- |
| 电视剧 | 骨朵 `NETWORK_DRAMA`、`TV_DRAMA` | 分别20条、17条，日榜GDI热度；可按原榜分组展示，不创造合成全国播放率 |
| 电影 | 骨朵 `NETWORK_MOVIE`；百度电影榜可作补充 | 骨朵20条网络电影日榜；百度10条电影搜索热度。两者范围及分值不相同，必须分开标识 |
| 综艺 | 骨朵 `NETWORK_VARIETY`、`TV_VARIETY` | 分别20条、18条，日榜GDI热度；不把两榜分值与其他平台热度平均 |
| 动漫 | 骨朵 `ALL_ANIME` | 20条，保留来源自身的动漫/国漫范围与日榜标签，不声称覆盖全部国家全部动画 |
| 短剧 | 红果开发者网站反链指向的 `rank/hot-drama` | 当前20条；网页标注基于红果内观看/互动等综合热度，不能写成全国播放量或全量平台排行 |

所有路径都取得实际条目及指标字段，不以HTTP200、网页标题或纯空数组作为品类覆盖证据。最终使用哪些主榜/分组由主架构确定，开发及正式验收仍需复核类型与作品身份。

## 2. 骨朵公开日榜合同

来源页面：[骨朵数据](https://d2.guduomedia.com/)。本页实际引用的静态脚本为 `/gdsq_v3/js/rank_v3.js?v=2024081804`，仅按文本读取。观察到默认 `baseUrl=''`，`getList` 使用同域匿名GET：

```text
GET https://d2.guduomedia.com/m/v3/billboard/configDay
GET https://d2.guduomedia.com/m/v3/billboard/list
  ?type=DAILY
  &category=<官网已使用的分类枚举>
  &date=<configDay.data.DAILY.endDay>
  &attach=gdi
  &orderTitle=gdi
  &platformId=0
```

本次 `configDay` 返回 `code=0`、`DAILY.startDay=2018-01-31`、`DAILY.endDay=2026-10-27`。另官网 `display_day` 返回 `has_today=false`、`hour=21:00`。因此使用实际最后可用日期10月27日，不能擅自取设备“昨天”或标成10月29日实时榜。

| 官网枚举 | 返回条数 | 首项样本（仅用于合同核对） | gdi |
| --- | ---: | --- | ---: |
| NETWORK_DRAMA | 20 | 尚公主 | 72.58 |
| TV_DRAMA | 17 | 小城良方 | 74.58 |
| NETWORK_VARIETY | 20 | 现在就出发 第4季 | 56.87 |
| TV_VARIETY | 18 | 花儿与少年2026 | 55.03 |
| ALL_ANIME | 20 | 仙逆 | 50.74 |
| NETWORK_MOVIE | 20 | 战邮1942 | 48.75 |

响应为 `code=0`、`data`数组。字段包括 `showId/name/category/releaseDate/gdi/gdiFloat/rank/rise` 等。仅保留用于榜单与作品检索的必要字段，来源内部ID不能当作某播放站点的影片ID；点击应通过作品信息发起聚合搜索。

负例：`ALL_DRAMA`、`ALL_VARIETY` 两个聚合枚举本次返回0条，不能因为前端日志/变量出现过就当有效取数分类。禁止猜测一组枚举反复扫描；上述非空枚举来自真实官网代码及有限响应核对。

本次骨朵 `robots.txt` 返回404。该事实不等于开放数据许可；未观察到这些请求要求账号或绕过访问屏障。公开匿名元数据读取与第三方插件执行应分别审查，不因没有文档化API就自动否定开发可行性，也不宣称任意数据均可重新分发。

## 3. 短剧来源身份关联与纯数据提取

身份关联不是只相信网页自称“官网”：

1. 通过Apple官方iTunes搜索接口查询红果短剧，结果包含App ID `6451407032`，名称“红果短剧 - 海量热门短剧随心看”，seller为 `Beijing Bimo Liuxiang Technology Co., Ltd.`，bundleId为 `com.phoenix.video`。
2. 该结果的开发者网站为 `https://novelquickapp.com/hongguo`；本次正常重定向至 `https://hongguoduanju.com/`。
3. 该首页直接提供[红果热播榜](https://hongguoduanju.com/rank/hot-drama)及真人剧/漫剧/AI剧榜链接；页脚公司及ByteDance域名资源与上述关联一致。

这条证据链支持将该开发者网站提供的榜单作为明确来源候选，不代表可以声称“全国所有短剧官方总榜”或其内部统计已被独立审计。

榜单页面当前SSR含20条，页首文字为“10月29日已更新 · 基于红果内观看/互动等综合热度排序”。来源只给月日和指标描述时，应保留原文与日期精度；不得补造统计窗口、具体时分或全国播放市占。

可解析的纯数据点：

```text
script[data-script-src="modern-run-router-data-fn"][data-fn-name="r"]
  .attr("data-fn-args")

HTML属性解码后为JSON数组：
["rank_hot-drama/page", "content", {"isSuccess": true, "rankList": [...]}]
```

用HTML解析器提取属性，再解析JSON；不运行 `runWindowFn`、router脚本或任意JS。必须校验目标路由/`content`/成功标记和数组结构，再取 `id/seriesId/rank/title/cover/href/heatText` 等必要字段。仅作纯数据静态解析已取得20个带 `heatText` 的条目。

丢弃SSR上下文 `req`、内部host/IP/guest标识、`playHref/episodeVids`和剧情简介等无关内容，不将整个页面或内部上下文写入日志/公开仓库；不请求播放页。榜单作品在本App中进入统一检索，不绕过来源的付费或播放限制。

本次 `robots.txt` 为200，通用规则 `Allow: /`，另外限制部分筛选参数和 `/player/*/*`、`/series/`、`/query/` 等；`/rank/hot-drama`不在其禁止路径。读取范围保持在榜单元数据，不据此推导视频分发权限或永久数据API承诺。

## 4. 百度电影补充与已证伪对照

百度首页实际链接指向[电影榜](https://top.baidu.com/board?tab=movie)。本次匿名200响应包含纯JSON注释 `<!--s-data:{...}-->`，可以仅提取注释内JSON，不执行网页脚本。

`data.curBoardName=电影榜`，`data.cards[0].content`有10条，字段 `word/hotScore/index/url/img`。这是搜索热度，不是播放量；所见JSON没有明确源更新时间，必须区分“来源未提供”与本App获取时间，不能用抓取时刻冒充榜单更新时刻。本次 `robots.txt`为404，不据此推导数据许可。

爱奇艺官网SSR有混合热榜，但按官网实际JS追踪得到的匿名 `mesh.if.iqiyi.com/portal/pcw/rankList/comRankList` 请求，本轮两种官网参数形式均返回空 `items`。因此接口路径存在不能作为已经取得五品类可消费数据的证明。该对照及原始响应由主架构保存，当前不作为主数据承诺。

## 5. 架构需要落实的约束

- 使用榜单源、原指标、品类/分组、原始排名、源数据日期/精度和获取时间组成统一模型；不同指标不合成一个伪全国分值。
- 先按官网配置获取最后可用日期，再请求对应日榜；不要猜日期、批量扫历史或全分类。
- 按用户进入品类懒加载，限定并发/请求预算、响应大小、超时与重试次数，缓存规范化后的必要字段；不把匿名热榜错误误判成网盘登录失效。
- 缓存失败保留最近成功数据，并清楚显示源日期与缓存/过期状态。五个长期空页不能满足已确认P0。
- 不继承网页脚本、账号上下文或媒体URL；所有请求使用独立匿名边界，不附带用户云盘凭据，不绕登录/验证码/付费。
- 开发阶段用合成、最小化或有明确来源的结构夹具验证字段缺失、空数组、日期回退、源类型不匹配和恶意链接；正式阶段仍须以最终APK检索结果核验，不能由本报告代替。

## 6. 证据保存范围

本轮原始匿名响应、官网JS文本与静态JSON提取结果仅存工作目录 `work/robert-tv/feasibility-1.0.0/trending/`，不把整页源码、内部请求上下文或第三方完整数据集打入公开App。公开文档仅保留路径、合同、有限样本与覆盖边界，便于独立架构审校复核。
