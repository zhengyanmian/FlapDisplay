<p align="center">
  <img src="src/main/resources/flapdisplayplus_logo.png" width="128" alt="翻牌万象"/>
</p>

<h1 align="center">翻牌万象 FlapDisplayPlus</h1>

<p align="center">
  机械动力（Create）翻牌显示器扩展附属模组 —— 让翻牌显示<strong>图片、动图、视频</strong>与<strong>实时信息</strong>，还能与<strong>网络音乐机</strong>联动显示封面与歌词。
</p>

<p align="center">
  <a href="https://www.minecraft.net/"><img src="https://img.shields.io/badge/Minecraft-1.21.1-green" alt="Minecraft"/></a>
  <a href="https://neoforged.net/"><img src="https://img.shields.io/badge/NeoForge-21.1+-orange" alt="NeoForge"/></a>
  <a href="https://www.createmod.net/"><img src="https://img.shields.io/badge/Create-6.0.10+-blue" alt="Create"/></a>
  <img src="https://img.shields.io/badge/双端-客户端+%20服务端-purple" alt="双端"/>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow" alt="MIT"/></a>
</p>

---

## ✨ 功能特性

### 一、媒体显示

翻牌显示器不再只能显示文字 —— 它现在是一块真正的屏幕。

| 类型 | 格式 | 解码后端 |
|------|------|----------|
| 图片 | PNG / JPG / BMP / WebP | 内置 ImageIO + TwelveMonkeys |
| 动图 | GIF | 内置逐帧解析 |
| 视频 | MP4 / M4V / MOV / **MKV / AVI / WebM / FLV / WMV / TS** | **FFmpeg**（推荐）或 JCodec 兜底 |

- **双解码后端**：自动优先使用 FFmpeg（性能强、格式全）；没有 FFmpeg 时自动退回纯 Java 的 JCodec（MP4/H.264），**任何环境都能播**
- **FFmpeg 自动下载**：缺失时进入世界自动从镜像下载（聊天框提示开始/完成/失败），也可手动放置 `ffmpeg.exe`
- **编码格式**：FFmpeg 后端支持 H.264 / H.265 / AV1 等主流编码
- **自动缩放**：按翻牌尺寸等比适配，最大分辨率与帧率可配置
- **WebP 兜底**：被错误命名为 `.png` / `.jpg` 的 WebP 也能正常显示

### 二、网络媒体

支持 **URL 直链**播放，实现**边下边播**：

- **直链解析**：图床 / CDN / 直链 mp4 等（HTTP Content-Type 探测）
- **边下边播**：下载未完成即可开始播放，无需等待整个文件
- **缓存管理**：同一链接二次播放直接命中缓存，容量上限可配置，超出按 LRU 清理

### 三、实时信息源

- **现实时间**：系统时钟，支持自定义格式
- **游戏时间**：世界天数与时刻
- **天气**：当前天气状态
- **TPS / MSPT**：服务器性能监控

### 四、网络音乐机联动（可选）

安装 **Net Music（网络音乐机）** 后自动启用，未安装则静默降级：

- **专辑封面**：实时显示当前播放曲目封面
- **歌名 / 歌手**：滚动显示
- **歌词**：逐行同步，支持翻译歌词双行
- **多平台搜索**：网易云音乐 / QQ 音乐（VIP 歌曲红字标识）
- **QQ音乐扫码登录**：支持扫码与换号
- **刻录唱片**：在刻录机界面直接制作「媒体唱片」

### 五、遵守原版规则

- 翻牌显示器**需要转速（动力）**才会显示媒体，断电即停止 —— 与原版翻牌行为一致
- 打开游戏菜单（ESC）自动暂停画面与声音
- 清空媒体、拆除方块立即停止播放，不留残留音画

---

## 📦 安装

| 项目 | 版本 |
|------|------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1+ |
| 机械动力 Create | 6.0.10+（**必需**） |
| Net Music 网络音乐机 | 任意（**可选**） |
| Java | 21 |

1. 安装 NeoForge 21.1+ 与 Create 6.0.10+
2. 将 `flap-display-plus-x.x.x.jar` 放入 `.minecraft/mods/`
3. **客户端与服务端都需安装**（双端模组）
4. 需要音乐联动再放入 Net Music 的 jar

> **FFmpeg（可选）**：自动下载失败的网络环境下，可手动将 `ffmpeg.exe` 放到游戏根目录或 `config/flapdisplayplus/` 下。

---

## 🎮 使用

### 显示媒体

1. 放置**翻牌显示器**，用**显示链接器**指向它
2. 打开显示链接器界面 → 选择**媒体源**
3. 在媒体界面中从**本地文件**选择图片/视频，或粘贴 URL 直链
4. 确认后翻牌立即开始渲染；给翻牌供能（转动）即可看到画面

### 媒体校准（可选）

媒体画面与面板的细微对齐可用指令调整：

```
/fdpcal x|y|inset|ix|iy|z <值>    # 绝对值或 +n / -n 相对值
/fdpcal show                      # 查看当前校准值
/fdpcal reset                     # 重置
/fdpcal save                      # 保存到配置
```

> 从侧面看媒体与面板有错位时，调 `z`（深度）；正面边距用 `ix / iy`。

---

## 🔧 配置

游戏内 `Mods → 翻牌万象 → 配置`，机械动力风格界面。常用项：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `media.videoMaxDim` | 720 | 视频最大边长 |
| `media.videoFps` | 24 | 视频最高帧率 |
| `media.imageMaxDim` | 2048 | 图片最大边长 |
| `media.netCacheMaxMB` | 512 | 网络缓存上限 (MB) |
| `media.panelDepth` | -0.49 | 媒体面深度偏移（校准用） |

---

## 🛠 从源码构建

需要 JDK 21，依赖全部来自 Maven 坐标，无需手动准备第三方 jar：

```bash
export JAVA_HOME="/path/to/jdk-21"
./gradlew build -x test
# 产物：build/libs/flap-display-plus-1.0.0.jar
```

> **Net Music 软联动**：Net Music 不在公共 Maven 仓库，构建时若 `libs/netmusic.jar` 缺失会自动跳过联动部分，其余功能完整编译。

---

## ⚠️ 已知限制

- JCodec 兜底后端仅支持 **MP4 / M4V / MOV（H.264）**；完整格式支持需 FFmpeg
- 仅支持 **http(s) 直链**，不支持视频网站页面链接；`m3u8` / `dash` 流式清单被显式拒绝
- 视频为软件解码，高分辨率长视频建议在配置中降低最大边长与帧率

---

## 📄 许可

本项目采用 **MIT License**。

第三方库：[ZXing](https://github.com/zxing/zxing)（Apache-2.0）· [JCodec](http://jcodec.org/)（BSD-2-Clause）· [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys)（BSD-3-Clause）

---

## 👤 作者

**枕燕眠** ([@zhengyanmian](https://github.com/zhengyanmian))
