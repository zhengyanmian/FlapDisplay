# 翻牌万象 FlapDisplayPlus

> 机械动力（Create）翻牌显示器的扩展附属模组 —— 让翻牌显示**图片、动图、视频**，以及**实时信息**，还能与**网络音乐机**联动显示封面与歌词。

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1+-orange)](https://neoforged.net/)
[![Create](https://img.shields.io/badge/Create-6.0.10+-blue)](https://www.createmod.net/)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

---

## 功能特性

### 一、媒体显示

翻牌显示器不再只能显示文字 —— 它现在是一块真正的屏幕。

| 类型 | 格式 | 说明 |
|------|------|------|
| 图片 | PNG / JPG / JPEG / GIF / BMP / WebP | 静态图与 GIF 动图 |
| 视频 | MP4 / M4V / MOV | 纯 Java 解码（JCodec），无需外部解码器 |

- **GIF 动图**：自动逐帧解析并按帧率播放
- **视频播放**：H.264 解码，可配置最大分辨率与帧率以适配性能
- **WebP 兜底**：被错误命名为 `.png` / `.jpg` 的 WebP 文件也能正常显示（TwelveMonkeys）
- **自动缩放**：按翻牌尺寸等比适配，支持清晰度优先 / 性能优先

### 二、网络媒体（重点）

支持 **URL 直链**与**视频网站链接**，并实现**边下边播**。

```
┌─────────────────────────────────────────────────────────────┐
│  MediaResolverManager                                        │
│                                                              │
│   ① DirectLinkResolver   直链（图床/CDN/直链 mp4）           │
│      └ 扩展名判定 + HTTP HEAD 探测 Content-Type              │
│                                                              │
│   ② YtDlpResolver        视频网站（B站/ytb 等）              │
│      └ 调用【用户本机】yt-dlp 外部进程拿直链                  │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│  NetCache      固定路径缓存 + .complete 标记 + LRU 容量控制  │
│  StreamDownloader    后台流式下载 + 条件等待                 │
│  StreamingFileChannel  实现 jcodec SeekableByteChannel       │
└─────────────────────────────────────────────────────────────┘
                            ↓
                        边下边播
```

- **边下边播**：下载未完成时即可开始播放，播放进度自动等待数据到达，无需等待整个文件下载完
- **断点复用**：同一链接二次播放直接命中缓存
- **容量控制**：缓存目录与最大占用可在配置界面调整，超出后按 LRU 自动清理

#### 关于合规边界（重要）

本模组**不实现**任何站点的签名算法、防盗链绕过或私有 API 逆向。

视频网站解析依赖**用户自行安装**的 `yt-dlp` 外部程序：

- 通过 `ProcessBuilder` 传入**参数数组**（不经过 shell），杜绝命令注入
- 未安装 `yt-dlp` 时，直链功能**完全不受影响**，仅站点链接不可用

### 三、实时信息源

翻牌可直接显示动态信息，脱离静态文本：

- **现实时间**：系统时钟，支持自定义时间格式
- **游戏时间**：Minecraft 世界天数与时刻
- **天气**：当前生物群系天气状态
- **TPS**：服务器每刻 Tick 数（性能监控）

### 四、网络音乐机联动（可选）

安装 **Net Music（网络音乐机）** 后自动启用，未安装则静默降级、不影响任何其他功能。

- **专辑封面**：实时显示当前播放曲目的封面
- **歌名/歌手**：滚动显示
- **歌词**：逐行同步（含翻译歌词双行显示）
- **多平台搜索**：网易云音乐 / QQ 音乐
- **登录支持**：二维码扫码登录、邮箱密码登录、手机验证码登录

---

## 安装

### 环境要求

| 项目 | 版本 |
|------|------|
| Minecraft | 1.21.1 |
| NeoForge | 21.1 或更高 |
| 机械动力 Create | 6.0.10 或更高（**必需**） |
| Net Music 网络音乐机 | 任意（**可选**，用于音乐联动） |
| Java | 21 |

### 步骤

1. 安装 NeoForge 21.1+ 与机械动力 Create 6.0.10+
2. 将 `flapdisplayplus-x.x.x.jar` 放入 `.minecraft/mods/`
3. **客户端与服务端都需安装**（本模组为双端模组）
4. 若需要音乐联动，再放入 Net Music 的 jar

### 可选：启用视频网站解析

如需播放 B 站等视频网站的链接，需自行安装 `yt-dlp`：

```bash
# Windows (winget)
winget install yt-dlp

# 或从 GitHub Releases 下载 yt-dlp.exe 放到任意目录，
# 然后在此模组的配置界面中填写该 exe 的完整路径
```

配置路径：游戏内 `Mods → 翻牌万象 → 配置 → 媒体 → yt-dlp 路径`

---

## 使用

### 给翻牌设置媒体

1. 放置**翻牌显示器**（Flap Display），用**显示链接器**（Display Link）指向它
2. 手持**翻牌万象配置工具**（或按配置的快捷键）打开媒体选择界面
3. 界面中可：
   - 从**本地文件**中选择图片/视频
   - 在**链接输入框**粘贴 URL 直链或视频网站链接，点击「添加链接」
4. 设置后翻牌立即开始渲染

### 显示实时信息

在显示链接器指向翻牌后，选择对应信息源：

- `现实时间` / `游戏时间` / `天气` / `TPS`

---

## 从源码构建

### 前置

- JDK 21
- 无需手动准备第三方 jar —— 依赖已全部改为 Maven 坐标

### 构建

```bash
# Linux / macOS / Git Bash
export JAVA_HOME="/path/to/jdk-21"
./gradlew build -x test

# Windows
set JAVA_HOME=C:\path\to\jdk-21
gradlew.bat build -x test
```

产物位于 `build/libs/flapdisplayplus-1.0.0.jar`

### 关于 Net Music 软联动依赖

Net Music 不在任何公共 Maven 仓库中，需**自行准备**：

```bash
# 将 Net Music 的 jar 放到此处（文件名固定）
libs/netmusic.jar
```

**该文件缺失时不会导致构建失败** —— 构建脚本会自动跳过软联动部分，其余功能完整编译。这样保证了仓库中无需提交任何第三方二进制文件。

---

## 项目结构

```
src/main/java/com/flapdisplayplus/
├── FlapDisplayPlus.java            主入口
├── ModDisplaySources.java          显示源注册
├── api/                            对外 API
├── client/                         客户端渲染与媒体管理
│   ├── MediaManager.java           本地媒体清单（格式单一数据源）
│   ├── VideoPlayer.java            视频解码播放
│   └── CuckooClockMediaScreen.java 媒体选择界面
├── net/                            【网络媒体】
│   ├── MediaResolver.java          解析器接口
│   ├── DirectLinkResolver.java     直链解析
│   ├── YtDlpResolver.java          站点解析（外挂 yt-dlp）
│   ├── NetCache.java               缓存管理
│   ├── StreamDownloader.java       流式下载
│   ├── StreamingFileChannel.java   边下边播通道
│   └── NetMediaManager.java        网络媒体总控
├── music/                          【Net Music 联动】
│   ├── netease/                    网易云 API
│   ├── qq/                         QQ 音乐登录
│   ├── search/                     多平台搜索
│   ├── source/                     显示源（封面/歌名/歌词）
│   ├── arm/                        动力臂交互
│   └── mixin/                      续播等 Mixin
├── mixin/                          Create 织入
├── source/                         信息源（时间/天气/TPS）
├── network/                        网络包
└── config/                         配置
```

---

## 技术说明

### 为什么第三方库要「平铺嵌入」

依常规做法应使用 `jarJar` 嵌套打包第三方库，但嵌套 jar 会触发 **NeoForge SecureJar 读取异常**（`invalid stored block lengths`），导致整个模组加载失败。

因此本项目将 zxing / jcodec / TwelveMonkeys 的 class 文件**平铺**进 jar 根目录（见 `build.gradle` 的 `jar` 任务）。同时排除 `META-INF/**` 以避免多个 jar 的 SPI 服务文件（`ImageReaderSpi`）同名冲突。

### 边下边播的三个关键点

1. **缓存文件使用固定路径 + `.complete` 标记**
   不用 `.part` + 重命名方案 —— 重命名会让**正在播放的文件句柄失效**。

2. **`StreamingFileChannel.size()` 返回服务器声明的总长度**
   若返回当前已下载长度，解封装器无法定位到文件尾部的 `moov` 索引，MP4 将无法解析。

3. **实现 jcodec 自己的 `SeekableByteChannel` 接口**
   注意是 `org.jcodec.common.io.SeekableByteChannel`（方法名为 `setPosition` / `truncate`），而非 `java.nio` 的同名接口。

### 性能调优

视频播放前会对 YUV 平面层**先降采样**再转为 `BufferedImage`，避免在原生分辨率下创建大缓冲（1080p 每帧约 8MB）。可在配置界面调整：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `media.videoMaxDim` | 1024 | 视频最大边长 |
| `media.videoFps` | 24 | 视频最高帧率 |
| `media.imageMaxDim` | 2048 | 图片最大边长 |
| `media.netCacheMaxMB` | 512 | 网络缓存上限 (MB) |

---

## 已知限制

- 视频格式仅支持 **MP4 / M4V / MOV**（JCodec 提供 MP4/MKV/FLV 解封装，但**不含 AVI**）
- 视频编解码仅支持 **H.264**
- 站点解析需**用户自备 `yt-dlp`**，且 `m3u8` / `dash` 流式清单会被显式拒绝（本项目按整个文件下载）

---

## 许可

本项目采用 **MIT License**。

第三方库版权归属：
- [ZXing](https://github.com/zxing/zxing) — Apache-2.0
- [JCodec](http://jcodec.org/) — BSD-2-Clause
- [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) — BSD-3-Clause

---

## 作者

**枕燕眠** ([@zhengyanmian](https://github.com/zhengyanmian))
