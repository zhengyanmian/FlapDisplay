# Modrinth 页面资料 — FlapDisplayPlus

> 创建项目时逐项填写；正文（Body）直接整段粘贴到描述编辑器。

---

## 一、项目元数据（创建表单）

| 字段 | 内容 |
|------|------|
| Project name | FlapDisplayPlus |
| Project slug | `flapdisplayplus` |
| Project title（显示名） | FlapDisplayPlus 翻牌万象 |
| Summary（≤256字符） | Turn Create's Flap Displays into real screens — images, GIFs, videos, live info, and Net Music album art & lyrics. 让翻牌显示器播放图片/视频/实时信息/歌词。 |
| Project icon | `src/main/resources/flapdisplayplus_logo.png`（仓库内 128×128） |
| Categories | Technology（主）· Decoration |
| Environment | Client & Server（必选双端） |
| License | MIT |
| Source URL | https://github.com/zhengyanmian/FlapDisplay |
| Issues URL | https://github.com/zhengyanmian/FlapDisplay/issues |
| Loaders | NeoForge |
| Game versions | 1.21.1 |
| Dependencies（required） | create（Create，6.0.10+） |
| Dependencies（optional） | netmusic（Net Music，若百科/平台收录） |
| Loader version | NeoForge 21.1.x |
| Status | Listed / Public |

**Gallery 建议**：上传游戏内截图 3–5 张 —— ①翻牌显示视频画面 ②显示歌词/封面 ③显示实时时间/TPS ④媒体选择界面（黄铜 GUI）

---

## 二、正文（Body，Markdown 直接粘贴）

让机械动力（Create）的翻牌显示器变成一块真正的"屏幕"。

**FlapDisplayPlus（翻牌万象）** 是一个 Create 附属模组：原版只能显示文字的翻牌显示器，现在可以播放**图片、GIF 动图、视频**，显示**实时信息**，并与**网络音乐机（Net Music）**联动显示歌曲封面与同步歌词。

模组严格遵循原版设定：翻牌显示器**需要动力（转速）**才会显示内容，断电即停。

## ✨ Features 特性

### Media 媒体显示

| Type | Formats |
|------|---------|
| Images | PNG / JPG / BMP / WebP |
| Animated | GIF |
| Video | MP4 / M4V / MOV / MKV / AVI / WebM / FLV / WMV / TS |

**Dual decoding backends 双解码后端**：

- **FFmpeg backend (recommended)** — full format & codec support (H.264 / H.265 / AV1). If missing, the mod **auto-downloads** it in-game (with chat notifications), or place `ffmpeg.exe` manually in the game directory or `config/flapdisplayplus/`.
- **JCodec fallback** — pure Java (MP4/H.264), no external programs needed. The mod automatically falls back when FFmpeg is unavailable, so videos play **anywhere**.

### Networking 网络媒体

- **URL direct links** (image hosts, CDNs, direct video URLs)
- **Video site links** (Bilibili, YouTube, …) via your locally installed `yt-dlp` — no site-specific reverse engineering is built in; direct links work without yt-dlp
- **Progressive playback** — start watching before the download finishes
- **Caching** — repeat plays hit the cache; size cap & LRU cleanup configurable

### Live info 实时信息

- Real-world time（现实时间，格式可自定义）
- In-game time（游戏时间）
- Weather（天气）
- TPS / MSPT（服务器性能）

### Net Music integration 音乐联动（optional 可选）

With [Net Music](https://github.com/zhengyanmian/NetMusicDisplay) installed:

- Album covers（专辑封面）
- Scrolling title & artist（歌名/歌手滚动）
- Line-synced lyrics, with translation（逐行同步歌词，支持翻译双行）
- NetEase Cloud Music / QQ Music search（多平台搜索，VIP 标记）
- QQ Music QR-code login（扫码登录）
- Burn media records（刻录媒体唱片）

### Sticks to vanilla rules 遵守原版规则

- Flap displays need **kinetic power** to show media — no rotation, no picture（无转速不显示）
- ESC menu pauses both video and audio
- Breaking the block or clearing the media stops playback instantly — no ghost sounds

## 🎮 Usage 使用

1. Place a **Flap Display** and point a **Display Link** at it
2. Open the display link's screen and choose the **media source**
3. Pick a local image/video file, or paste a URL
4. Supply kinetic power — the flaps come alive

**Calibration 校准**（可选）：

```
/fdpcal x|y|inset|ix|iy|z <value>   # supports +n / -n relative adjustments
/fdpcal show | reset | save
```

Side-view misalignment → adjust `z` (depth). Front margin → `ix` / `iy`.

## ⚙️ Config 配置

In-game config screen (Create-styled) under `Mods → FlapDisplayPlus`:

| Option | Default | Description |
|--------|---------|-------------|
| `media.videoMaxDim` | 720 | Max video dimension |
| `media.videoFps` | 24 | Max video framerate |
| `media.imageMaxDim` | 2048 | Max image dimension |
| `media.netCacheMaxMB` | 512 | Network cache cap (MB) |

## ⚠️ Notes 已知限制

- The JCodec fallback only supports MP4/M4V/MOV (H.264); full format support needs FFmpeg
- Site parsing requires a user-installed `yt-dlp`; `m3u8`/`dash` manifests are explicitly rejected
- Software decoding — lower `videoMaxDim` / `videoFps` for long, high-resolution videos

## 📄 License 许可

MIT. Third-party: ZXing (Apache-2.0) · JCodec (BSD-2-Clause) · TwelveMonkeys (BSD-3-Clause)

---

**作者 Author**: 枕燕眠 (zhengyanmian) · [GitHub](https://github.com/zhengyanmian/FlapDisplay)
