# 架构设计：单窗口状态机

> 将现有「小圆球 + 选择遮罩 + 翻译弹窗」三个独立窗口合并为一个动态缩放的悬浮窗。

---

## 状态机

```
        ┌────────────────────────────────────────────┐
        │                                            │
        ▼                                            │
  ┌──────────┐   点击    ┌──────────┐    拖拽完成    ┌────────────┐
  │  IDLE    │ ────────→ │ SELECT   │ ─────────────→ │ TRANSLATE  │
  │ (小球)   │           │ (全屏)   │                │ (展开面板) │
  └──────────┘ ←──────── └──────────┘ ←───────────── └────────────┘
         ↑      点外面          │ 点外面                     │ 点外面
         │                      │                            │
         └──────────────────────┴────────────────────────────┘
                       回退到 IDLE
```

**3 个状态，4 种触发事件，1 个 View 实例贯穿全程。**

| 状态 | 用户行为 | 视觉效果 | 下一个状态 |
|------|---------|---------|-----------|
| **IDLE** | 点击 | 小球 → 遮罩 | SELECT |
| **SELECT** | 拖拽选区域 | 画出选择框 | TRANSLATE |
| **TRANSLATE** | 点外面 | 面板 → 小球 | IDLE |
| **SELECT** | 点外面 | 遮罩 → 小球 | IDLE |
| **TRANSLATE** | — | 翻译结果流式渲染 | 留在 TRANSLATE |

---

## 每个状态的 LayoutParams

### IDLE（小球）

```kotlin
// 和当前 showBubble() 一致
width  = WRAP_CONTENT   // 56dp
height = WRAP_CONTENT   // 56dp
flags  = FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS
type   = TYPE_APPLICATION_OVERLAY
gravity = TOP | START
```

内容：`FrameLayout` 内嵌图标 ImageView。

---

### SELECT（全屏遮罩）

```kotlin
// 将现有 View resize —— 非 addView
width  = MATCH_PARENT
height = MATCH_PARENT
flags  = FLAG_NOT_FOCUSABLE | FLAG_FULLSCREEN
         | FLAG_LAYOUT_IN_SCREEN | FLAG_NOT_TOUCH_MODAL
         | FLAG_WATCH_OUTSIDE_TOUCH    // ← 监听外部点击退回 IDLE
type   = TYPE_APPLICATION_OVERLAY
gravity = TOP | START
x = 0
y = 0
```

**View 内部布局变化**：
- 隐藏图标层（`GONE`）
- 显示遮罩层（`VISIBLE`），复用当前 `SelectionOverlayView` 的绘制逻辑
- 遮罩层直接用 `onDraw` 绘制，不需要 inflate

**触摸处理切换**：
- 移除小球拖拽的 `OnTouchListener`
- 遮罩层接管 `onTouchEvent`（选择区域）

---

### TRANSLATE（展开面板）

```kotlin
width  = screenWidth * 0.88
height = WRAP_CONTENT        // 根据内容撑开，max = screenHeight * 0.6
flags  = FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN
         | FLAG_WATCH_OUTSIDE_TOUCH    // 监听外部点击退回 IDLE
type   = TYPE_APPLICATION_OVERLAY
gravity = CENTER
x = 0
y = 0
```

**View 内部布局变化**：
- 隐藏遮罩层（`GONE`）
- 显示翻译面板层（`VISIBLE`）
- 面板包含：标题栏（icon + 关闭文字）+ 滚动 TextView + 进度指示

---

## 触摸事件的分发逻辑

不同状态下触摸处理方式不同，单 View 通过 `dispatchTouchEvent` 或 `onTouchEvent` 区分：

```
touchEvent(event)
  │
  ├── IDLE
  │     └── OnTouchListener（当前）：拖拽移动 / 点击切换 SELECT
  │
  ├── SELECT
  │     ├── 区域内部：画选择框（onTouchEvent）
  │     └── ACTION_OUTSIDE：缩回 IDLE
  │
  └── TRANSLATE
        ├── 面板内部：透传给滚动控件（ScrollView 接手）
        └── ACTION_OUTSIDE：缩回 IDLE
```

关键点：

- **IDLE → SELECT**：`updateViewLayout` 改参数，不是 `addView`
- **SELECT → TRANSLATE**：选择完成 → `updateViewLayout` 改参数，View 内部切换可见层
- **→ IDLE**：`updateViewLayout` 改回小球尺寸 + `gravity = TOP | START` + x/y 恢复到之前位置

---

## View 内部结构

```
<com.bubbletranslate.app.service.BubbleView  // 继承 FrameLayout>
  ├── Layer 0: 图标层  (visible when IDLE)
  │     └── ImageView (ic_bubble)
  │
  ├── Layer 1: 遮罩层  (visible when SELECT)
  │     └── 自定义绘制（选择框 + 指引文字 + 四角 dim）
  │
  └── Layer 2: 面板层  (visible when TRANSLATE)
        ├── 标题行：状态指示 + 关闭点击区
        ├── ScrollView
        │     └── translationTextView
        └── 底部操作区：收起按钮 / 状态条
```

三层的 `visibility` 互斥切换，不重复 inflate。

---

## 优势总结

| 对比项 | 当前方案（3 窗口） | 新方案（1 窗口） |
|--------|------------------|-----------------|
| `addView` 调用次数 | 3-4 次/操作周期 | 1 次（初始化时） |
| `removeView` 调用次数 | 2-3 次/操作周期 | 0 次（用 resize 代替） |
| inflate | 3 个 layout 文件 | 1 个 layout（初始化时 inflate） |
| 矢量图崩溃风险 | 存在（Service inflate） | 无（不需要 inflate） |
| 窗口管理复杂度 | 高（要同步 3 个 window） | 低（1 个 window） |
| 外部点击关闭 | 需要找关闭按钮 | 点外面自动缩回 |
| 拖拽后保留位置 | 需要记录 | 缩回时自动回到原位 |
