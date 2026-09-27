# WebHomeTV Android TV / Google TV 系统 Home 集成实现说明

## 一、新增与修改文件清单

### 1. 新增文件列表
所有与 Android TV Home 相关的核心代码均独立封装在 `leanback` flavor 的专属包 `com.fongmi.android.tv.tvhome` 中，确保完全不影响 `mobile` flavor：

1. `app/src/leanback/java/com/fongmi/android/tv/tvhome/TvHomeDeepLink.java`
   - **作用**: 统一的系统 Home Deep Link 构建与分发器。支持 `webhtv://vod?...` 与 `webhtv://home` 协议，提供 URL 安全编解码、校验以及安全降级逻辑。
2. `app/src/leanback/java/com/fongmi/android/tv/tvhome/TvHomeSuppressionStore.java`
   - **作用**: 用户移除抑制机制持久化存储。基于 `SharedPreferences` 存储被用户在系统首页手动移除的内容 ID；当用户在应用内重新播放（超30秒或新播放）时自动解除抑制。
3. `app/src/leanback/java/com/fongmi/android/tv/tvhome/TvHomeWatchNext.java`
   - **作用**: 官方 `TvContractCompat.WatchNextPrograms` (Continue Watching) 接口实现。管理继续观看行、设置播放进度毫秒与时长毫秒、过滤已完播或已抑制内容、增量同步与完播移除。
4. `app/src/leanback/java/com/fongmi/android/tv/tvhome/TvHomeChannels.java`
   - **作用**: Android TV Preview Channels 频道与节目管理器。创建并维护“继续观看”、“最近观看”、“我的收藏”、“WebHomeTV 推荐”四大系统频道，基于 `contentId` 实现全增量比对更新。
5. `app/src/leanback/java/com/fongmi/android/tv/tvhome/TvHomeSync.java`
   - **作用**: 节流与防抖后台异步同步器。采用单线程 Executor 串行化 TvProvider 写入，提供 5 秒防抖（Debounce）处理播放进度高频更新，在退出或暂停时立即触发同步。
6. `app/src/leanback/java/com/fongmi/android/tv/tvhome/TvHomeManager.java`
   - **作用**: 面向 WebHomeTV 业务的统一门面单例。监听 EventBus 刷新事件、接收播放历史变更、收藏变更、推荐加载完成等事件，向同步器分发指令。
7. `app/src/leanback/java/com/fongmi/android/tv/tvhome/TvHomeReceiver.java`
   - **作用**: 广播接收器。接收系统广播 `android.media.tv.action.INITIALIZE_PROGRAMS`（初始化推荐节目）以及 `android.media.tv.action.WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED`（用户在首页移除项目时立即计入抑制名单）。
8. `app/src/testLeanback/java/com/fongmi/android/tv/tvhome/TvHomeDeepLinkTest.java`
   - **作用**: 单元测试类。验证 Deep Link 参数构建、URL 编码、解析、空参数容错及抑制存储逻辑。

### 2. 修改文件列表及修改点作用
遵循最小侵入与上游高可合并性原则，仅在 TV 端必要生命周期做小改动：

1. `gradle/libs.versions.toml`
   - **修改点**: 增加 `tvprovider = "1.0.0"` 版本及库依赖声明 `androidx.tvprovider:tvprovider:1.0.0`。
2. `app/build.gradle`
   - **修改点**: 在 `dependencies` 块中加入 `leanbackImplementation libs.tvprovider`。仅对 `leanback` flavor 生效，`mobile` flavor 零依赖。
3. `app/src/leanback/AndroidManifest.xml`
   - **修改点 1**: 声明系统 TV 权限 `com.android.providers.tv.permission.WRITE_EPG_DATA` 与 `READ_EPG_DATA`，确保 Preview Channels 的创建写入权限。
   - **修改点 2**: 在 `HomeActivity` 增加 `webhtv://vod` 和 `webhtv://home` 的 `VIEW` intent-filter，响应系统桌面卡片点击。
   - **修改点 3**: 注册 `TvHomeReceiver` 接收系统初始节目广播与用户移除广播。
4. `app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java`
   - **修改点 1**: 在 `checkAction(intent)` 中增加 `TvHomeDeepLink.isDeepLink(intent)` 分发处理。
   - **修改点 2**: 在 `showContent()` 中调用 `TvHomeManager.init(this)` 启动首次同步。
   - **修改点 3**: 在 `setViewModel()` 的首页结果回调中调用 `TvHomeManager.onRecommendChanged(...)`。
   - **修改点 4**: 在 `onItemDelete(History)` 与 `clearHistory()` 中调用 `TvHomeManager.onHistoryDeleted(...)` 与 `onHistoryCleared()`。
5. `app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java`
   - **修改点 1**: 在 `saveHistory()` 异步任务中加入 `TvHomeManager.onHistoryChanged(...)`（退出时调用 `onHistoryChangedImmediate`）。
   - **修改点 2**: 在 `syncHistory()` 异步任务中加入 `TvHomeManager.onHistoryChanged(...)`。
   - **修改点 3**: 在 `onKeep()`、`createKeep()`、`updateKeep()` 中调用 `TvHomeManager.onKeepChanged()`。
6. `app/src/leanback/java/com/fongmi/android/tv/receiver/BootReceiver.java`
   - **修改点**: 开机完成广播到达时调用 `TvHomeManager.init(context)` 与 `TvHomeManager.syncAll()`。

---

## 二、Android TV Preview Channel 频道设计

在 Android TV 8.0+ 桌面创建以下频道（`TvContractCompat.Channels.TYPE_PREVIEW`）：

| 频道名称 | 内部唯一标识 | 数据源 | 排序规则 | 容量上限 | 特性 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **继续观看** | `channel_continue` | `History` (未完播且 progress > 0) | `createTime DESC` | 20 | 带播放进度条 (`lastPlaybackPositionMillis` & `durationMillis`) |
| **最近观看** | `channel_recent` | `History.get()` | `createTime DESC` | 20 | 包含已完播内容，提供快速重温入口 |
| **我的收藏** | `channel_favorite` | `Keep.getVod()` | `createTime DESC` | 20 | 收藏列表，实时随用户收藏/取消同步 |
| **WebHomeTV 推荐** | `channel_recommend` | `HomeActivity` 首页推荐 `Vod` 列表 | 首页返回顺序 | 20 | 直接复用现有首页数据，不重复发起请求 |

所有频道节目均使用 `siteKey + "@@@" + vodId` 作为 `contentId`，在更新时采用增量比对机制：
- 数据库已有该 `contentId`：更新节目属性 (`ContentResolver.update`)
- 数据库无该 `contentId`：插入新节目 (`ContentResolver.insert`)
- 列表中已移除的节目：删除废弃条目 (`ContentResolver.delete`)

---

## 三、Watch Next / Continue Watching 官方 API 实现

在 Android TV / Google TV 官方 Continue Watching 行中：
1. **数据准入条件**:
   - `history.getPosition() > 0`
   - `!history.isNearEnding()` (剩余时间大于智能阈值 5s~30s)
   - `history.getPosition() < history.getDuration() - 5000` (未到达最后5秒)
   - 未在 `TvHomeSuppressionStore` 抑制名单中
2. **元数据映射**:
   - `title`: 影片主标题
   - `description`: 选集名称（如“第5集”）
   - `posterArtUri`: 自动清除 `@Headers=` 等非标 URL 参数，保留纯净图片链接
   - `intentUri`: 标准 Deep Link `webhtv://vod?key=...&id=...`
   - `lastPlaybackPositionMillis`: `(int) history.getPosition()` (精准毫秒)
   - `durationMillis`: `(int) history.getDuration()` (精准毫秒)
   - `lastEngagementTimeUtcMillis`: `history.getCreateTime()`
   - `type`: `TvContractCompat.WatchNextPrograms.TYPE_MOVIE`
   - `watchNextType`: `WATCH_NEXT_TYPE_CONTINUE`
3. **完播与移除**:
   - 当用户播放完毕触发 `isNearEnding()` 时，下次同步将自动检测并调用 `ContentResolver.delete` 将其从 Watch Next 移除。

---

## 四、用户移除抑制机制 (Suppression) 实现

针对 Android TV / Google TV 桌面用户长按“从继续观看中移除”功能：
1. **广播捕获**:
   - `TvHomeReceiver` 监听 `android.media.tv.action.WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED`。
   - 提取被禁用的 `programId`，查询其 `contentId` 并提取 `siteKey + vodId`，立即写入 `TvHomeSuppressionStore`。
2. **数据源过滤**:
   - 在构建 Watch Next 与继续观看频道时，调用 `TvHomeSuppressionStore.isSuppressed(siteKey, vodId)`，已抑制项目直接跳过。
3. **自动解除抑制**:
   - 当用户后续主动在应用内重新打开该影片，且播放进度达到 30 秒（`UNSUPPRESS_PLAYBACK_MS`）或产生新的观看记录时，自动调用 `TvHomeSuppressionStore.unsuppress(siteKey, vodId)` 解除抑制，恢复正常同步。

---

## 五、Deep Link 规范与恢复播放逻辑

### 1. 协议规范
```
webhtv://vod?key={URL_ENCODED_KEY}&id={URL_ENCODED_ID}&name={NAME}&pic={PIC}&mark={MARK}&wallPic={WALL_PIC}
```

### 2. 路由与启动恢复
1. 用户点击系统 Home 卡片，系统派发 `ACTION_VIEW` 意图，启动 `HomeActivity`。
2. `HomeActivity.checkAction()` 识别出 `TvHomeDeepLink`。
3. 解析出 `key`、`id`、`name`、`pic`、`mark`、`wallPic`。
4. 调用 `VideoActivity.start(activity, key, id, name, pic, mark, false, false, wallPic, null)`。
5. `VideoActivity` 内部根据 `getHistoryKey()` 自动从 Room 数据库中查出上次观看的选集 (`vodRemarks`)、线路 (`vodFlag`) 和断点 (`position`)，无缝自动恢复播放！
6. 若参数损坏或缺失，安全停留在 `HomeActivity`，绝不发生闪退。
