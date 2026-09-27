# WebHomeTV Android TV / Google TV 系统 Home 集成测试与验证指南

## 一、构建命令与生成文件

### 1. 构建命令
在项目根目录执行：

- **Leanback Debug 构建 (推荐)**:
  ```bash
  ./gradlew assembleLeanbackArm64_v8aDebug
  ```
- **Leanback Release 构建**:
  ```bash
  ./gradlew assembleLeanbackArm64_v8aRelease
  ```
- **单元测试验证 (验证 Deep Link 与抑制机制)**:
  ```bash
  ./gradlew testLeanbackArm64_v8aDebugUnitTest --tests com.fongmi.android.tv.tvhome.TvHomeDeepLinkTest
  ```
- **Mobile Flavor 编译验证 (确保未受污染)**:
  ```bash
  ./gradlew assembleMobileArm64_v8aDebug
  ```

### 2. 生成 APK 实际路径
- **TV Debug APK**:
  `webhtv/app/build/outputs/apk/leanbackArm64_v8a/debug/app-leanback-arm64_v8a-debug.apk`
- **TV Release APK**:
  `webhtv/app/build/outputs/apk/leanbackArm64_v8a/release/leanback-arm64_v8a.apk`
- **Mobile Debug APK (对比验证)**:
  `webhtv/app/build/outputs/apk/mobileArm64_v8a/debug/app-mobile-arm64_v8a-debug.apk`

---

## 二、ADB 安装与测试命令

### 1. 安装与启动
```bash
# 1. 安装 APK 到 TV 设备
adb install -r app/build/outputs/apk/leanbackArm64_v8a/debug/app-leanback-arm64_v8a-debug.apk

# 2. 正常启动应用
adb shell am start -n com.fongmi.android.tv/.ui.activity.HomeActivity
```

### 2. Deep Link 直接调起与恢复播放验证
```bash
# 调起测试：打开指定影片并自动恢复播放
adb shell am start -a android.intent.action.VIEW -d "webhtv://vod?key=douban&id=3541415&name=%E6%B5%81%E6%B5%AA%E5%9C%B0%E7%90%832" -p com.fongmi.android.tv

# 调起异常参数测试（空参数，应优雅回退至首页，不闪退）
adb shell am start -a android.intent.action.VIEW -d "webhtv://vod?key=&id=" -p com.fongmi.android.tv
```

### 3. 系统 Home 频道与 Watch Next 数据库查询
通过 ADB 查看系统 TvProvider 中已注册的数据：
```bash
# 查询 Watch Next (Continue Watching) 记录
adb shell content query --uri content://android.media.tv/watch_next_program

# 查询 WebHomeTV 自定义预览频道
adb shell content query --uri content://android.media.tv/channel --projection _id:display_name:internal_provider_id

# 查询预览频道内的节目卡片
adb shell content query --uri content://android.media.tv/preview_program --projection _id:channel_id:title:content_id:last_playback_position_msec
```

### 4. 模拟系统广播触发
```bash
# 模拟系统初始化推荐节目广播
adb shell am broadcast -a android.media.tv.action.INITIALIZE_PROGRAMS -p com.fongmi.android.tv
```

---

## 三、实机详细验证步骤

### 1. 基础功能回归验证
1. 打开 WebHomeTV，确认原有首页正常渲染。
2. 切换接口源，确认接口配置加载正常。
3. 点击搜索，输入关键词，确认能正常检索影片。
4. 进入影片详情并开始播放，确认清晰度、选集、音轨与弹幕功能正常。

### 2. Continue Watching / Watch Next 验证
1. 播放某部影片约 1 分钟后按返回键退出播放器。
2. 按遥控器 Home 键回到 Android TV / Google TV 桌面。
3. 检查系统首页的“继续观看 (Continue Watching)”或“Watch Next”行：
   - 出现该影片卡片。
   - 显示标题、海报图片。
   - 进度条显示在约 1 分钟位置。
4. 点击该卡片：
   - 触发 Deep Link `webhtv://vod?...`。
   - WebHomeTV 启动并直接打开该影片详情。
   - 播放器自动恢复到上次播放位置（约 1 分钟处）继续播放。

### 3. 完播移除验证
1. 快进影片至结尾（最后 10 秒内）或等待播放自然结束。
2. 退出播放器并回到系统桌面。
3. 检查系统首页“继续观看”：该影片已自动从 Continue Watching / Watch Next 移除。
4. 检查系统“最近观看”频道：该影片仍保留在最近观看列表中。

### 4. 收藏与推荐频道验证
1. 在影片详情页点击“收藏”按钮。
2. 回到系统桌面，进入 WebHomeTV 专属频道行中的“我的收藏”：该影片卡片已同步展示。
3. 再次点击取消收藏后，卡片在系统中自动移除。
4. 浏览“WebHomeTV 推荐”频道：卡片与 App 首页当前源推荐内容一致。

### 5. 用户移除抑制 (Suppression) 机制验证
1. 在 Android TV 系统首页 Continue Watching 行中，长按某影片卡片，选择“从继续观看中移除”。
2. 打开 WebHomeTV，在首页进行其它操作（如浏览、收藏其它影片），然后返回系统桌面。
3. 验证被移除的影片**没有**被下一次后台同步重新加入继续观看行。
4. 在 WebHomeTV 中重新打开该影片，主动播放超过 30 秒。
5. 退出播放器，回到系统桌面：验证该影片已解除抑制，重新出现在继续观看行中。

---

## 四、已知限制与兼容性说明

1. **Android TV Launcher 支持**:
   - Preview Channels 与 Watch Next 规范需要设备运行 Android 8.0 (API 26) 及以上版本，并且 Launcher 支持 Android TV 官方 Preview Channel 规范（如 Android TV Home、Google TV Launcher）。
   - 部分国产定制电视盒子（如极米、当贝、小米电视部分国内版本）移除了官方 TvProvider 框架；在这些设备上，所有系统集成调用均由 `try/catch` 和 `isSupported()` 保护，App 会**静默降级（Fail Gracefully）**，绝不崩溃或异常，App 内全部功能完全正常。
2. **图片跨域与防盗链**:
   - 系统桌面 Launcher 独立请求海报图片，不支持携带 App 内部自定义 Request Header；对于部分严格限制 Referer 的影视源，系统桌面可能回退显示默认应用图标或标题占位卡片。
3. **环境限制**:
   - 当前开发主机暂无物理连接的 Android TV 实体设备；代码已通过严格的 AndroidX 架构设计、静态检查、单元测试及 Debug / Release 完整编译打包。
