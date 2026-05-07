# BubbleTranslate 开发全记录

> 一个 Android 悬浮窗截图翻译 App 的诞生历程
>
> 项目: [Hi-Barry/BubbleTranslate](https://github.com/Hi-Barry/BubbleTranslate)
>
> 技术栈: Kotlin + Android SDK 34 + Kimi K2.6 Vision API + MediaProjection
>
> 跨越: 4 天 · 50+ 次提交 · 8 次关键 Bug 修复 · 3 轮坐标对齐方案迭代

---

## 目录

1. [第一天：从零到跑通](#第一天从零到跑通)
2. [第二天：从能用到稳定](#第二天从能用到稳定)
3. [第三天：坐标对齐的终极之战](#第三天坐标对齐的终极之战)
4. [第四天：双击启动与初始化竞态](#第四天双击启动与初始化竞态)
5. [技术盘点](#技术盘点)

---

## 第一天：从零到跑通

### `636ff55` — 初始实现

> 2026-04-30 17:53 · 作者: root · Co-Authored-By: Claude Opus 4.7

Android 悬浮窗翻译 App 的初始实现，使用 Kimi K2.6 视觉模型。包含三个核心功能：
- 可拖拽的悬浮气泡（Floating Bubble）
- 区域选择遮罩层（Selection Overlay）
- 通过 Kimi API 的流式翻译（Streaming Translation）

### `98c7ebf` — 修复选择和截图

> 2026-04-30 17:56

- 使用 `saveLayer/restoreToCount` 实现 CLEAR xfermode 混合
- 移除矛盾的 `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` 标志
- 截图捕获添加 3 秒超时
- 翻译弹窗使用 `FLAG_NOT_TOUCH_MODAL` 使关闭按钮可用

### `59b4af3` — 编译错误修复

> 2026-04-30 17:58

三处编译问题：缺少 `GradientDrawable` 导入、可空字符串处理、Float 转 Int 的类型转换。

### 第一轮 CI/CD 建设（5 次提交）

在项目跑通后，投入了大量精力建设 CI/CD 流水线：

| 提交 | 内容 |
|------|------|
| `251669f` | 添加 GitHub Actions 写入权限 |
| `ea911eb` | 签名密钥与 CI 集成 |
| `a08b9d8` | 环境变量替代文件配置 |
| `6f5d303` | 修复 Gradle 导入 |
| `de2dd31` | 调试输出与签名配置 |
| `22be5c1` | 智能类型转换修复 |
| `b2493c5` | 改用 `apksigner` 后处理签名 |
| `f6b1aaf` | APK 重命名规范 |

**教训：** Android 应用的 CI 签名是一个多步骤的复杂过程，最终方案是 Gradle 构建未签名 APK + CI 步骤中用 `apksigner` 单独签名。

---

## 第二天：从能用到稳定

### `a9af3ed` — 选择区域后闪退修复（关键）

> 2026-04-30 23:42 · 作者: Hi-Barry

这是第一个由真实用户触发的严重 Bug。选择区域后 App 闪退，涉及三个根本原因：

**1. GPU 驱动崩溃**

`SelectionOverlayView` 使用 `saveLayer` + `PorterDuff.Mode.CLEAR` 来实现"挖洞"效果（选中区域透明，周围变暗）。但在硬件加速的 Canvas 上，某些 GPU 驱动会因此崩溃。

**修复：** 放弃复杂的 xfermode，改为用四个矩形分别绘制选中区域的上、下、左、右侧的暗色区域。简单、稳定、性能更好。

**2. 截图 Buffer 异常**

`ScreenshotManager` 在从 `Image` 读取像素到 `Bitmap` 时，没有调用 `buffer.rewind()`，且行填充（row-padding）处理有误，导致 `BufferUnderflowException`。

**3. Intent 反序列化兼容性**

`getParcelableExtra("data")` 在 Android 13+ 上需要显式指定类型参数。

### `fe3f768` — View 生命周期与 ProGuard

> 2026-05-01 05:18 · 作者: Hi-Barry

**最诡异的 Bug：** 选择区域后偶发闪退，发生在触摸事件派发过程中。

**Root Cause 1 — View 在触摸中自毁：**

`onTouchEvent` 中直接调用了 `windowManager.removeView()`。Android 的触摸派发链在 View 被移除时会崩溃——等于是你正在用一把椅子，椅子自己把自己抽走了。

**修复：** 使用 `View.post()` 将所有移除操作延迟到下一个事件循环迭代，让触摸派发先安全完成。

**Root Cause 2 — ProGuard 剪枝：**

Release 构建中 ProGuard 裁剪了协程相关的类（`AndroidDispatcherFactory`），导致 `Dispatchers.Main` 在 Service 中初始化失败。

**修复：** 添加协程的 ProGuard keep 规则。

### `2c6a02a` — 诊断日志体系

> 2026-05-01 11:52 · 作者: Hi-Barry

为所有关键路径添加了 `Log.d/e("BT", ...)` 日志，以及全局 `try-catch(Throwable)` 拦截器和崩溃文件记录。这个日志体系在后来的所有调试中发挥了关键作用。

### `6df7304` — 三大 Bug 定点清除

> 2026-05-01 12:19

> **这是项目转折点。** Hi-Barry 接管了开发，此前由 AI 全程生成。

1. **截图全黑：** `VirtualDisplay` 和 `ImageReader` 之间缺少帧同步。改为轮询方式，等待至少一帧可用。
2. **UI 状态异步撕裂：** `MainActivity` 的 `launchAndRepeat` 和 `observe` 同时在更新 UI 状态，导致竞态条件。重构为单一状态更新函数。
3. **Service 被杀死后气泡不恢复：** `FloatingBubbleService.onCreate` 中的异常导致 `showBubble()` 被跳过。在 `onStartCommandInternal` 的异常处理器中也调用 `showBubble()`。

### `48cc557` — APK 自动版本命名

> 2026-05-01 12:24

CI 构建的 APK 文件名现在自动包含版本号，如 `PopupTranslation-2.0.9-arm64-v8a.apk`。

### `256cbab` — Service 中矢量图标渲染

> 2026-05-01 12:30

Service 上下文中加载包含矢量图的布局时，`android:tint` 导致 `InflationException`。开启 `vectorDrawables.useSupportLibrary = true` 并改用 `AppCompatResources.getDrawable()`。

### `f58e4f8` — API 响应处理重构

> 2026-05-01 12:53

同时处理流式（SSE）和非流式两种 API 响应格式，减少超时时间。

### `7c1e243` — 并发选择防护

> 2026-05-01 12:54

添加 `isTranslating` 标志位，防止用户在一次翻译进行中再次触发选择。

### `9cd0a3f` — 单窗口状态机重构（重大重构）

> 2026-05-01 14:10

**项目最大的重构。** 此前三个状态（气泡、选择遮罩、翻译面板）使用三个独立的 Window 管理，界面切换时需要同步三个窗口的创建/移除，极易出错。

**新架构：** 单个 `BubbleView` 通过 `State` 枚举（`IDLE` / `SELECT` / `TRANSLATE`）管理所有界面形态，配合 `WindowManager.updateViewLayout()` 实现平滑过渡。代码量减少 40%，状态一致性显著提高。

### `b7ae5aa` & `470c0c4` — 重构后编译修复

> 2026-05-01 14:31

重构引入了一些编译错误：`MATCH_PARENT`/`WRAP_CONTENT` 限定符问题、`PixelFormat` 导入遗漏、Lambda 返回标签错误、`setLineSpacingExtra` API 级别不够被移除。

### `510a738` — 持久化 VirtualDisplay

> 2026-05-01 14:33

从"每次截图新建 VirtualDisplay"改为"保持一个持久实例"，截图延迟从 500ms+ 降到接近零。

### `c3f5c8f` — GPU 同步延迟

> 2026-05-01 14:37

添加 300ms 延迟等待 GPU 合成完成后再截图。虽然持久 VirtualDisplay 理论上应该更快，但实际测试发现某些设备上需要等待至少一帧的合成周期。

### `5f9c23e` — 主线程创建 VirtualDisplay（关键）

> 2026-05-01 15:52 · 版本 v2.0.0 → v2.1.0

> **最隐蔽的 Bug：** `createVirtualDisplay()` 必须在主线程调用。

此前所有版本的 `ScreenshotManager` 将 `createVirtualDisplay()` 放在 IO 线程的 `withContext(Dispatchers.IO)` 中。这在 Google Pixel 等设备上工作正常，但在很多国产手机上（尤其开启了省电优化的设备），结果永远是零帧——`acquireLatestImage()` 始终返回 `null`。

**修复：** 在 `Dispatchers.Main` 上创建 VirtualDisplay，使用 `Handler(Looper.getMainLooper())`。这个看似微小的改动解决了反复出现一整天的"截图黑屏"问题。

**后续（`2413679`）：** 修复了将 `Triple` 改为 `data class CaptureSetup` 的编译错误（Kotlin 不允许解构 4 个值的 `Triple`）。

### 版本迭代中的细微修复

| 版本 | 提交 | 内容 |
|------|------|------|
| v2.0.1 | `f91556b` | 重新确认主线程创建 VirtualDisplay |
| v2.0.2 | `129cd32`, `c550444` | `view.post()` 延迟截图管线，让"翻译中…"占位符先渲染 |
| v2.0.3 | `2efde24` | 诊断日志 + 设置中的 API 配置 + START_STICKY 修复 |
| v2.0.4 | `f1ad9ca` | START_STICKY → START_NOT_STICKY（MediaProjection 令牌在重启后失效） |
| v2.0.5 | `02608e4` | **关键修复：** `Activity.RESULT_OK == -1`，判断条件写反了！导致 MediaProjection 永远失败 |
| v2.0.6 | `4884723` | `onStartCommand` 用 try-catch 包裹 + 逐步日志 |
| v2.0.7 | `99d0dd3` | Android 14+ 添加 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` |
| v2.0.8 | `bfb5383` | Kimi API 400 — `system` 角色的 `content` 必须是纯字符串 |
| v2.0.9 | `a3b0e14` | 弃用 SSE 流式解析，改用非流式 JSON 响应 |

**v2.0.5 的教训：** `Activity.RESULT_OK` 在 Android 中是 `-1`，但开发者直觉认为是 `1`，这个错误潜伏了多个版本。

---

## 第三天：坐标对齐的终极之战

这是项目中最曲折的一段调试经历——悬浮窗选择区域与截图区域的坐标对齐问题。

### `353c76b` — v2.1.0：getLocationOnScreen 补偿

> 2026-05-02 02:19

**问题：** 用户选择的区域截图后向上偏移了状态栏高度（包含了上一段文本，丢了最后一行）。

**分析：** `TYPE_APPLICATION_OVERLAY` 窗口被系统放置在状态栏下方，但 `MediaProjection` 截取的 Bitmap 从屏幕最顶端开始。`event.y`（view 局部坐标）不等于屏幕坐标。

**修复：** 用 `bubbleView.getLocationOnScreen()` 获取窗口偏移量，加到选区坐标上。

**结果：** 在某些设备上补偿正确，但在另一些设备上（窗口已被系统放在屏幕原点）导致**重复补偿**，选区向下偏移。

### `7690ff2` — v2.1.1：FLAG_LAYOUT_NO_LIMITS

> 2026-05-02 02:37

**思路：** 既然偏移量因厂商而异，不如强制窗口真正全屏，让两个坐标系天然对齐。

**修复：** `transitionToSelect()` 中添加 `FLAG_LAYOUT_NO_LIMITS` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`。

**结果：** **灾难性失败。** 窗口扩展到了屏幕上方（负值区域），`event.y` 被整体放大，选区偏移加剧，直接从第一段跳到了第三段。

### `2f57762` — v2.1.2：event.rawX/rawY ✨ 最终方案

> 2026-05-02 02:57

**顿悟：** 不要跟窗口 flags 和厂商行为较劲。`MotionEvent` 本身就提供了**屏幕绝对坐标** `rawX`/`rawY`。

**修复：**
1. `BubbleView` 的触摸处理中，同时记录 `event.x/y`（用于画框）和 `event.rawX/rawY`（用于裁剪）
2. `onSelectionComplete` 回调传递 raw 坐标
3. `processSelection` 直接用 raw 坐标裁剪 bitmap
4. 回退所有窗口 flag 修改

**结果：** 完美。`event.rawY` 从屏幕左上角开始计算，与 `MediaProjection` 截图的坐标空间天然一致，无需任何补偿计算。

### `db55783` — v1.0.0：正式发布

> 2026-05-02 05:14

删除所有开发版本的 tag 和 release，以 `v1.0.0` 作为项目的正式首个版本。

---

## 第四天：双击启动与初始化竞态

> 2026-05-05 · 版本 v1.3.3 → v1.3.4 · 分支 bugfix/start-twice

这是项目中最深的一次调试——一个"需要点击两次才能启动"的 Bug，引出了三个相互叠加的根因。

### 用户报告

> "点击启动翻译泡泡，弹出录制权限申请，点击立即开始，软件并没有启动，还是停止状态。需要再点击一次，再次弹出权限申请，点击立即开始才能启动。"

修复后暴露了第二个 Bug：翻译时报错 "Screen capture failed: MediaProjection not available"。

### 根因 1：主线程消息队列竞态（UI 显示"停止"）

`startForegroundService()` 是一个 Binder IPC 调用。它在 AMS 中**仅仅是往主线程消息队列 POST 了一条"创建 Service"的消息**就立即返回了。之后 Service 的 `onCreate()` 才在消息队列中执行。

**消息队列的实际顺序：**
```
[R1] callback 执行 → startForegroundService() → AMS 投递 [S1] 到队尾 → 返回
                     → isServiceRunning=true → UI 显示"运行中"
[R2] onResume() → updateUiFromServiceState()
                  → instance 还是 null！（[S1] 还没执行）
                  → isServiceRunning=false → UI 显示"停止"  ← BUG
[S1] onCreate() → instance=this → showBubble() → 泡泡出现
```

`onResume`（消息 R2）在 `onCreate`（消息 S1）之前执行，检查 `FloatingBubbleService.instance` 时它还是 null，于是 UI 从"运行中"被覆盖回"停止"。

**修复：** `onResume()` 中用 `binding.root.post { updateUiFromServiceState() }` 和 `startServiceWithMediaProjection()` 中同样使用 `post`，将状态检查延迟到 Service 创建消息之后。

### 根因 2：嵌套 launch() 静默丢结果（无 overlay 权限时）

当用户首次安装 App（overlay 权限未授予），`startTranslationFlow()` 先启动 overlay 权限设置页。`overlayPermissionLauncher` 的回调中调用 `requestMediaProjection()` → `mediaProjectionLauncher.launch()`。

AndroidX Activity Result API 要求 `launch()` 在 `onResume()` **之前**调用。但从一个 launcher 的回调中调用另一个 launcher，实际的 `launch()` 会被延迟到 `onResume()` 之后——结果是媒体投影对话框照常弹出，用户点击"立即开始"后**结果被静默丢弃**，callback 永远不会触发。

**修复：** `overlayPermissionLauncher` 回调中用 `binding.root.post { requestMediaProjection() }` 延迟。

### 根因 3：`getMediaProjection()` 必须在 `startForeground()` 之后

> **这是最终的致命一击。** 上述两个修复后，启动问题解决了，但翻译时报错：
> "Screen capture failed: MediaProjection not available"

原代码中 `onStartCommandInternal` 的顺序是：
```kotlin
getMediaProjection(resultCode, data)  // ① 先调
// ...
startForegroundCompat(...)             // ② 后调
```

在 Google Pixel 等标准设备上这没问题，但**大量国产 ROM（小米/华为/OPPO/Vivo）在 `getMediaProjection()` 内部会检查调用者是否已进入前台状态**。Service 的 `startForeground()` 还没执行时，`FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` 类型未激活 → 权限检查失败 → 返回 null。

**为什么旧版第二次点击能工作？** 因为第一次点击时 Service 虽然没有拿到 MediaProjection，但 `onStartCommand` 的 catch 处理器中调用了 `startForeground()`。Service 在第二次点击时已处于前台状态，`getMediaProjection()` 通过。

**修复：** 调换顺序——先 `startForeground()`，后 `getMediaProjection()`。

### 子根因：Intent 序列化丢失 Binder Token

在排查根因 3 的同时，还有一个叠加问题：原代码通过 `intent.putExtra("data", dataIntent)` 把 MediaProjection 的 `data` Intent 放入新 Intent 传给 Service。这个 `data` Intent 内部的 Binder token（`MediaProjection.EXTRA_MEDIA_PROJECTION`）经过 `Intent → Parcel → AMS(IPC) → Parcel → Intent` 的双重序列化后，在部分设备上丢失。

**修复：** 改用 `App` 单例的 `pendingResultCode` / `pendingResultData` 字段传递。Activity 和 Service 在同一进程，这是纯内存引用，零序列化。

### 踩坑总结

| # | 看上去是什么 | 实际是什么 |
|---|------------|-----------|
| 1 | "Service 没启动" | Service 启动了，但 UI 被 `onResume` 在 `onCreate` 前覆盖了 |
| 2 | "权限申请弹了两次" | 嵌套 launch 导致第一次结果被丢弃 |
| 3 | "截图功能坏了" | `getMediaProjection` 在 `startForeground` 前调用，国产 ROM 拒绝 |
| 4 | "mediaProjection 拿不到" | Intent 跨 IPC 时 Binder token 丢了 |

---

## 技术盘点

### 最终架构

```
FloatingBubbleService (LifecycleService)
  ├─ BubbleView (FrameLayout)      ← 单窗口状态机
  │    ├─ IDLE:    ImageView (气泡图标)
  │    ├─ SELECT:  Canvas (遮罩+选区)
  │    └─ TRANSLATE: LinearLayout (翻译面板)
  ├─ ScreenshotManager              ← MediaProjection 截图
  └─ KimiApiClient                  ← Kimi K2.6 Vision API
```

### 八次关键 Bug 修复

| # | 问题 | 根因 | 修复 |
|---|------|------|------|
| 1 | 截图全黑 | IO 线程创建 VirtualDisplay | 主线程 + MainLooper Handler |
| 2 | 选择后闪退 | View 在触摸中自毁 | View.post() 延迟移除 |
| 3 | UI 状态撕裂 | 异步竞态条件 | 单一状态更新函数 |
| 4 | Service 不恢复 | 异常跳过 showBubble() | 异常路径也调用 |
| 5 | API 400 | system content 格式错误 | 纯字符串而非对象数组 |
| 6 | **截图偏移** | 悬浮窗坐标 vs 屏幕坐标不匹配 | **event.rawX/rawY** |
| 7 | **双击才能启动** | 消息队列 onResume/onCreate 竞态 | View.post() 延迟状态检查 |
| 8 | **MediaProjection 不可用** | getMediaProjection 在 startForeground 前调用 | 调换调用顺序 |

### 最值得记住的原则

1. **`createVirtualDisplay()` 必须主线程。** 这条坑了 5 个版本。
2. **触摸事件中不要移除自身 View。** 用 `post()` 延迟。
3. **`RESULT_OK == -1`。** 不要用直觉判断。
4. **`event.rawX/rawY` > 窗口 flag 魔改。** 厂商行为不可预测时，用 API 自带的绝对坐标。
5. **日志先行。** 在花时间猜 bug 之前，先加足够的 log。
6. **`startForegroundService()` 返回时 Service 还没创建。** 消息队列顺序：[callback] → [onResume] → [onCreate]。不要同步检查 Service 状态。
7. **不要在 `registerForActivityResult` 回调里直接调另一个 launcher。** 用 `post`。
8. **`getMediaProjection()` 必须在 `startForeground()` 之后。** 国产 ROM 的前台服务类型检查比 Pixel 严格。
9. **Intent 跨进程传输时 Binder token 可能丢失。** 同进程内用单例传递复杂对象。

---

*本文件由项目的完整 Git 提交历史生成，关键调试经验由开发者手工增补。*

*共 50+ 次提交，跨越 4 天的密集开发。*
