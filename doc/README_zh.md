<h1 align="center">BubbleTranslate · 泡泡翻译</h1>
<p align="center">
  <strong>圈选屏幕上任意文字，即时翻译。</strong>
</p>

<p align="center">
  <a href="https://github.com/Hi-Barry/BubbleTranslate/releases"><img src="https://img.shields.io/github/v/release/Hi-Barry/BubbleTranslate?color=4CAF50&label=%E7%89%88%E6%9C%AC" alt="Release"></a>
  <a href="https://github.com/Hi-Barry/BubbleTranslate/blob/master/LICENSE"><img src="https://img.shields.io/badge/License-MIT-green.svg" alt="License"></a>
  <a href="../README.md">English</a>
</p>

---

## 这是什么？

BubbleTranslate 是一个 Android 悬浮窗翻译工具。你可以在**任何 App 上圈选文字区域，立刻得到翻译结果**——不用切 App、不用复制粘贴、不用手动截图裁剪。

支持两种翻译模式：

- **🤖 远程模式（大语言模型）** — 基于 Kimi K2.6 视觉模型，直接从屏幕像素中识别文字并翻译。适合复杂排版、混合语言、UI 元素。
- **🌐 本地模式（Google 翻译）** — 使用 Google 免费翻译 API，无需 API Key。适合快速查阅和简单文字翻译。

## 使用方式

| 步骤 | 操作 |
|------|------|
| 1 | 点击屏幕边缘的绿色悬浮泡泡 |
| 2 | 在需要翻译的文字区域画一个框 |
| 3 | 翻译结果立刻显示在弹出面板中 |
| 4 | 拖动面板任意位置调整位置 — 点面板外关闭 |

## 功能

- ✅ **不打断当前操作** — 覆盖在任意 App 上方（浏览器、PDF、聊天等）
- ✅ **双模式翻译** — 本地 Google 翻译（无需 Key）/ 远程 LLM 翻译
- ✅ **任意图文** — Kimi K2.6 能直接理解屏幕像素（远程模式）
- ✅ **Google 翻译** — 纯文本翻译，无需 API Key（本地模式）
- ✅ **面板任意位置可拖拽** — 无死角，想放哪放哪
- ✅ **一键复制** — 标题栏复制按钮，翻译结果直达剪贴板
- ✅ **泡泡透明度可调** — 10%–100% 滑块调节
- ✅ **深色模式** — 自动跟随系统主题
- ✅ **Android 8.0 以上** — 覆盖主流设备

## 安装

1. 从 [Releases](https://github.com/Hi-Barry/BubbleTranslate/releases) 下载最新 APK
2. 安装后打开 App
3. 选择翻译模式 — 远程模式需要填入 **Kimi API Key**，本地模式无需
4. 点击 **启动**，授权屏幕录制权限
5. 绿色泡泡出现 — 可以开始了！

> ℹ️ 远程模式需要 [月之暗面 Kimi API Key](https://platform.moonshot.cn)。本地模式使用 Google 免费翻译 API。

## 架构

```
FloatingBubbleService (LifecycleService)
 ├── BubbleView (FrameLayout, 单窗口状态机)
 │    ├── IDLE:       可拖拽的泡泡图标
 │    ├── SELECT:     全屏遮罩 + 矩形选择
 │    └── TRANSLATE:  翻译结果面板
 ├── ScreenshotManager  → MediaProjection 屏幕截图
 ├── KimiApiClient      → Kimi K2.6 Vision API (远程模式)
 └── GoogleTranslateClient → Google Translate API (本地模式)
```

详见 [ARCHITECTURE.md](../ARCHITECTURE.md)。

## 自行构建

```bash
# 调试版（无需签名）
./gradlew assembleDebug

# 正式版（需要签名配置）
./gradlew assembleRelease
```

推送 `v*` 标签后，CI 会自动构建签名的正式版 APK。

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Kotlin |
| 最低 SDK | Android 8.0 (API 26) |
| 目标 SDK | Android 14 (API 34) |
| HTTP 客户端 | OkHttp 4 |
| 异步 | Kotlin Coroutines |
| 屏幕截图 | MediaProjection + VirtualDisplay |
| 文字识别 | ML Kit Text Recognition (本地模式) |
| 翻译 API | Kimi K2.6 + Google Translate |

## 开发历程

这个项目从零到正式发布，经历了 43 次提交、6 次关键 Bug 修复、3 轮坐标对齐方案迭代。完整记录见 [BubbleTranslate-Development-History.md](../BubbleTranslate-Development-History.md)。

## 许可证

MIT © [Barry](https://github.com/Hi-Barry)
