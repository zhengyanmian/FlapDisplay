# FlapDisplayPlus 重构方案

> 版本：草案 v1　　日期：2026-08-28
> 范围：1.21.1 NeoForge 分支（`flap-display-plus/`，82 文件 / 10542 行）
> 原则：**先出方案再动手，已发布功能零破坏**

---

## 0. 一页速览

调研时从 Create 6.0.10 的**字节码**里挖到一条关键事实，它直接推翻了我们现有代码里一个核心假设：

> `DisplayLinkBlockEntity.activeSource` 是 **`public`** 字段，不是 private。

我们为此写了一个 80 行的 `DisplayLinkReflection` 反射类，还配了长篇注释解释「为什么必须用反射」。**反射在这一处完全不需要**——直接 `link.activeSource = ourSource;` 就行。这条链上连带可以砍掉约 90 行代码和一个 Mixin。

四条重构主线（对应用户选定的四个方向）各自的结论先说：

| 方向 | 现状 | 结论 | 优先级 |
|---|---|---|---|
| **架构分层清理** | `music/` 包 50 个类混在主工程里，占近 60% 代码 | 反向依赖干净（只依赖 `FlapDisplayPlus.MODID` 一个常量），**可零成本拆包** | P1 |
| **借鉴成熟实现** | 自研反射/自研 Mixin 注入绕过 Create 机制 | 改用 Create 官方扩展点，**砍掉 1 个 Mixin + 1 个反射类** | **P0** |
| **性能与资源管理** | 单帧 4 遍全帧 CPU 拷贝；2126 个第三方类平铺进 jar | 纹理上传路径可省 2 遍拷贝；无全局执行器 | P1 |
| **API 与扩展性** | `api/` 只有 1 个接口，扩展全靠 Mixin | 建立 `IDisplayContentProvider` 式扩展点 | P2 |

**建议执行顺序**：P0（去掉反射+Mixin）→ P1（拆包 + 性能）→ P2（扩展 API）。每步独立可验证、可单独发布。

---

## 1. 调研结论：外部项目怎么做的

### 1.1 MediaPlayer（hackermdch）—— 解码与渲染的「正确姿势」

来源：<https://github.com/hackermdch/MediaPlayer>（MC百科 <https://www.mcmod.cn/class/15535.html>）

| 项 | 值 |
|---|---|
| 定位 | 基于 FFmpeg 的 MC 解码库 |
| 平台 | Fabric / Forge / NeoForge，**仅 Windows x64** |
| 许可 | LGPL-3.0（**与我们 MIT 不兼容**，只能借鉴思路，不能抄代码） |
| 最后更新 | 2025-05-18（已一年多未更新） |
| 规模 | Java 侧仅 12 个类 |

**它的核心做法（值得借鉴）**：

```java
// 解码器把帧【直接写进 GL 纹理】，Java 侧不做像素搬运
decoder.fetch();
bufferSource.getBuffer(VideoRenderType.create(decoder.frame));
```

- `VideoFrame extends AbstractTexture` —— 帧本身就是纹理
- JNI 原生解码 → 直接写 GL 纹理 ID，**Java 侧零 CPU 拷贝**
- 自定义 `RenderType` + `DefaultVertexFormat.NEW_ENTITY`，用 `VertexConsumer` 渲染
- 硬件加速四级降级：`CUDA → D3D12VA → D3D11VA → NONE`
- `Cleaner` 管理原生内存

**对我们的意义**：这是性能上的天花板参照。但它绑定 LGPL-3.0 + 仅 Windows x64 + FFmpeg 原生库，**我们不应改用这条路**（见 §3.1 决策）。

### 1.2 Create 官方的 Display Link 扩展模型

来源：Create 6.0.10 字节码（本机 `~/.gradle/caches/.../create-1.21.1-6.0.10-280-slim.jar`）+ <https://deepwiki.com/Creators-of-Create/Create/5.4-display-link-system>

**`DisplaySource` 的完整扩展点**（`com.simibubi.create.api.behaviour.display.DisplaySource`）：

```java
public static final SimpleRegistry.Multi<Block, DisplaySource> BY_BLOCK;
public static final SimpleRegistry.Multi<BlockEntityType<?>, DisplaySource> BY_BLOCK_ENTITY;
public static final List<MutableComponent> EMPTY;
public static final MutableComponent EMPTY_LINE;

public abstract List<MutableComponent> provideText(DisplayLinkContext, DisplayTargetStats);
public void transferData(DisplayLinkContext, DisplayTarget, int);
public void onSignalReset(DisplayLinkContext);
public void populateData(DisplayLinkContext);
public int  getPassiveRefreshTicks();      // 默认 100
public boolean shouldPassiveReset();       // 默认 true
public List<List<MutableComponent>> provideFlapDisplayText(DisplayLinkContext, DisplayTargetStats);
public void loadFlapDisplayLayout(DisplayLinkContext, FlapDisplayBlockEntity, FlapDisplayLayout, int);
public void initConfigurationWidgets(DisplayLinkContext, ModularGuiLineBuilder, boolean);

public static DisplaySource get(ResourceLocation);
public static List<DisplaySource> getAll(LevelAccessor, BlockPos);
```

**`DisplayTarget`**（`...display.DisplayTarget`）：

```java
public static final SimpleRegistry<Block, DisplayTarget> BY_BLOCK;
public static final SimpleRegistry<BlockEntityType<?>, DisplayTarget> BY_BLOCK_ENTITY;
public abstract void acceptText(int, List<MutableComponent>, DisplayLinkContext);
public abstract DisplayTargetStats provideStats(DisplayLinkContext);
public AABB getMultiblockBounds(LevelAccessor, BlockPos);
public boolean requiresComponentSanitization();
```

**`DisplayLinkBlockEntity` 关键字段**（字节码 `javap -p` 实录）：

```java
public    BlockPos targetOffset;
public    DisplaySource activeSource;      // ← public！我们却用反射读它
public    DisplayTarget activeTarget;
public    int targetLine;
public    int refreshTicks;

public void tick();                        // 每 tick
public void tickSource();                  // 读红石 POWERED，未通电直接 return
public void updateGatheredData();          // 真正推送
```

**`tickSource()` 字节码逻辑**（这是理解「为什么要绕过红石」的钥匙）：

```java
public void tickSource() {
    refreshTicks = 0;
    if (getBlockState().getOptionalValue(DisplayLinkBlock.POWERED).orElse(true)) {
        return;                     // ← 未通电（POWERED=false）直接返回，不推数据
    }
    if (!level.isClientSide) {
        updateGatheredData();
    }
}
```

**`tick()` 字节码逻辑**：

```java
public void tick() {
    super.tick();
    if (isVirtual()) return;
    if (activeSource == null) return;         // ← 源为空时什么都不做
    if (level.isClientSide) return;
    refreshTicks--;
    if (refreshTicks > 0) return;
    activeSource.getPassiveRefreshTicks() → refreshTicks;
    if (!activeSource.shouldPassiveReset()) {
        tickSource();                          // ← 只有「不重置」的源才会走被动刷新
    }
}
```

**结论（极其重要）**：官方机制本来就支持「源自动被动刷新」。`DisplaySource` 只要覆盖：

```java
@Override public boolean shouldPassiveReset() { return false; }   // 不被清空
@Override public int getPassiveRefreshTicks() { return 20; }      // 每 20 tick 刷一次
```

Create 的 `tick()` 就会每 20 tick 自动调 `tickSource()` → `updateGatheredData()`。

而 `tickSource()` 里唯一门槛是 `DisplayLinkBlock.POWERED`（红石通电后 **POWERED=true 反而 return**，即「通电时暂停被动刷新」——Create 的设计是通电后由 `onNoMorePowered` 等事件驱动）。

**我们已经覆盖了这两个方法**（`FlapDisplayMediaSource.java:140-150`），**但我们依然从 `tick()` 注入去手动调 `updateGatheredData()`**——这段 Mixin 里唯一真正无法用官方机制表达的需求是「翻牌无转速时清除图片」。

### 1.3 Create: IDLX —— 同一个坑的旁证

来源：<https://www.curseforge.com/minecraft/mc-mods/create-idlx/files/7947091>、<https://modrinth.com/mod/create-idlx/versions>

- 版本 1.4 for MC 1.21.1 & Create 6.0.X，NeoForge，MIT
- 更新日志里明确写到它**修好了「Mysterious Cuckoo Clock 现在支持所有 Display Source」**——说明「布谷鸟时钟 + 显示链接器」这个组合确实是社区的关注点，我们选的方向是对的
- 它新增显示源的方式是**注册新的 DisplaySource**（如 Mechanical Piston Extension State），**不是 Mixin 注入**

**对我们的意义**：活跃的同类附属（MIT 协议、同样 1.21.1 + Create 6.0.X）用的是官方注册路线。我们走 Mixin 属于逆向而行。

### 1.4 jcodec 在 MC 模组领域的现状

搜索结论：**没有找到现代 MC 模组用 jcodec 做视频播放的案例**。

jcodec 官方文档自己写明：

> JCodec is a pure Java implementation. **The decoding will typically be an order of magnitude slower than the native implementations (such as FFmpeg).**

这解释了为什么 MC 模组领域要么用 FFmpeg/JNI（MediaPlayer），要么用原生平台解码器，**几乎没人用 jcodec**。

---

## 2. 自身代码现状：量化体检

### 2.1 规模构成

```
源码：82 文件 / 10542 行

按行数 Top 7（占 41%）：
   757  client/VideoPlayer.java              ← 上帝类
   732  client/MediaManager.java             ← 上帝类
   545  client/CuckooClockMediaScreen.java
   450  music/search/qqmusic/QQMusicApi.java
   399  music/qq/QqLoginService.java
   350  music/client/gui/ConfigScreen.java
   315  music/client/gui/MusicSearchScreen.java

按包分组：
   com.flapdisplayplus.music.*     ~50 个类（近 60% 代码）
   com.flapdisplayplus.net          9 个类
   com.flapdisplayplus.client       7 个类
   com.flapdisplayplus.mixin        4 个类
   com.flapdisplayplus.*（其他）    ~14 个类
```

### 2.2 构建产物构成（jar 4,258,507 字节）

| 内容 | 类数 | 体积 |
|---|---|---|
| **我们自己的类** | 130 | **479 KB（11%）** |
| jcodec | 989 | 3330 KB（78%） |
| zxing（二维码登录用） | 323 | 1076 KB（25%） |
| twelvemonkeys（WebP 解码） | 430 | 961 KB（23%） |
| 其他第三方 | 84 | 660 KB（15%） |

> 注：百分比合计>100%，因字节统计含未被引用但已平铺的类；实际 jar 经压缩后为 4.26 MB。
>
> **亮点**：jcodec 一个库就占 78% 的类数。而它只用来解 MP4/H.264。

### 2.3 耦合面实测（拆分可行性）

**`music/` 包反向依赖**——`music/` 里所有文件引用的非 music 类：

```
15 × com.flapdisplayplus.FlapDisplayPlus     ← 且全是 MODID / LOGGER 这类常量
```

**主流程引用 `music/`**：

```
FlapDisplayPlus.java:16   import ...music.MusicClientInit;
FlapDisplayPlus.java:17   import ...music.MusicNetIntegration;
client/CuckooClockMediaScreen.java:19  import ...music.MusicNetIntegration;
```

**结论**：`music/` 与主工程之间只有 **3 处引用**，且都是浅引用。**可以零成本拆出为独立 Gradle 子模块或独立 jar**。

**`MediaManager` / `VideoPlayer` 外部引用**：全部集中在 3 个文件（`ClientWorldEvents` 4 处、`CuckooClockMediaScreen` 8 处、`FlapDisplayRendererMixin` 2 处）+ 3 处 `isVideo/isImage` 判定。接口面很小。

### 2.4 线程与资源盘点（无全局调度器）

```
new Thread(...)            分 5 处各自裸起：VideoPlayer 视频/音频 ×2、StreamDownloader、YtDlpResolver ×2
CompletableFuture.runAsync()   8 处（用 ForkJoinPool.commonPool，不可控）
Executors.*                仅 2 处：NetMediaManager(fixed 2)、QqLoginService(single)
```

**问题**：
1. `CompletableFuture.runAsync` 默认走 `ForkJoinPool.commonPool`——**与 MC 主线程和其他模组共享同一个池**，媒体下载容易把公共池打满
2. 每个 `VideoPlayer` 起 2 个线程，多显示器场景线程数线性增长
3. `VideoPlayer` 有 `IDLE_STOP_MS = 8000` 的空闲回收，但没有全局上限

### 2.5 性能瓶颈：单帧 4 遍全帧 CPU 拷贝

`VideoPlayer.uploadFrame()`（341 行起）+ `writeTexture()`（376 行起）的路径：

```
jcodec Picture (YUV)
   ↓ ① scalePictureYuv()   逐平面盒式平均（CPU 双重循环）
   ↓ ② AWTUtil.toBufferedImage()   YUV→RGB 转换 + 新建大数组
   ↓ ③ bi.getRGB(...)      全帧 int[] 拷贝（4MB@1080p 分配）
   ↓ ④ setPixelRGBA() 逐像素   通道重排 ARGB↔ABGR（双重循环）
   ↓ ⑤ DynamicTexture.upload()
```

**可以省掉的**：
- ③ 的 `getRGB` 分配 → 可直接用 `bi.getRaster().getDataBuffer()` 复用缓冲
- ④ 的逐像素通道重排 → `NativeImage.setPixelRGBA` 是 GL 的 RGBA 顺序，AWT 是 ARGB；但可以用 `NativeImage` 的 `format` + 直接内存写，或在 ② 阶段就让 AWTUtil 输出 ABGR（若 jcodec 支持）
- `downscaleBox()`（472 行）已定义但**在 `uploadFrame` 路径里未被调用**（`scalePictureYuv` 走的是 `boxAvg`）——死代码

### 2.6 反射与 Mixin 分布

**反射点 13 处，集中在 5 个文件**：

| 文件 | 处数 | 用途 | 可去掉？ |
|---|---|---|---|
| `compat/DisplayLinkReflection.java` | 4 | 读写 `activeSource` | ✅ **字段是 public，整个类可删** |
| `compat/NetMusicDetector.java` | 2 | `Class.forName` 探测可选依赖 | ⚠️ 可改用 NeoForge `ModList` |
| `mixin/DisplayLinkBlockEntityMixin.java` | 1 | 调 `setActiveSource` | ✅ 随上一个一起去掉 |
| `music/compat/NetMusicCompat.java` | 4 | 跨模组网络包桥接 | ⚠️ 保留（不可避免） |
| `network/SetMediaPacket.java` | 2 | 设源 | ✅ 改直接赋值 |

**Mixin 9 个**：

```
mixin/                                  4 个
  CuckooClockBlockEntityMixin.java      NBT 持久化媒体配置（必要）
  DisplayLinkBlockEntityMixin.java      ← 可大幅简化
  DisplayLinkBlockMixin.java            GUI 替换（必要，除非用官方配置控件）
  FlapDisplayRendererMixin.java         渲染叠加（必要——Create 无渲染扩展点）

music/mixin/                            5 个
  BlockMusicPlayerMixin / CDBurnerMenuScreenMixin / NetMusicAudioStreamMixin
  NetMusicSoundMixin / TileEntityMusicPlayerMixin
```

**关键判断**：`FlapDisplayRendererMixin`（219 行）**无法去掉**——Create 没有提供「在翻牌上叠加自定义渲染」的官方扩展点，这是硬需求。但 `DisplayLinkBlockEntityMixin` 可以瘦身。

---

## 3. 重构方案

### 3.1 决策一：不替换 jcodec（明确不做）

**理由**：

| 方案 | 优势 | 代价 | 决策 |
|---|---|---|---|
| 保留 jcodec | 纯 Java、跨平台、零原生依赖、jar 已稳定 | 解码慢一个数量级、只支持 MP4/H.264 | ✅ **保留** |
| 换 MediaPlayer (FFmpeg/JNI) | 硬件加速、格式全 | LGPL-3.0 与 MIT 冲突、仅 Windows x64、项目已停更 | ❌ 否决 |
| 自研 JNI 绑定 FFmpeg | 性能最优 | 工作量 ≥ 3 个月、要维护 3 平台原生库、体积暴涨 | ❌ 否决 |
| 换 JavaCV / JavaCPP | 比 jcodec 快 | 原生库 100MB+、体积不可接受（当前 jar 才 4.26 MB） | ❌ 否决 |

**jcodec 的真实约束要写进文档而不是隐藏**：
- 只支持 **MP4 系容器**（mp4/m4v/mov）
- 只支持 **H.264 视频**（不支持 H.265/AV1）
- 不支持 AVI/MKV（jcodec 0.2.5 无 AVI demuxer）

**改进方向**：不换解码器，而是**优化解码后的像素处理路径**（§3.4）。

### 3.2 决策二：去掉 DisplayLink 反射层（P0 / 最高优先）

**证据**（Create 6.0.10 字节码）：

```
public com.simibubi.create.api.behaviour.display.DisplaySource activeSource;
```

字段是 `public`，`DisplayLinkReflection` 的整篇「为什么必须反射」注释建立在错误前提上。

**改动**：

1. 删除 `compat/DisplayLinkReflection.java`（80 行）
2. `mixin/DisplayLinkBlockEntityMixin.java` 改为直接字段访问：
   ```java
   if (self.activeSource != ModDisplaySources.FLAP_DISPLAY_MEDIA.get()) {
       self.activeSource = ModDisplaySources.FLAP_DISPLAY_MEDIA.get();
   }
   ```
3. `network/SetMediaPacket.java` 同步去掉 2 处反射调用
4. `source/FlapDisplayMediaSource.java` 顶部注释同步更正（现在写的是「必须用反射」）

**风险**：Create 若在未来版本把字段改回 private 会编译失败。缓解——编译期就能发现（不是运行期），且 NeoForge 官方映射下字段名稳定。

### 3.3 决策三：把 tick 注入换成官方被动刷新（P0）

**现状**：`DisplayLinkBlockEntityMixin` 在 `tick()` 里每 20 tick 手动调 `updateGatheredData()`，注释解释是为了「绕过 tickSource 的红石 POWERED 门槛」。

**但官方机制已经支持**：`shouldPassiveReset()==false` 时，Create 自己的 `tick()` 就会走 `tickSource()`。而 `tickSource()` 只在 `POWERED==true` 时提前 return。

**矛盾点**：我们**已经**覆盖了 `shouldPassiveReset()→false` 和 `getPassiveRefreshTicks()→20`。那为什么还要手动推？

**真实原因**（从代码看）：唯一无法用官方机制表达的是——**「翻牌显示器无转速时清除图片」**（`DisplayLinkBlockEntityMixin.java:56-79`）。

**建议方案**：把 Mixin 缩小到只保留这一件事：

```java
@Inject(method = "tick", at = @At("HEAD"))
private void flapdisplayplus$clearWhenUnpowered(CallbackInfo ci) {
    // 仅保留：翻牌无转速 → 发空包清除媒体
    // 设源 + 被动推送交给官方 shouldPassiveReset/getPassiveRefreshTicks 机制
}
```

**收益**：
- 删掉手动 `updateGatheredData()` 调用（不再与 Create 自己的刷新节奏打架）
- 删掉全局静态 `pushCounter`（现在被所有链接器共享，是个隐性耦合）
- 行为更可预测

**风险**：中。需要实测确认「指向布谷鸟时钟 → 无需红石即可自动显示」在去掉手动推送后仍然成立。**这一条必须实测验证后再合并。**

### 3.4 决策四：优化帧上传路径（P1）

**目标**：省掉 4 遍全帧拷贝中的 2 遍。

**改动点**：

1. **去掉 `bi.getRGB()` 的全帧分配**（`VideoPlayer.java:369`）
   ```java
   // 现在：每帧新建 4MB int[]
   int[] argb = bi.getRGB(0, 0, bw, bh, null, 0, bw);

   // 改为：复用单个字段缓冲
   private int[] pixelBuf;
   pixelBuf = bi.getRGB(0, 0, bw, bh, pixelBuf, 0, bw);
   ```
   注意：`bw` 变化时要重建（尺寸变了行距变了）。

2. **消除逐像素通道重排**（`VideoPlayer.java:397-406`）
   现在每个像素做 4 次移位 + 4 次掩码。两个可选方案：
   - **方案 A（低风险）**：让小尺寸纹理走 `NativeImage` 的 `setPixelRGBA` 批量；大尺寸用 `MemoryUtil.memByteBuffer` 直接写原生内存后 `NativeImage.upload()`——省掉 Java 层 setPixel 调用开销。
   - **方案 B（中风险）**：让 jcodec 直接输出 RGB 而非 YUV（`ColorSpace.RGB`），在 `AWTUtil.toBufferedImage` 之后用光栅 `DataBuffer` 直接搬运，跳过 `getRGB` + `setPixelRGBA` 两步。

   **建议先做 1（零风险），再评估 2**。盲目做 2 有重演历史 bug 的风险——代码注释里记录了「曾因通道错位导致橙显示成品红」（`MediaManager` 的 `argbToRgba`）。

3. **删除死代码**：`downscaleBox()`（472 行）未被调用。
4. **纹理上限与 fps 配置联动**：`MEDIA_VIDEO_MAX_DIM` 默认 1024、`MEDIA_VIDEO_FPS` 默认 24。建议在 GUI 里暴露，并在文档里给出「卡顿 ↔ 清晰度」的调参指引。

**收益预估**：按 1024×1024@24fps 计算，每帧省 1 次 4MB 分配 → 24 fps 下每秒省 96 MB 分配量，GC 压力显著下降。

### 3.5 决策五：拆分 `music/` 包（P1）

**可行性证据**：`music/` → 主工程仅 15 处引用，**全是 `FlapDisplayPlus.MODID` / `LOGGER` 这类常量**；主工程 → `music/` 仅 3 处 import。

**方案**：

```
方案 A：Gradle 多模块（推荐）
  :core          主工程（client / net / source / mixin / api / config）
  :music         音乐集成（可单独编译、单独禁用的子模块）

方案 B：只做包重命名归一化
  com.flapdisplayplus.music.* → com.flapdisplayplus.integration.netmusic.*
  并把已有可选的 ModDisplaySources 从 music 包挪回主工程
```

**为什么推荐方案 A**：
- 音乐功能是**可选集成**（依赖检测到 Net Music 才注册），逻辑上就该是独立模块
- 拆出后主工程从 82 文件/10542 行降到约 40 文件/4500 行，可读性大幅提升
- `music/config/Config.java` 与 `config/Config.java` 两个独立 `ModConfigSpec` 的问题可以顺带解决（各模块管自己的 SPEC）

**注意**：`ModDisplaySources.register()` 目前在 `music/` 包下（`music/ModDisplaySources.java`），**这是放错了**——显示源是主功能，不是音乐集成的一部分。拆包时要把这个类挪回主工程。

### 3.6 决策六：统一线程与网络资源管理（P1）

**问题**：
- 8 处 `CompletableFuture.runAsync` 走 `ForkJoinPool.commonPool`（与 MC + 其他模组共享）
- `StreamDownloader` 每个下载裸起线程，无上限
- 无全局下载并发上限

**方案**：引入一个 `MediaExecutors`：

```java
public final class MediaExecutors {
    // 下载池：固定 2-3 线程（网络 IO 密集，不宜过多）
    private static final ExecutorService DOWNLOAD = Executors.newFixedThreadPool(3, ...);
    // 解码/上传：单线程队列（避免多视频同时抢 GPU 上传）
    private static final ExecutorService UPLOAD  = Executors.newSingleThreadExecutor(...);
    // 元数据/探测：缓存池
    private static final ExecutorService META    = Executors.newCachedThreadPool(...);

    public static void shutdown();  // 客户端断开时调用
}
```

并加**全局下载并发上限**（当前 `NetMediaManager` 的 `POOL` 是 fixed 2，但 `StreamDownloader` 是旁路裸起线程，绕过这个限制）。

**收益**：资源可预测、世界卸载时能干净回收、不与其他模组抢公共池。

### 3.7 决策七：建立扩展 API（P2）

**现状**：`api/` 只有 `CuckooClockMedia.java`。第三方想在翻牌上显示自定义内容，只能自己写 Mixin。

**建议**：借鉴 Create 自己的 `DisplaySource` 设计，在 `api/` 下建立：

```java
package com.flapdisplayplus.api;

/** 自定义显示内容提供者：实现它即可往翻牌上叠加自定义渲染。 */
public interface IDisplayContentProvider {
    /** 唯一 ID（命名空间:路径） */
    ResourceLocation getId();

    /** 每帧被调用，返回要绘制的纹理（null = 本帧不绘制） */
    ResourceLocation getFrame(DisplayContentContext ctx);

    /** 内容原始尺寸（用于 FIT/COVER 比例计算） */
    default int getContentWidth()  { return 0; }
    default int getContentHeight() { return 0; }

    /** 优先级（大者覆盖小者） */
    default int getPriority() { return 0; }
}

/** 注册入口 */
public final class FlapDisplayPlusApi {
    public static void registerContentProvider(IDisplayContentProvider provider);
    public static void registerRenderLayer(IDisplayRenderLayer layer);
}
```

并把 `FlapDisplayRendererMixin` 从「直接读 `MediaRenderRegistry`」改为「遍历已注册的 provider」，让媒体渲染成为**第一个内置 provider**。

**收益**：第三方模组（比如想显示 B 站弹幕、显示某个机器的状态图）无需 Mixin，直接实现接口。

**风险**：低（新增而非修改）。但需要冻结接口签名——一旦发布就不能随便改。

### 3.8 关于「配置割裂」（P2）

现状：两个独立 `ModConfigSpec`：

```
config/Config.java         →  media.imagePath / media.fit / media.videoSound /
                               media.videoMaxDim / media.videoFps / media.imageMaxDim /
                               media.ytdlpPath / media.resolverTimeoutSec /
                               media.netCacheDir / media.netCacheMaxMb
music/config/Config.java   →  show_lyric_when_paused / show_pause_time /
                               pause_symbol_enabled / redstone_mode / pause_resume /
                               cookie / audio_quality / search_list_mode / search_page_size
```

**评估**：这其实**不算严重问题**——两个 SPEC 服务于两个独立功能域，NeoForge 支持多个 SPEC。真正的问题只是**命名前缀不统一**（一个用 `media.xxx` 点号分层，一个用 `xxx` 扁平）。

**建议**：低优先级。做拆分（§3.5）时顺带统一为 `media.*` / `netmusic.*` 两族前缀即可。

### 3.9 决策九：配置界面与 UI 层重构（P1，本次新增）

> 本节是对原方案的**范围扩展**。原方案 §3.7/§3.8 只把 GUI 当作「配置项的名字前缀」问题，
> 没有把界面本身当作重构对象。这次按用户要求「从底层到配置界面再到 UI」补齐。

#### 3.9.1 现状盘点（11 个文件 / 约 2372 行）

| 文件 | 行数 | 基类 | 控件来源 |
|---|---|---|---|
| `client/CuckooClockMediaScreen` | 545 | Create `AbstractSimiScreen` | **FdpButton（自绘）** |
| `music/client/gui/ConfigScreen` | 350 | 原版 `Screen` | 原版 `Button` |
| `music/client/gui/MusicSearchScreen` | 315 | 原版 `Screen` | 原版 `Button` |
| `music/client/gui/LoginScreen` | 295 | 原版 `Screen` | 原版 `Button` |
| `mixin/FlapDisplayRendererMixin` | 219 | — | — |
| `mixin/CDBurnerMenuScreenMixin` | 192 | 原版 `AbstractContainerScreen` | 原版 `Button` |
| `music/client/gui/QqLoginScreen` | 184 | 原版 `Screen` | 原版 `Button` |
| `music/client/CDCoverRenderer` | 88 | — | — |
| `music/client/gui/QrCodeRenderer` | 82 | — | — |
| `client/FdpButton` | 69 | 原版 `Button` | 自绘 |
| `music/client/CustomRendererBakedModel` | 23 | — | — |

#### 3.9.2 问题一：两套 GUI 体系并存，风格割裂（**确凿**）

- `FdpButton` 的类注释写着「与布谷鸟时钟媒体界面 **/ 配置界面** 风格统一」
- **但事实是**：`grep FdpButton` 只命中 `CuckooClockMediaScreen`（1 个文件，13 处调用）
- `music/client/gui/` 下 4 个 Screen **全部使用原版 `Button`**，共约 25 处 `Button.builder(...)`
- 结果：从布谷鸟时钟界面点进音乐配置，**按钮从「暗木底+描金边框」突变成原版灰色按钮**

**方案**：把 `FdpButton` 升级为控件库 `FdpWidgets`：

```java
package com.flapdisplayplus.client;

public final class FdpWidgets {
    // 统一的木质配色常量（单一数据源，杜绝各文件硬编码 0xFF2F2216）
    public static final int WOOD_DARK = 0xFF2A1D13;
    public static final int WOOD_BG   = 0xFF3D2B1F;
    public static final int WOOD_CELL = 0xFF2F2216;
    public static final int BORDER    = 0xFF5C4430;
    public static final int BORDER_HL = 0xFFC9A86A;
    public static final int TEXT      = 0xFFE8D5AB;
    public static final int TEXT_DIM  = 0xFFB09A72;

    /** 木质面板：外框 + 底色 + 四边描线，替代各处手写的 4 行 fill/hLine/vLine */
    public static void panel(GuiGraphics g, int x, int y, int w, int h);

    /** 开关按钮（自动回写配置 + 落盘 + 更新文案），替代 ConfigScreen.addBoolRow */
    public static FdpButton toggle(int x, int y, ModConfigSpec.BooleanValue val, Runnable afterChange);

    /** 枚举循环按钮（点击切下一个值 + 中文名），替代 ConfigScreen.addEnumRow */
    public static <T extends Enum<T>> FdpButton cycle(int x, int y, int w,
            ModConfigSpec.EnumValue<T> val, Function<T, String> labelOf);
}
```

**收益**：4 个界面的约 25 处按钮调用改为 1 行；`CuckooClockMediaScreen` 里手写的 4 行面板描边压成 1 行；配色常量从散落各处收敛到 1 处。

#### 3.9.3 问题二：`CuckooClockMediaScreen` 网格命中判定与绘制**错位 20px**（**真 bug**）

```java
// renderWindow（第 440 行）—— 绘制起点
int gridTop = top + 56;

// mouseClicked（第 523 行）—— 判定起点
int top = this.guiTop + 76;   // ← 56 vs 76，差 20px
```

后果：**点击格子时，实际命中位置比看到的画面低 20px**——点第一行格子会命中第二行，点第二行会命中第三行（或落到空白）。同时 `cellW=92 / cellH=70 / cols=3` 在**两个方法里各自硬编码了一份**，这正是偏移能悄悄产生的原因。

**方案**：抽出唯一的布局来源：

```java
/** 网格几何：绘制与命中判定共用，杜绝两处硬编码漂移 */
private record Grid(int left, int top, int cellW, int cellH, int cols, int gapX, int gapY) {
    int x(int i) { return left + (i % cols) * (cellW + gapX); }
    int y(int i) { return top + (i / cols) * (cellH + gapY); }
    boolean hit(int i, double mx, double my) {
        return mx >= x(i) && mx <= x(i) + cellW && my >= y(i) && my <= y(i) + cellH;
    }
}

private Grid grid() { return new Grid(guiLeft + 10, guiTop + 56, 92, 70, 3, 6, 8); }
```

绘制和 `mouseClicked` 都调 `grid()`，**结构上不可能再错位**。

#### 3.9.4 问题三：`CuckooClockMediaScreen` 单类 545 行、5 项职责

一个类同时负责：① 本地文件扫描 ② 网络链接管理 ③ 分页状态 ④ 网格渲染 + 命中 ⑤ 内容类型选项卡 + 状态提示文案。

**方案**：按职责拆为 4 个协作类（同包，不改变外部调用方）：

```
client/
  CuckooClockMediaScreen.java   主界面：窗口/控件装配/事件分发（目标 ~250 行）
  MediaGrid.java                纯布局计算 + 命中判定（Grid record 升格为类）
  MediaEntry.java               条目模型（原内部类 Entry + 本地扫描/网络合并）
  MediaScreenText.java          全部中文文案（sourceTypeHint / modeLabel / labels）
  FdpWidgets.java               控件库（§3.9.2）
```

**注意**：`Entry` 目前是 `private static final class`，升级为顶层类时要保留 `isNet()/key()/shortName()/name()` 语义不变。

#### 3.9.5 问题四：配置界面与功能界面**字段重复**

同一个设置项散落在多处、各自更新：

| 设置 | 出现位置 |
|---|---|
| `SEARCH_LIST_MODE` | `ConfigScreen.buildSearch` 改；`MusicSearchScreen.rebuildItems/render/mouseScrolled` 读 4 处 |
| `SEARCH_PAGE_SIZE` | `ConfigScreen.buildSearch` 改；`MusicSearchScreen` 读 4 处 |
| `videoSound` | `CuckooClockMediaScreen` 行2 按钮改；`MediaManager` 读 |

`ConfigScreen.changePageSize()` 的做法是**改完直接 `setScreen(new ConfigScreen(...))` 整页重建**——因为设置项与界面控件之间没有绑定机制，只能靠重建刷新。

**方案**：把「配置项 ↔ 控件」的联动交给 `FdpWidgets.toggle/cycle`（回调里统一 `val.set` + `SPEC.save` + 刷新文案），并给 `CuckooClockMediaScreen` 的声音开关改为复用 `MediaManager` 的公共 setter（已有 `setVideoSound`），不再就地拼字符串。

#### 3.9.6 问题五：媒体参数**未暴露到界面**

`Config.MEDIA_VIDEO_MAX_DIM`(1024) / `MEDIA_VIDEO_FPS`(24) / `MEDIA_IMAGE_MAX_DIM`(2048) 已存在，但**没有任何界面能改**——用户遇到卡顿只能手改配置文件。

**方案**：在 `ConfigScreen` 新增 `Page.MEDIA`「媒体性能」子页：清晰度档位（640/1024/1440）、帧率（12/24/30/60）、图片上限（1024/2048/4096），全部用 `FdpWidgets.cycle`，并在页面底部给出「卡顿 ↔ 清晰度」的中文调参指引。

#### 3.9.7 问题六：`ConfigScreen` 用 `List<InfoLine>` 手工排布文字

所有说明文字靠 `info("...", cx - 150, y, 0xC8C8C8); y += 16;` **手工累加 y 坐标**（`buildNetease` 里 14 行、`buildSearch` 里 9 行）。加一行要重算后面所有坐标。

**方案**：改为累加器：

```java
private final TextFlow flow = new TextFlow(0xE0E0E0, 16);
flow.h1("【制作网易云唱片】", 0xFFD060);
flow.p("在网易云 App/网页复制歌曲分享链接，形如：");
flow.mono("music.163.com/song?id=数字&uct2=...", 0x9CDCFE);
```

`TextFlow` 内部维护 `x/y` 与缩进，`render` 时统一遍历绘制。

---

### 3.10 决策十：QQ 音乐支持打通（2026-08-28 全接口实测）

#### 3.10.1 调研对象

用户要求「先在 GitHub 以及 MC 百科上找找有没有成熟的项目，再进行」。本次共调研到以下成熟项目：

| 项目 | 类型 | 许可证 | 借鉴价值 |
|---|---|---|---|
| **Yincmewy/NetMusicCanNeedQQ**（网络音乐机:看你的QQ） | Forge 模组，0.2.0-beta，最后更新 2026-05-06 | **BSD-3-Clause** | ★★★★★ 搜索/URL解析/刷新策略全部已跑通 |
| jsososo/QQMusicApi | Node.js 服务 | MIT | 接口清单佐证 |
| 1015770492/yumbo-music-utils | Java 库（网易云+QQ） | MIT | 轻量可参考，但不含 mod 集成 |
| 网络音乐机:登登你的（LoginNeed） | 附属模组 | — | 仅手动粘 Cookie，**无自动登录** |
| 网络音乐机:更好的登录（Better Login） | 附属模组 | — | 登录改进 |
| 网络音乐机:高级唱片机 / 播放列表 | 附属模组 | — | 与本需求无关 |

**关键判断**：`NetMusicCanNeedQQ` 是唯一成熟的同类实现，且我们此前已借鉴过它。本次拉取其**最新 main 分支**源码（2026-05-06），发现它已经**完全弃用旧搜索接口**，改用 `musicu.fcg` 协议族。我们现有代码停留在它上一代的方案上。

#### 3.10.2 全接口实测结果（本次亲自验证，非推测）

下列结论全部由 `curl` 实测原始响应得出，未经任何推测：

| 接口 | 方法/模块 | 实测结果 |
|---|---|---|
| **搜索** | `DoSearchForQQMusicDesktop` @ `music.search.SearchCgiService` | ✅ **`code:0`，返回完整列表**（含 `mid`/`name`/`file.media_mid`/`interval`/`singer`/`pay.pay_play`/`size_flac`/`size_320mp3`） |
| **`req.code=2001` 的真实含义** | — | ⚠️ **本次返回空列表，成因有二：① 连续快速请求触发限流 ② 个别关键词被曲库过滤。两者都是间歇性的**。实测同一关键词「周杰伦」先返回 2001、几分钟后重跑返回 30 条完整数据；「周杰伦/晴天/Vicetone/Nevada/钢琴/古筝」批量重跑全部正常。**绝不可当成接口故障**——上层只提示「无结果」，用户再点一次通常就好 |
| ~~搜索（老）~~ | ~~`c.y.qq.com/soso/fcgi-bin/client_search_cp`~~ | ❌ **HTTP 500，已死**（现有代码正靠它） |
| ~~搜索（错名）~~ | ~~`DoSearchForMusicDesktop`~~ | ❌ 空列表（**正确方法名是 `DoSearchForQQMusicDesktop`**，我们此前试错了名字） |
| **vkey 换地址** | `vkey.GetVkeyServer.CgiGetVkey` | ✅ **未登录即可返回真实 vkey**（见下方分档结果） |
| **歌词** | `music.musichallSong.PlayLyricInfo.GetPlayLyricInfo` | ✅ **`code:0`，返回明文 LRC**（如《晴天》68 行，`[ti:]/[ar:]` 齐全） |
| ~~歌词（老）~~ | `fcg_query_lyric_new.fcg` | ⚠️ 仍可用，但官方已迁移，统一走 `PlayLyricInfo` 更稳 |
| 歌曲详情 | `music.pf_song_detail_svr.get_song_detail` | ✅ 可用（但**搜索已直接返回 `media_mid`，无需此调用**） |

**4 个必须精确复刻的协议细节**（错一个就返回空）：

1. 搜索走 `https://u.y.qq.com/cgi-bin/musicu.fcg`（**`u.y`**，不是 `u6.y`）
2. 搜索 `comm` 固定 `ct=19`、`cv=1859`、`uin=0`；请求体**单层** `{"comm":{...},"req":{...}}`，结果读 `req.data.body.song.list`
3. vkey 请求 `comm` 是 `ct=24`、`cv=0`；请求体**外层带 `req_1`**，结果读 `req_1.data.midurlinfo`
4. 必须带 `Referer: https://y.qq.com/`

#### 3.10.3 播放地址分档实测（未登录状态）

以免费歌曲《Merry Christmas Mr. Lawrence》为例，一次性请求 6 档，结果：

| 档位 | 含义 | 未登录结果 | 可播 |
|---|---|---|---|
| `F000` | FLAC 无损 | `result: 104003` | ❌ 需 VIP |
| `M800` | 320k 以上 | `result: 104003` | ❌ 需 VIP |
| **`M500`** | **320kbps mp3** | **`result: 0` + 真实 purl** | ✅ **可播** |
| `RS02` | 试听档 | `result: 0` + 真实 purl | ✅ 可播 |
| `C600` | m4a 高码 | `result: 104003` | ❌ 需 VIP |
| `C400` | m4a 128k | `result: 0` + 真实 purl | ✅ 可播 |

VIP 歌曲（`pay_play=1`）未登录**只能拿 `RS02` 试听档**（实测《枫》等，其余档全 `104003`）。

**实测下载验证**（这是"真能播"的证据）：对拿到的地址发 Range 请求 →
```
HTTP/1.1 206 Partial Content
Content-Type: audio/mpeg
Content-Range: bytes 0-2047/960887
```
`960887` 字节与搜索结果 `file.size_try` 完全一致，**且支持 Range 请求**（对 §3.4 的边下边播管线友好）。

#### 3.10.4 由此确定的三条结论

**结论 1：QQ 音乐不需要登录就能用，体验达"搜索 + 320kbps 播放"。
这是本次最重要的发现**，它直接推翻了代码注释里「vkey 在未登录时对所有歌曲返回空 purl」的旧结论——那是**试错方法名导致搜索全空 + 只试了 VIP 歌曲**两个原因叠加造成的误判。

**结论 2：登录的价值仅剩「FLAC 无损 / VIP 歌曲全曲」**。
因此登录应定位为**可选增强**，绝不作为使用前置。UI 上「未登录」不应该是错误态。

**结论 3：CD 里必须存 `media_mid` 与 `songmid` 两个稳定标识，不能只存一个。**
vkey 请求的 `filename` 要 `前缀 + media_mid + 后缀`，而 `songmid` 用于歌词与详情。二者不同（实测《枫》：`songmid=003KtYhg4frNXC` 但 `media_mid=0044M6Un0RXph2`）。**用过期的 vkey URL 存进 CD 会导致唱片随时间失效**——正确做法是参考 `QqMusicUpdater` 的「存稳定标识 + 运行时按 30 分钟冷却刷新」。

#### 3.10.5 实施方案（QQ-0 ~ QQ-5）

| 阶段 | 内容 | 风险 |
|---|---|---|
| **QQ-0** | `QQMusicApi.search()` 迁移到 `DoSearchForQQMusicDesktop`，解析 `req.data.body.song.list`，直接取 `media_mid` | 低（已实测通过） |
| **QQ-1** | 拆解 `QUALITY_CANDIDATES` 为「按未登录/已登录分档」：未登录优先 `M500`→`C400`→`RS02`；已登录前置 `F000`/`M800` | 低 |
| **QQ-2** | `QqSearchCache` 增加 `mediaMid` 字段回填，`resolvePlayUrl` 优先用缓存，**去掉 fallback 详情接口调用**（省一次网络往返） | 低 |
| **QQ-3** | 歌词改走 `music.musichallSong.PlayLyricInfo`（明文 LRC，官方现役接口） | 低 |
| **QQ-4** | **解除两个硬编码关闭**：`MusicSearchScreen:113` 的「QQ音乐搜索功能开发中」、`CDBurnerMenuScreenMixin:120` 的「QQ音乐刻录功能开发中，请使用网易云音乐」 | 低 |
| **QQ-5** | `getPlayUrlSync` 从 Render 线程剥离 → 刻录时走后台预取 + 缓存命中（**禁止渲染线程阻塞网络请求**） | 中 |

**不做的事**（明确边界）：不实现任何绕过 VIP 鉴权的逻辑。`104003` 就是版权方的正常拒绝，我们只按官方允许的档位降级（VIP 歌 → 试听档）。这与 §3.10.3 的档位表严格一致。

#### 3.10.6 与「歌词获取 / 歌曲搜索 / 网络音乐机拓展」的关系

用户本次要求的三件事，经调研后判定为**同一件事的三个面**：

- **歌曲搜索**：网易云侧已通；QQ 侧是 QQ-0 一个方法名的事 → 修完全线打通
- **歌词获取**：网易云侧已有 `LyricCache`；QQ 侧缺官方现役接口 → QQ-3 补齐。**注意 §「LyricCache 失败结果绝不能当成功值永久缓存」的历史教训在此同样适用**
- **网络音乐机拓展**：`MusicNetIntegration` 的 6 个显示源 + `MusicPlayResolverManager` 解析器注册架构**已经是对的**，不需要重构。真正缺的是 QQ 侧的解析器能真正拿到地址 → QQ-0~3 完成后自动生效

---

## 4. 执行计划

| 阶段 | 内容 | 风险 | 可独立发布 |
|---|---|---|---|
| **P0-a** | 去掉 `DisplayLinkReflection`，改直接字段访问（§3.2） | 低（编译期可验证） | ✅ |
| **P0-b** | 瘦身 `DisplayLinkBlockEntityMixin`，改用官方被动刷新（§3.3） | **中（需实测）** | ✅ |
| **P1-a** | 帧上传路径优化：复用缓冲 + 删死代码（§3.4.1、§3.4.3） | 低 | ✅ |
| **P1-b** | 拆分 `music/` 为 Gradle 子模块 + 挪回 `ModDisplaySources`（§3.5） | 中（构建配置改动） | ✅ |
| **P1-c** | 统一线程池 `MediaExecutors`（§3.6） | 中（并发行为变化） | ✅ |
| **P2-a** | 建立 `api/` 扩展点，媒体渲染改走 provider（§3.7） | 低 | ✅ |
| **P2-b** | 配置前缀统一（§3.8） | 低 | ✅ |
| **UI-0** | **修网格点击热区偏移 20px + 抽出 `Grid` 单一布局来源（§3.9.3）** | **极低（纯 bug 修复）** | ✅ |
| **UI-1** | 抽 `FdpWidgets` 控件库 + 配色常量收敛（§3.9.2） | 低 | ✅ |
| **UI-2** | 拆 `CuckooClockMediaScreen` 为 4 类（§3.9.4） | 低 | ✅ |
| **UI-3** | 4 个配置/登录界面换用 `FdpWidgets`，消除风格割裂（§3.9.2） | 低 | ✅ |
| **UI-4** | `ConfigScreen` 改 `TextFlow` 累加排版（§3.9.7） | 低 | ✅ |
| **UI-5** | 新增「媒体性能」子页，暴露清晰度/帧率/图片上限（§3.9.6） | 低 | ✅ |
| **QQ-0** | **搜索迁到 `DoSearchForQQMusicDesktop`（§3.10.2）** | **低（已实测）** | ✅ |
| **QQ-1** | 音质分档按登录态区分（§3.10.3） | 低 | ✅ |
| **QQ-2** | `media_mid` 缓存回填，去掉详情接口兜底调用（§3.10.5） | 低 | ✅ |
| **QQ-3** | 歌词迁到 `PlayLyricInfo` 明文 LRC（§3.10.2） | 低 | ✅ |
| **QQ-4** | **解除 QQ 搜索/刻录两处硬编码「开发中」（§3.10.5）** | 低 | ✅ |
| **QQ-5** | `getPlayUrlSync` 移出 Render 线程（§3.10.5） | 中 | ✅ |

**建议执行顺序**：`UI-0`（先修 bug，纯赚）→ `P0-a`（去反射）→ `UI-1` → `UI-2` → `UI-3` → `UI-4` → `UI-5` → `QQ-0`→`QQ-4`（打通 QQ，已实测接口可用）→ 再评估 `P0-b`/`P1-*`/`QQ-5`。
把零风险的放前面，每一批都能独立构建 + 独立部署验证。

**已完成批次**：第一批（UI-0/UI-1/UI-4/UI-5 部分、P0-a）已构建部署（jar 4,269,287 字节）。

**每阶段的验证要求**：
1. `./gradlew build -x test` 通过
2. 启动游戏，实测：本地图片 / 本地 GIF / 本地 MP4（有声、无声）
3. 实测：直链图片 / 直链 MP4
4. 实测：翻牌无转速时媒体消失、有转速时恢复
5. 实测：多显示器同时播放不崩
6. 世界退出后检查无残留线程

---

## 5. 待确认问题（需用户决策）

1. **§3.3 是否接受行为变化？**
   去掉手动 `updateGatheredData()` 后，「指向布谷鸟时钟 → 无需红石自动显示」依赖 Create 自己的被动刷新。需要先做一次隔离测试确认。若不稳定，退回保留手动推送但去掉全局 `pushCounter`。

2. **§3.5 拆包方案选 A（Gradle 多模块）还是 B（仅包重命名）？**
   方案 A 更彻底但有构建配置风险；方案 B 零风险但解决不了「主工程臃肿」的观感。

3. **§3.4 「消除逐像素通道重排」是否要做？**
   收益明显但有重演配色 bug 的风险（历史上曾出现橙→品红）。建议先做零风险项，这一条单独评估。

4. **§3.7 扩展 API 是否现在就冻结设计？**
   一旦发布就要承担兼容责任。若暂无第三方接入需求，可推迟到 P2 之后。

5. **是否有多平台（1.20.1 Forge）同步重构的需求？**
   本方案只覆盖 1.21.1 NeoForge。若两个分支都要改，需要额外评估工作量。

6. **【UI 范围】界面重构要做到哪一层？**
   - **选项 A（保守）**：只修 bug（§3.9.3 网格错位）+ 抽 `FdpWidgets` 换掉原版按钮，**布局与交互不变**，纯视觉统一。风险最低。
   - **选项 B（推荐）**：A + 拆 `CuckooClockMediaScreen` 多职责（§3.9.4）+ `TextFlow` 排版（§3.9.7）+ 新增「媒体性能」子页（§3.9.6）。
   - **选项 C（激进）**：B + 重做主界面布局（当前 3 列×2 行网格 + 三行按钮，信息密度低、翻页效率差），改成左侧目录树 + 右侧大预览。
   选 C 需要先定交互稿，工作量最大但观感提升最明显。

7. **【UI 范围】是否需要统一的「翻牌万象」设置入口？**
   目前 `FlapDisplayPlus` 自己的配置（媒体参数）与音乐配置是**两个独立的配置界面**（两个 `ModConfigSpec`）。
   是否要合并成一个「设置」界面，左侧标签页切换「通用 / 媒体 / 音乐」？
   合并观感更好，但要处理 `ModContainer` 注册的 config factory（`IConfigScreenFactory`）指向哪个 Screen。

---

## 附录 A：Create API 借鉴要点速查

```java
// 1. 注册显示源（三步，无需 Mixin）
public static final DeferredHolder<DisplaySource, FlapDisplayMediaSource> FLAP_DISPLAY_MEDIA =
    SOURCES.register("flap_display_media", FlapDisplayMediaSource::new);

// BY_BLOCK_ENTITY.add 必须用【同一个实例】
DisplaySource.BY_BLOCK_ENTITY.add(TYPE, FLAP_DISPLAY_MEDIA.get());  // ← .get() 同一引用

// 2. 源的关键覆盖
@Override public boolean shouldPassiveReset() { return false; }  // 防被清空
@Override public int  getPassiveRefreshTicks() { return 20; }   // 被动刷新间隔
@Override protected MutableComponent provideLine(DisplayLinkContext ctx, DisplayTargetStats st);

// 3. activeSource 是 public，直接访问
link.activeSource = FLAP_DISPLAY_MEDIA.get();   // 不需要反射

// 4. 主动推送（绕过红石）
link.updateGatheredData();   // public
```

## 附录 B：参考项目索引

| 项目 | 地址 | 许可 | 借鉴点 | 能否抄代码 |
|---|---|---|---|---|
| MediaPlayer | github.com/hackermdch/MediaPlayer | LGPL-3.0 | JNI 直写 GL 纹理、自定义 RenderType | ❌ 许可冲突 |
| Create: IDLX | modrinth.com/mod/create-idlx | MIT | 官方注册路线、布谷鸟时钟支持 | ⚠️ 需保留署名 |
| Create 本体 | github.com/Creators-of-Create/Create | MIT | DisplaySource/DisplayTarget 扩展模型 | ✅ |
| jcodec | github.com/jcodec/jcodec | FreeBSD | 当前解码后端 | ✅ |

## 附录 C：本次调研的验证方法

- **Create API**：从本机 `~/.gradle/caches/modules-2/files-2.1/com.simibubi.create/create-1.21.1/6.0.10-280/.../create-1.21.1-6.0.10-280-slim.jar` 解压 class 后 `javap -p` / `javap -c` 读签名与字节码
- **自身代码**：`grep` 统计耦合面、`unzip -l` 统计 jar 构成
- **外部项目**：WebFetch + WebSearch（MC百科、GitHub、Modrinth、CurseForge）

> 方法论提醒：**任何关于第三方 API 可见性的断言，都应该用 `javap` 在真实 jar 上验证，而不是靠注释或记忆。** 本次「反射是必需的」这个错误假设就是靠字节码推翻的。
