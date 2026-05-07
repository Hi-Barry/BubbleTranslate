<p align="center">
  <img src="app/src/main/res/drawable/ic_bubble.xml" width="80" height="80" alt="BubbleTranslate" 
       style="display:none" onerror="this.style.display='none'">
</p>

<h1 align="center">BubbleTranslate · 泡泡翻译</h1>
<p align="center">
  <strong>Select any text on screen. Translate it instantly.</strong>
</p>

<p align="center">
  <a href="https://github.com/Hi-Barry/BubbleTranslate/releases"><img src="https://img.shields.io/github/v/release/Hi-Barry/BubbleTranslate?color=4CAF50&label=Release" alt="Release"></a>
  <a href="https://github.com/Hi-Barry/BubbleTranslate/blob/master/LICENSE"><img src="https://img.shields.io/badge/License-MIT-green.svg" alt="License"></a>
  <a href="doc/README_zh.md">中文文档</a>
</p>

---

## What is BubbleTranslate?

BubbleTranslate is an Android floating overlay app that lets you **select any text on your screen and translate it instantly** — no app switching, no copy-paste, no screenshots to crop manually.

Supports two translation modes:

- **🤖 Remote (LLM)** — Powered by the **Kimi K2.6 Vision API**, understands text directly from screen pixels. Great for complex layouts, mixed languages, and UI elements.
- **🌐 Local (Google)** — Uses **Google Translate** for text translation. No API key needed — works for quick lookups and simple text.

## How It Works

1. **Start** — grant overlay + screen capture permissions, configure your translation mode
2. **Tap** — a green bubble appears at the screen edge; tap it on any app
3. **Select** — drag to draw a rectangle around the text you want to translate
4. **Translate** — the selected area is processed and the result appears in a floating panel
5. **Drag** — the translation panel can be dragged anywhere on screen; tap anywhere outside to dismiss

## Features

- **Floating overlay** — works on top of any app (browser, reader, chat, game)
- **Dual translation modes** — Local (Google, no API key) / Remote (LLM with Vision)
- **Vision LLM** — no OCR needed; Kimi K2.6 reads text directly from pixels
- **Google Translate** — text-based translation, no API key required
- **Streaming translation** — see results appear in real time (remote mode)
- **Box-drag selection** — precise area selection with corner handles
- **Adjustable bubble opacity** — 10%–100% via SeekBar
- **Copy to clipboard** — one-tap copy from the translation panel
- **Dark mode support** — DayNight theme, readable in any lighting

## Getting Started

### Prerequisites

- Android 8.0+ (API 26)
- For Remote mode: A [Moonshot API key](https://platform.moonshot.cn) (Kimi)
- For Local mode: Internet connection (Google Translate API)

### Installation

Download the latest APK from [Releases](https://github.com/Hi-Barry/BubbleTranslate/releases).

### Setup

1. Open the app
2. Select your preferred translation mode
3. For Remote mode: enter your Kimi API key
4. Tap **Start Translation Bubble**
5. Grant overlay permission when prompted
6. Grant screen capture permission
7. The green bubble appears — you're ready!

## License

MIT

---

The full story of this project's development — 43 commits, 6 critical bug fixes, and 3 iterations on the coordinate-alignment problem — is documented in [BubbleTranslate-Development-History.md](BubbleTranslate-Development-History.md).
