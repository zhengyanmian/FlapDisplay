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

> 建议粘贴顺序：先粘英文版，空一行后加 `---` 分隔线，再粘中文版。两版都完整，Modrinth 不会自动翻译。

### English version

Turn the Create Flap Display into a real screen.

**FlapDisplayPlus** is an add-on for Create: the flap display, which could originally only show text, can now play **images, GIFs, and videos**, show **live information**, and sync with **Net Music** to display album covers and line-synced lyrics.

The add-on sticks to vanilla rules: a flap display needs **kinetic power** to show media — no rotation, no picture.

## ✨ Features

### Media display

| Type | Formats |
|------|---------|
| Images | PNG / JPG / BMP / WebP |
| Animated | GIF |
| Video | MP4 / M4V / MOV / MKV / AVI / WebM / FLV / WMV / TS |

**Dual decoding backends**:

- **FFmpeg backend (recommended)** — full format & codec support (H.264 / H.265 / AV1). If missing, the mod **auto-downloads** it in-game (with chat notifications), or place `ffmpeg.exe` manually in the game directory or `config/flapdisplayplus/`.
- **JCodec fallback** — pure Java (MP4/H.264), no external programs needed. The mod automatically falls back when FFmpeg is unavailable, so videos play **anywhere**.

### Networking

- **URL direct links** (image hosts, CDNs, direct video URLs)
- **Progressive playback** — start watching before the download finishes
- **Caching** — repeat plays hit the cache; size cap & LRU cleanup configurable

### Live info

- Real-world time (customizable format)
- In-game time
- Weather
- TPS / MSPT (server performance)

### Net Music integration (optional)

With [Net Music](https://github.com/zhengyanmian/NetMusicDisplay) installed:

- Album covers
- Scrolling title & artist
- Line-synced lyrics, with translation
- NetEase Cloud Music / QQ Music search (VIP tags supported)
- QQ Music QR-code login
- Burn media records

### Sticks to vanilla rules

- Flap displays need **kinetic power** to show media — no rotation, no picture
- ESC menu pauses both video and audio
- Breaking the block or clearing the media stops playback instantly — no ghost sounds

## 🎮 Usage

1. Place a **Flap Display** and point a **Display Link** at it
2. Open the display link's screen and choose the **media source**
3. Pick a local image/video file, or paste a URL
4. Supply kinetic power — the flaps come alive

**Calibration** (optional):

```
/fdpcal x|y|inset|ix|iy|z <value>   # supports +n / -n relative adjustments
/fdpcal show | reset | save
```

Side-view misalignment → adjust `z` (depth). Front margin → `ix` / `iy`.

## ⚙️ Config

In-game config screen (Create-styled) under `Mods → FlapDisplayPlus`:

| Option | Default | Description |
|--------|---------|-------------|
| `media.videoMaxDim` | 720 | Max video dimension |
| `media.videoFps` | 24 | Max video framerate |
| `media.imageMaxDim` | 2048 | Max image dimension |
| `media.netCacheMaxMB` | 512 | Network cache cap (MB) |

## ⚠️ Notes

- The JCodec fallback only supports MP4/M4V/MOV (H.264); full format support needs FFmpeg
- Only **http(s) direct links** are supported — no video-site page links; `m3u8`/`dash` manifests are explicitly rejected
- Software decoding — lower `videoMaxDim` / `videoFps` for long, high-resolution videos

## 📄 License

MIT. Third-party: ZXing (Apache-2.0) · JCodec (BSD-2-Clause) · TwelveMonkeys (BSD-3-Clause)

**Author**: 枕燕眠 (zhengyanmian) · [GitHub](https://github.com/zhengyanmian/FlapDisplay)

---

### 中文版

让机械动力（Create）的翻牌显示器变成一块真正的"屏幕"。

**FlapDisplayPlus（翻牌万象）** 是一个 Create 附属模组：原版只能显示文字的翻牌显示器，现在可以播放**图片、GIF 动图、视频**，显示**实时信息**，并与**网络音乐机（Net Music）**联动显示歌曲封面与同步歌词。

模组严格遵循原版设定：翻牌显示器**需要动力（转速）**才会显示内容，无应力即不显示。

## ✨ 特性

### 媒体显示

| 类型 | 格式 |
|------|------|
| 图片 | PNG / JPG / BMP / WebP |
| 动图 | GIF |
| 视频 | MP4 / M4V / MOV / MKV / AVI / WebM / FLV / WMV / TS |

**双解码后端**：

- **FFmpeg 后端（推荐）**——完整格式与编码支持（H.264 / H.265 / AV1）。缺失时模组会在游戏内**自动下载**（聊天框提示），也可手动放置 `ffmpeg.exe` 到游戏目录或 `config/flapdisplayplus/`。
- **JCodec 兜底**——纯 Java（MP4/H.264），无需外部程序。FFmpeg 不可用时自动回退，视频**在任何环境都能播**。

### 网络媒体

- **URL 直链**（图床、CDN、视频直链）
- **边下边播**——下载未完成即可开始观看
- **缓存**——重复播放命中缓存；容量上限与 LRU 清理可配置

### 实时信息

- 现实时间（格式可自定义）
- 游戏时间
- 天气
- TPS / MSPT（服务器性能）

### 网络音乐机联动（可选）

安装[网络音乐机](https://github.com/zhengyanmian/NetMusicDisplay)后：

- 专辑封面
- 歌名 / 歌手滚动
- 逐行同步歌词，支持翻译双行
- 网易云 / QQ 音乐多平台搜索（VIP 标记）
- QQ 音乐扫码登录
- 刻录媒体唱片

### 遵守原版规则

- 翻牌显示器**需要转速**才显示媒体——无转速不显示
- ESC 菜单同时暂停视频与音频
- 挖掉方块或清空媒体立即停止播放——没有幽灵声音

## 🎮 使用

1. 放置**翻牌显示器**，把**显示链接器**对准它
2. 打开显示链接器界面，选择**媒体来源**
3. 选择本地图片/视频文件，或粘贴 URL
4. 提供动力——翻牌开始生动起来

**校准**（可选）：

```
/fdpcal x|y|inset|ix|iy|z <数值>   # 支持 +n / -n 相对调整
/fdpcal show | reset | save
```

侧面看有错位 → 调 `z`（深度）。四周留白 → 调 `ix` / `iy`。

## ⚙️ 配置

游戏内配置界面（机械动力风格），位于 `模组 → FlapDisplayPlus`：

| 选项 | 默认值 | 说明 |
|------|--------|------|
| `media.videoMaxDim` | 720 | 视频最大边长 |
| `media.videoFps` | 24 | 视频最大帧率 |
| `media.imageMaxDim` | 2048 | 图片最大边长 |
| `media.netCacheMaxMB` | 512 | 网络缓存上限（MB） |

## ⚠️ 已知限制

- JCodec 兜底仅支持 MP4/M4V/MOV（H.264）；完整格式支持需要 FFmpeg
- 仅支持 **http(s) 直链**，不支持视频网站页面链接；明确拒绝 `m3u8`/`dash` 清单
- 软解码——长时间高分辨率视频建议调低 `videoMaxDim` / `videoFps`

## 📄 许可

MIT。第三方组件：ZXing (Apache-2.0) · JCodec (BSD-2-Clause) · TwelveMonkeys (BSD-3-Clause)

**作者**：枕燕眠 (zhengyanmian) · [GitHub](https://github.com/zhengyanmian/FlapDisplay)
