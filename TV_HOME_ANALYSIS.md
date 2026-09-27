# WebHomeTV Android TV / Google TV 系统 Home 集成分析报告

## 一、项目构建结构

### 1. 模块与 Flavor 架构
- **App Module 路径**: `app/`
- **Flavor Dimensions**:
  - `dimension "mode"`: `leanback` (TV 端) 和 `mobile` (移动端)
  - `dimension "abi"`: `arm64_v8a`, `armeabi_v7a`
- **TV 端构建变体 (Variant)**:
  - Debug: `leanbackArm64_v8aDebug`, `leanbackArmeabi_v7aDebug`
  - Release: `leanbackArm64_v8aRelease`, `leanbackArmeabi_v7aRelease`
- **Application ID**: `com.fongmi.android.tv`
- **SDK 版本**:
  - `minSdk`: 24 (Android 7.0)
  - `targetSdk`: 28 (Android 9.0)
  - `compileSdk`: 37
- **依赖库现状**:
  - AndroidX Leanback: `androidx.leanback:leanback:1.2.0`
  - Media3: `1.11.0-alpha01-fongmi`
  - Room: `androidx.room:room-runtime:2.8.4`
  - EventBus: `org.greenrobot:eventbus:3.3.1`
  - Glide: `com.github.bumptech.glide:glide:5.0.7`
  - `androidx.tvprovider:tvprovider`: 原项目尚未引入，需以 `leanbackImplementation` 方式引入 `1.0.0`，确保不污染 `mobile`。

### 2. Manifest 与主要 Activity
- **Main Manifest**: `app/src/main/AndroidManifest.xml` (定义基础权限、Provider、Service、Application 配置)
- **TV Manifest**: `app/src/leanback/AndroidManifest.xml` (定义 TV 端专属 Activity、Banner、Leanback 特性)
- **TV 主 Activity**: `com.fongmi.android.tv.ui.activity.HomeActivity` (`app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java`)
- **TV 播放与详情 Activity**: `com.fongmi.android.tv.ui.activity.VideoActivity` (`app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java`)
  - WebHomeTV 在 TV 端采用单界面合一模式：`VideoActivity` 同时承载影片详情展示与播放器核心。

---

## 二、观看历史 (History) 实现分析

### 1. 实体与存储
- **实体类文件**: `app/src/main/java/com/fongmi/android/tv/bean/History.java`
- **DAO 文件**: `app/src/main/java/com/fongmi/android/tv/db/dao/HistoryDao.java`
- **数据库**: `AppDatabase` (Room 数据库，名称 `"tv"`，当前版本 37)
- **表名**: `History`
- **主键**: `key` (String)，通常格式为 `siteKey + "@@@" + vodId + "@@@" + cid` 或 `siteKey + "@@@" + vodId`。
- **关键字段**:
  - `key` (String): 主键
  - `vodName` (String): 影片标题
  - `vodPic` (String): 海报图片 URL
  - `wallPic` (String): 背景图片 URL
  - `vodFlag` (String): 线路/播放源标识
  - `vodRemarks` (String): 剧集名称（如“第1集”）
  - `episodeUrl` (String): 当前播放集播放地址
  - `position` (long): 当前播放进度（毫秒）
  - `duration` (long): 视频总时长（毫秒）
  - `createTime` (long): 最近更新时间戳（毫秒）
  - `cid` (int): 接口配置 ID (`VodConfig.getCid()`)
  - `opening` (long), `ending` (long): 片头/片尾跳过位置

### 2. 字段提取方法
- 站点 Key: `history.getSiteKey()` -> `key.split("@@@")[0]`
- 视频 ID: `history.getVodId()` -> `key.split("@@@")[1]`
- 完播判定逻辑: 已内置 `history.isNearEnding()`，根据视频总时长智能计算阈值（5秒至30秒），当剩余时间小于阈值时视为接近结束。

### 3. 写入与更新时机
- **写入位置**:
  1. `VideoActivity.saveHistory()`: 播放退出、切集或后台挂起时，从 `player().getPosition()` / `player().getDuration()` 写入 `mHistory` 并调用 `mHistory.save()`。
  2. `VideoActivity.syncHistory()`: 周期性同步。
  3. `PlaybackProgressWriter`: 远程同步、API 同步。
- **删除逻辑**:
  - 单条删除: `history.delete()` (删除本地记录) 或 `history.deleteAndSync()` (产生 tombstone 并同步)。
  - 清空历史: `History.delete(cid)` 或 `History.deleteAndSync(cid)`。

---

## 三、收藏 (Keep) 实现分析

### 1. 实体与存储
- **实体类文件**: `app/src/main/java/com/fongmi/android/tv/bean/Keep.java`
- **DAO 文件**: `app/src/main/java/com/fongmi/android/tv/db/dao/KeepDao.java`
- **表名**: `Keep`
- **主键**: `key` (String，格式同 History: `siteKey + "@@@" + vodId + "@@@" + cid`)
- **关键字段**:
  - `key`: 主键
  - `vodName`: 视频名称
  - `vodPic`: 海报图片
  - `siteName`: 站点名称
  - `createTime`: 收藏时间戳
  - `type`: 类型 (0: VOD 视频收藏, 1: Live 直播收藏)
  - `cid`: 接口配置 ID

### 2. 操作方法
- **获取收藏列表**: `Keep.getVod()` 返回 `List<Keep>`，按 `createTime DESC` 降序排列。
- **添加收藏**: `keep.save()` (`VideoActivity.createKeep()`)。
- **取消收藏**: `keep.delete()` (`VideoActivity.checkKeepImg()`)。

---

## 四、首页推荐 (Recommendation) 实现分析

### 1. 数据来源
- `HomeActivity` 在配置加载成功后调用 `mViewModel.homeContent()`。
- `SiteViewModel` 请求首页站点的数据后，通过 LiveData `mViewModel.getResult()` 分发。
- 在 `HomeActivity.java:294` 中，`result.getList()` 包含当期推荐的 `List<Vod>`。
- 同时通过 `Cache.clear().put(result)` 进行内存缓存。
- **系统 Home 集成策略**:
  - 绝不重复发起额外全量网络请求。
  - 在 `HomeActivity` 收到 `mViewModel.getResult()` 推荐数据后，通知 `TvHomeManager.onRecommendChanged(result.getList())` 增量同步至系统首页推荐频道。

---

## 五、播放入口与启动参数分析

### 1. TV 端播放入口
`VideoActivity` 提供了标准的静态启动方法：
```java
VideoActivity.start(
    Activity activity,
    String key,       // siteKey
    String id,        // vodId
    String name,      // 视频名称
    String pic,       // 海报图片
    String mark,      // 当前集名称 (可选)
    boolean collect,  // 是否从聚合进入
    boolean cast,     // 是否投屏
    String wallPic,   // 背景图 (可选)
    String content    // 简介内容 (可选)
)
```

### 2. 内部恢复播放机制
当 `VideoActivity` 启动时：
1. 计算 `getHistoryKey()` (`key + "@@@" + id + "@@@" + cid`)。
2. 调用 `mHistory = History.find(getHistoryKey())` 查询历史。
3. 如果存在历史记录，会自动恢复当前线路 (`vodFlag`)、当前选集 (`vodRemarks`) 和播放进度 (`position`)。
4. 调用 `resolveInitialPlaybackPosition()` 自动 seek 到断点位置。
5. 因此，外部启动只需准确传递 `key`、`id`、`name`、`pic`（可选 `mark`、`wallPic`），WebHomeTV 即可完整自动恢复播放！

---

## 六、Deep Link 规范与路由设计

### 1. URI 结构
统一采用自定义 URI 协议：
```
webhtv://vod?key={key}&id={id}&name={name}&pic={pic}&mark={mark}&wallPic={wallPic}
```
所有 query 参数均经过严格的标准 URL 编码 (`UTF-8`) 与安全解码。

### 2. 路由处理
- 在 `app/src/leanback/AndroidManifest.xml` 中为 `HomeActivity` 注册 `webhtv://vod` 的 `VIEW` intent-filter。
- 为什么通过 `HomeActivity` 路由？
  1. 保证 App 依赖的 `VodConfig`、数据库等全局上下文正常初始化。
  2. 提供完善的返回栈体验（退出视频后回到 App 首页而不是白屏退出）。
  3. 异常容错机制：当 Deep Link 参数缺失或无效时，优雅 fallback 至 `HomeActivity` 首页，不发生任何崩溃。

---

## 七、数据流与同步架构设计

```
[WebHomeTV 运行时事件]
       │
       ├─► 播放进度更新 / 停止 ──► History.save() ──► TvHomeManager.onHistoryChanged()
       │                                                      │
       ├─► 收藏添加 / 取消   ──► Keep.save()/delete() ─► TvHomeManager.onKeepChanged()
       │                                                      │
       ├─► 首页推荐加载完成  ──► SiteViewModel ───────► TvHomeManager.onRecommendChanged()
       │                                                      │
       └─► 开机广播 / 进入 App ──────────────────────► TvHomeManager.syncAll()
                                                              │
                                                              ▼
                                                     [TvHomeSync 异步工作线程]
                                                              │
                     ┌────────────────────────────────────────┴────────────────────────────────────────┐
                     ▼                                                                                 ▼
         [TvContractCompat.WatchNextPrograms]                                           [TvContractCompat Preview Channels]
          - 仅限未完播且 position > 0                                                   - "最近观看" 频道
          - 检查 TvHomeSuppressionStore 过滤抑制项                                      - "我的收藏" 频道
          - 设置 lastPlaybackPositionMillis & durationMillis                            - "WebHomeTV 推荐" 频道
          - 完播或用户移除后退出 Watch Next                                             - 频道内按 siteKey + vodId 去重
```
