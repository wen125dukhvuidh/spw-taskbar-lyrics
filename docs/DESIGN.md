# 设计方案：插件内自绘任务栏歌词（不依赖任何外部程序）

> ## ⚠️ 架构已变更（2026-10-12）
>
> 曾经的「改造 ANYNC/TaskbarLyrics、保留其 UI」方案**已废弃**。
> 用户的要求是把任务栏歌词**做进插件本身**，之后可以**卸载 TaskbarLyrics**。
>
> - 不再依赖 ANYNC、不再维护 C# 分支、不需要 .NET、不起任何外部进程
> - 歌词来源不变（插件自己解析内嵌歌词，已实现）
> - 渲染改为**插件内用 JNA 调 Win32 自绘**，可行性已实测通过
>
> 本文中凡是提到 ANYNC 改动清单的章节（第 2 节的 `SpwLyricProvider`、
> 第 6 节的 C# 改动点等）**仅作历史记录保留，不再执行**。
> 新的实现见下方「0.A 插件内自绘架构」。

---

## 0.A 插件内自绘架构（当前方案）

### 数据流

```
Salt Player for Windows 进程（Java 25，jlink 裁剪运行时）
  │
  ├─ PlaybackExtensionPoint  取当前歌词行 + 播放位置
  │     onLyricLineUpdated / onPositionUpdated / onBeforeLoadLyrics
  │
  ├─ LyricsStore             内嵌歌词解析为主路径（FLAC/ID3/MP4 + 同目录 lrc）
  │     100% 覆盖率（已实测本机 298/298）
  │
  └─ TaskbarLyricsWindow     JNA → Win32
        ├─ 找 Shell_TrayWnd，枚举子窗口求空闲横条
        ├─ CreateWindowExW 建 WS_CHILD 子窗口挂进任务栏
        │    WS_EX_LAYERED | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE | WS_EX_TRANSPARENT
        ├─ Java2D 渲染到 TYPE_INT_ARGB_PRE 位图
        └─ UpdateLayeredWindow 推送（逐像素 alpha）
```

### 为什么这条路可行（实测依据）

| 依据 | 来源 |
| --- | --- |
| 宿主**主动开放原生调用** | `app/Salt Player for Windows.cfg` 里 `--enable-native-access=ALL-UNNAMED` |
| 宿主**允许 AWT 窗口** | 同一 cfg 里没有 `-Djava.awt.headless=true` |
| 宿主**开放 AWT 内部包** | `--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED` |
| JVM 内可调 Win32 | `tools/probe/TbDemo.java` 实测成功 |
| 分层窗口可嵌进任务栏 | 实测父窗口 = `Shell_TrayWnd`，z 序第 0 位 |
| 逐像素透明可渲染中文 | `PrintWindow` 回读检出 385 个亮文字像素 |

### 任务栏几何（本机实测，Windows 11 22H2 / 2560×1440 / 缩放 150%）

`Shell_TrayWnd` 是**顶层**窗口，rect (0,1368)-(2560,1440)，物理高 72px（= 48 逻辑px）。

它的**直接子窗口**决定了哪里是空闲的：

| 子窗口 | 类名 | rect | 是否算占用 |
| --- | --- | --- | --- |
| TrafficMonitor | `#32770` | (2,1380)-(558,1428) | ✅ 占用 |
| **居中图标区** | `ReBarWindow32` | (1116,1368)-(1578,1440) | ✅ 占用 |
| 右侧托盘 | `TrayNotifyWnd` | (2017,1368)-(2560,1440) | ✅ 占用 |
| XAML 合成层 | `Windows.UI.Composition.DesktopWindowContentBridge` | 全宽 2560x72 | ❌ **必须排除** |

> 排除最后一项是关键：它覆盖整条任务栏，若算作占用就永远找不到空隙。

求空隙的算法：把「可见、宽度 > 1、不是合成层」的子窗口 rect 按左边界排序，
在任务栏范围内扫一遍求最大空隙。本机结果：`[558→1116]=558px`、`[1578→2017]=439px`。

### 分层窗口的三个技术要点

1. **DIB 是自下而上、BGRA 顺序**。`CreateDIBSection` 用正数高度得到 bottom-up，
   写像素时按行倒序；`BufferedImage.TYPE_INT_ARGB_PRE` 的 int 是 `0xAARRGGBB`，
   拆成字节正好是 B/G/R/A。
2. **`UpdateLayeredWindow` 的 `hdcDst` / `pptDst` 传 `null`**，
   位置和尺寸交给 `SetWindowPos` 管，避免「子窗口坐标是相对父窗口还是屏幕」的歧义。
3. **透明底上必须用灰度抗锯齿**。`VALUE_TEXT_ANTIALIAS_ON`；
   若用 LCD 子像素抗锯齿（`VALUE_TEXT_ANTIALIAS_LCD_*`），
   半透明像素的边缘会出现彩色描边。

### 0.A.1 端到端验证结果（已通过）

把模块裁成与宿主**完全一致的 18 个**（`--limit-modules`），跑**插件真正编译出来的类**：

```
kotlin-stdlib: 可加载
Win32.isAvailable = true
主任务栏 (0,1368)-(2560,1440) 2560x72 dpi=144
空闲段: x=558 宽=558 / x=1578 宽=439
窗口 x=567 y=1368 w=540 h=72 scale=1.50
封面=51x51（读自 D:\Music\01. 六月飞霜.flac 的内嵌 PICTURE 块）
PrintWindow=true   回读亮文字像素=1916
共渲染 333 帧，全程窗口存活=true
```

自检工具 `tools/probe/RenderProbe.java` 用的是**插件真实类**
（Win32 / TaskbarLayerWindow / RenderSettings / LyricsPainter / CoverArt），不是探针代码。
产出 `plugin-render.png` / `plugin-readback.png`。

### 0.A.2 文字渲染规范（逐项抄自原版源码，不要凭观感调）

**权威来源**（按优先级）：

| 文件 | 给出什么 |
| --- | --- |
| `TaskbarLyrics.App/ForegroundColorPolicy.cs` | 透明度体系、扫描叠加补偿公式 |
| `TaskbarLyrics.App/LyricsStyleScriptFactory.cs` | 注入给前端的实际样式值（含 `FontWeight`、`ShowTextShadow`） |
| `TaskbarLyrics.App/LyricsLayoutMetrics.cs` | 全部版式常量 |
| `Web/Lyrics/style.css` + `app.js` | 两层结构、羽化宽度、字号算法 |

**原版没有任何字体描边**——全 CSS 无 `-webkit-text-stroke`、无 `paint-order`。
`.line` 上只有一句 `text-shadow: var(--text-shadow)`。

#### 透明度：连乘，不是单一常量

```
primary                 = 0.90 × `.current-line` opacity 0.98               → 0.882  当前行「已唱」
secondary（当前行未唱）  = 0.60 × `.current-line` opacity 0.98               → 0.588  当前行「未唱」
translation             = 0.70 × `.translation-line` opacity 1              → 0.700  翻译行
next-line               = 0.60 × `.next-line` opacity 0.72                  → 0.432  下一句
translation-placeholder = 0.70 × `.translation-placeholder` opacity 0.55    → 0.385  译文缺失时的 `…`
```

#### 第二行内容：原版按「整首有没有翻译」切换布局

这一段是**主行/第二行错位的根源**，逐条对照原版源码：

```csharp
// MainWindow.xaml.cs:1282
translationMode = _currentSettings.ShowLyricTranslation && _hasTrackTranslation,
```

```js
// app.js:1402  翻译模式只取本句翻译，**不回落成下一句**
const visibleSecondary = useTranslationPair
    ? toDisplayLine(normalized.currentTranslation, " ")
    : toDisplayLine(normalized.next, " ");
```

```js
// app.js:341  译文为空时渲染成「…」，配 .translation-placeholder 的 opacity 0.55
function resolveTranslationDisplay(line) {
  const text = (line ?? "").toString().trim();
  return { text: text.length > 0 ? text : "…", isPlaceholder: text.length === 0 };
}
```

于是 `AUTO`（默认）的三条规则是：

| 情况 | 第二行 | 透明度 |
| --- | --- | --- |
| 本句有翻译 | 译文 | 0.700 |
| 整首有翻译、本句没有（LRC 写作 `[mm:ss.xx]//`） | `…` 占位 | 0.385 |
| 整首都没有翻译 | 下一句 | 0.432 |

原版 Core 的测试把第二种情况钉死了：

```csharp
Assert.Null(middle.CurrentTranslation);   // 本句没有翻译 → 拿不到内容
Assert.True(middle.HasTrackTranslation);  // 但整首有 → 仍处于翻译模式
```

⚠️ **不要"顺手优化"成回落下一句**：那样第二行会在「译文」和「下一句」之间来回跳，
而且与原版不一致。也不要把「整首没有翻译」那一档设成 0.385——原版此时走的是
SINGLE 布局，`.next-line` 是 0.432。

#### 双语歌词：同一时间戳的两行，必须在解析阶段配对

内嵌 LRC 的双语写法是「原文、译文各占一行、**时间标签完全相同**」：

```
[00:13.68]I'm sittin' here in the boring room
[00:13.68]我呆坐在这了无生趣的房间
```

实测本机 298 个文件里 **17 首（6%）** 是这种写法，共 **561 处配对**，
且一律**原文在前、译文在后**（英文／日文／日文三类语种一致）。
翻译位上的占位符只有一种：QQ 音乐的 `//`。

⚠️ **不配对的后果**：两条记录 `startMs` 相同，而 `indexOfLine` 取的是
「最后一个 `startMs <= 当前位置`」——正好取到后一条（**译文**），
于是主行显示译文、第二行显示下一句原文，**整体错位一行**。

实测数据（`tools/probe/BilingualProbe.java`）：

```
pair-check  298 个文件 / 561 处配对 / 0 失败
auto-check  298 首 / 14588 行 / 0 失败
            ├ 显示译文   527 行
            ├ 显示下一句 13835 行
            └ 显示 … 占位 226 行
```

另外：片头把 3～4 行制作信息挤在同一个时间戳的写法（实测 2 处）
**不合并**——强行配对会直接丢内容，原样保留成多行。


#### 扫描高亮：两层叠加，overlay 的 alpha 由补偿公式反解

原版 `.current-line` 里叠了两个 span：base 整行用 `--secondary`，
scan 层用 `--word-scan-overlay` 覆盖已唱区。

`CreateWordScanOverlayColor` 的公式**唯一目的**是让叠加结果正好等于 primary：

```
overlay = (primaryAlpha − secondaryAlpha) × 255 / (255 − secondaryAlpha)
        = (229 − 153) × 255 / 102 = 190          → 0.745
```

验证：`0.60 + 0.745 × (1 − 0.60) = 0.898`（×0.98 = **0.880**，即 primary）。

⚠️ **踩过的坑**：第二层若直接用 primary(0.882)，合成会变成 `0.951`。
后果不只是偏亮——同一个字形被半透明地画两遍，抗锯齿边缘按
`a₁ + a₂(1 − a₁)` 累积，**每个字的轮廓会长出一圈暗边，看起来像被描了边**。
实测同帧对比（主行峰值亮度，理论值 0.882×255 = 225）：

| | 旧（overlay = primary） | 新（overlay = 0.745） |
| --- | --- | --- |
| 峰值亮度 | 242（= 0.951×255） | **226** |
| 墨密度 | 98.045 | **92.632** |
| 直方图 224–255 档占比 | 46.7% | **35.5%** |

#### 文字阴影：必须真的模糊

```css
text-shadow: 0 1px 2px rgba(0, 0, 0, 0.36);
```

CSS 的 blur radius 与高斯 σ 差 2 倍（**σ = radius / 2**），所以是 σ = 1 DIP，
偏移 (0, 1) DIP。实现上用**三次分离式 box blur** 近似高斯，
box 半径由 `r = (round(√(4σ²+1)) − 1) / 2` 反解。

⚠️ 曾经写成"把字形硬拷贝一份往下挪 1px"（零模糊）。中文字形横画密集，
硬拷贝会在每道横画下缘压出一条实边，观感就是给字描了轮廓。

**注意**：阴影是黑色半透明，压在纯黑背景上**本来就看不见**。
不要用"离屏渲染到黑底后有没有阴影像素"来判断它是否生效——
用原版窗口截图对比更会误判，因为 `PrintWindow` 会把任务栏背景一起抓进来。
判断依据只有一条：`shadowImage()` 产出的 alpha 峰值是否 ≈ `0.36 × 0.98 × 255 ≈ 90`。

#### 字重（笔画粗细）：差在抗锯齿的混合空间，不在字体

**先纠正一个曾经的错误结论**：拿"半行已唱"的渲染去比"整行 primary"的参考，
未唱档 0.588 会把行内均值拉下来，得到"我们细 20%"的**假**结果。
所有字重对比必须在**同一档 alpha**下做（下面都是整行已唱 0.886）。

**基准怎么来的**：原版走 WebView2（Chromium/Skia）。本机 Edge 154 与 WebView2 同版本，
于是用 `msedge --headless=new --force-device-scale-factor=1.5`
渲染一份**完全复刻原版 CSS** 的页面（`tools/probe/ref.html`），
同一句话分别加载思源黑体 Bold 与 Noto Bold —— 这样能把"字体差异"和"光栅化差异"分开。

**实测（同一句「微带着后悔」，21px，整行已唱）**：

| 渲染路径 | 归一墨量 | 笔画均值 |
| --- | --- | --- |
| Chromium + 思源黑体 Bold（原版路径） | **1194.2** | **5.92px** |
| Chromium + Noto Bold | 1184.3 | 6.03px |
| Java2D 不补偿（γ=1.0）+ 思源黑体 | 1124.2 | 5.53px |
| Java2D 不补偿 + Noto | 1112.1 | 5.39px |
| **Java2D γ=1.30 + 思源黑体（默认）** | **1184.3** | **5.99px** |

> 归一墨量 = 行内亮度总和 ÷ 峰值，**与透明度无关**，是字重的直接度量。
> 笔画均值 = 以峰值一半为阈值做水平游程统计的中位/均值，不受文字内容影响。

**两条结论**：

1. **字体只差 1.1%**（思源 1124.2 vs Noto 1112.1）。所以"换字体"不是主因，
   但仍应换——原版用的就是思源黑体。
2. **主因是光栅化**：Java2D 的文字抗锯齿走**线性混合**，
   Chromium / DirectWrite 走 **gamma 校正混合**。深色底上的浅色文字，
   后者明显更实——整整差**约 6%** 的墨量、笔画从 5.99px 掉到 5.53px。

**补偿方式**：把字形覆盖率 c 重映射（`LyricsPainter.gammaLut`）：

```
浅色文字在深底上： c' = c^(1/γ)
深色文字在浅底上： c' = 1 − (1−c)^(1/γ)
```

两个方向物理含义一致，都是在 sRGB 空间里还原线性光混合的结果。
γ=1.30 时归一墨量与笔画宽度都落在参考值 1% 以内，作为默认值。
设置项暴露为 **排版 → 笔画粗细补偿（%）**（100 = 不补偿）。

**顺带的结构改造**：文字不再每帧 `drawString`，而是先画成
**gamma 校正过的覆盖率掩膜**（按 文字+字体+颜色+尺寸 缓存），
再用 `AlphaComposite` 的**恒定 alpha** 合成。好处有两个：
栅格化跨帧复用（只有换行才重做），且透明度不会因多层叠加而漂移。

#### 字体：必须能按**文件**加载，光有家族名不够

原版自带 `SourceHanSansSC-Bold.otf`（字重 700）。本机字体目录里也有
`SourceHanSansSC-Bold-2.otf`，但它**没有注册进系统字体表**——
`GraphicsEnvironment.getAvailableFontFamilyNames()` 的 222 个家族里
既没有「思源黑体」也没有 "Source Han Sans SC"。

所以 `LyricsPainter` 的字体解析是**两级**的，而且**逐名字轮着试**
（不能先把所有名字过一遍家族表，否则会先命中回退链里的 Noto，
永远轮不到思源黑体的文件加载）：

1. 已注册的家族名；
2. 字体目录里同名的字体文件 → `Font.createFont` → `registerFont`（`FontLoader.kt`）。

⚠️ 候选名字里必须**同时有本地化名与英文名**（「思源黑体」/ "Source Han Sans SC"），
否则会静默退到别的字体，字重差一档。

#### 扫描边界羽化

`--word-scan-feather-width: 0.12em`，且被 CSS 收敛到
`min(0.12em, progress, 100% − progress)`。实现方式是**把覆盖层画到临时图层，
再用 `AlphaComposite.DstIn` 乘一道水平线性渐变**（左不透明→右透明）。

`DstIn` 要填满整个位图才可靠——它只在**被绘制的图元范围内**生效，
用形状填充会像封面圆角那次一样静默失效。

#### 扫描进度是连续量，不是「这个字唱完没」

进度来自 `TaskbarLyrics.Core/Services.LyricSyncService.cs` 的
`CalculateWordScanProgress`，它是**按字符数连续插值**的浮点比例——
一个字唱到一半，进度就走完这个字的一半，而不是等到字尾才整字翻过去。
原版自己的测试把这条钉死了（8 字符 `"Hi there"`，第一个 segment 唱到 500ms 时
进度是 `1/8`，不是 0）。

最早这边的写法是整字累加（`if (cell.startMs <= positionMs) acc += 整个字宽`），
于是扫描头一格一格跳，看着就是「一次亮一个字」。两层平滑都缺：

| 层 | 出处 | 值 |
|---|---|---|
| 字内连续插值 | `LyricSyncService.CalculateWordScanProgress` | 见上 |
| 90ms 线性追赶 | `style.css` `.line.word-scan-smoothing` | `--word-scan-progress 90ms linear` |

第二层看着像可有可无，其实有用：宿主 `onPositionUpdated` **每秒才回调一次**，
中间靠外推，每次回调都会校正一次跳变，90ms 追赶正好把它摊开。

**三个容易写错的点**：

1. `indexOf` 从 `searchIndex` **之后**开始找，所以 `"Hi there"` 里第二个字的
   基数是 3 而不是 2（空格算在长度里）。写成累计字宽会得 `4.5/8`，正确是 `5.5/8`。
2. 时长为 0 的字（标点、拖尾）整段计入，不参与插值。
3. 进度换成像素时**不许取整**。取整会把连续量重新量化成台阶，等于白修。
   `Row.sungPx` 因此是 `Double`。

**两个守卫**（来自 `app.js` 的 `setLineWordScanProgress`，缺了会在换行/切歌时拖泥带水）：

- 一次前进 > `0.35` → 直接跳，不平滑；
- 倒退 > `0.02` → 直接跳（换行了）；
- 两端 `0` 和 `1` → 直接跳（中间没有可插的值）。

**没有字级数据时退回匀速**（按行内时间比例）。原版这一步是 `null` → 干脆不画，
但那样凡是没带字级时间的歌整行纹丝不动，比不装插件还像坏了，保留退路。
匀速本身连续，不再过 90ms 追赶器——那只会白白多出一点滞后。

验证：`WordScanProbe` 29 项（含原版用例的 4 个断言点与新旧算法对照）；
`ScanPaintProbe` 4 项（画到画布上量「有多少帧真的变了」）。

```
逐毫秒「有变化」的比例：旧算法 0.3%，新算法 100.0%
单毫秒最大跳变：      旧算法 0.1000（= 整整一个字），新算法 0.000333
```

#### 版式常量（来自 `LyricsLayoutMetrics.cs`，单位 DIP）

```
宿主内边距 8 / 3      歌词面板 顶3 右4 左2
主行/副行纵向偏移 1 / 1       文本底部内边距 2
可下探缓冲 2          两行平分剩余高度
下一句字号 = max(9·scale, 当前字号 × 0.92)
```

`text-rendering: geometricPrecision` 对应 Java2D 的
`KEY_FRACTIONALMETRICS = ON`（默认是取整，字距会比原版紧）。

#### 行切换过渡（滚动动画）

**原版是有动画的**，逐值抄自 `Web/Lyrics/app.js` 与 `style.css`：

| 量 | 出处 | 值 |
| --- | --- | --- |
| 单行滚动时长 | `app.js` `transitionDurationMs` | 560ms |
| 翻译对滚动时长 | `app.js` `translationPairTransitionDurationMs` | 760ms |
| 位移/缩放缓动 | `style.css` `.track.animating` | `cubic-bezier(0.22, 0.72, 0.24, 1)` |
| 滚动距离 | `app.js` `setTrackOffset(n)` = `−linePitchPx × n` | 单行 1 行、翻译对 2 行 |
| 离场行终态 | `style.css` `.current-line.leaving` | 不透明度 0.16、缩放 0.972 |
| 离场行透明度曲线 | `app.js` `getFadeOutEase` | `easeOutCubic(t / 0.74)`，≥0.97 收敛到 1 |
| 提升行透明度曲线 | `app.js` `getFadeInEase` | `easeOutCubic(t / 0.72)`，≥0.96 收敛到 1 |
| 提升行起始缩放 | `app.js` `promotionStartScale` | `nextSize / currentSize` = 0.92 |
| 当前行静止不透明度 | `app.js` `currentLineRestOpacity` | 0.98 |
| 第二行静止不透明度 | `style.css` `.next-line` | 0.72 |

三个容易做错的点：

1. **位移和透明度不是同一条曲线**。位移走 CSS 的 cubic-bezier，透明度是 JS 每帧算的
   `easeOutCubic`，收敛点还不一样（0.74 / 0.72）。混用会让旧行"还没滚出去就已经淡没了"。
2. **翻译对只滚不淡**。`startTranslationPairTransition` 不会给旧行加 `leaving` /
   `promoting`，只把整条 track 上移两行——所以 760ms 里没有透明度动画。
3. **提升行要"按静止位置栅格化，再用变换摆回第二行"**。
   反着写（按第二行位置栅格化再平移）也行，但 t=1 的落点会带上两次取整的误差：
   两行中心都不是整数时，平移量 ≠ 基线差，动画结束会再跳半像素
   （实测让边缘像素差到 132/255）。按静止位置栅格化后 t=1 时缩放=1、位移=0，
   变换退化成恒等，落点与静止帧**逐像素一致**（实测 0/255），
   且与静止帧共用同一份掩膜缓存。

另外补了原版 `.viewport { overflow: hidden }` 这一刀裁剪：滚动出去的行是从
视口上边缘被切掉的，不是淡出到看不见。少了它，旧行会在窗口顶部再露出半截。

**触发条件只有一条**：帧身份（`歌曲#行号#主行文本`）变了。歌词进度推进、已唱高亮
前移都不算——原版里那是 `PROGRESS_PATCH`，原地更新（`presentation.js` 的
`sameLyricsIdentity`）。

实现见 `render/LineTransition.kt`（时长/曲线/状态机）与
`LyricsPainter.buildRows()`（把一帧拆成若干"行"，静止 1~2 行、过渡最多 4 行）。
机械校验见 `tools/probe/TransitionProbe.java`。

#### 帧调度（"丝滑"的真正开关）

曲线抄对了还不够——**画多少帧**才是体感。原版是 Chromium 合成器，动画天然跟着
vsync 走（60Hz 屏 = 16.7ms 一帧）。我们原来写死 33ms 一拍，560ms 只有 16 帧。

实测（`tools/probe/PerfProbe.java`）：单帧绘制**静止 0.19ms、过渡 1.9ms**。
瓶颈从来不是渲染，所以帧预算可以放心压到刷新率间隔。

调度由三件事组成，缺一件都会顿：

1. **帧时间线**，不是"画完再睡多久"。
   `nextFrameAt += budget`（预算 = 1/帧率），绘制耗时被吸收进等待，
   不会逐帧累加。实测：同样的 2ms 绘制，`sleep(16)` 的实际间隔是 18.7ms
   （53fps），时间线是 16.1ms（62fps）。
2. **`timeBeginPeriod(1)`**（winmm）。Windows 默认定时器粒度 15.625ms，
   `sleep(16)` 会被取整到 31.25ms。渲染线程启动时调，退出时 `timeEndPeriod` 配对。
3. **最后 1ms 自旋收尾**。等待唤醒的时刻有 ±1ms 浮动，用 `Thread.onSpinWait()`
   把帧间隔钉死——实测抖动从 1.31ms 降到 0.65ms。只在动画的 560ms 里烧这点 CPU。

帧率档位（设置项 `display.animationFrameRate`）：
`auto`（跟随主显示器刷新率，`EnumDisplaySettingsW` 的 `dmDisplayFrequency`）/
30 / 60 / 120。静止时一律回到 30fps：那时动的只有逐字扫描（约 100px/s，
30fps 每帧 3px，看不出台阶），而每帧都要 `UpdateLayeredWindow`，会让 DWM 一直忙。

**换行动画的起始延迟**靠条件变量而不是 `Thread.sleep`：歌词行一变
（`onLyricsLineUpdated`）立刻 `wake()` 唤醒渲染线程，最坏 33ms 的等待降到接近 0。
不然动画看起来是"先顿一下再动"。

端到端实测（`tools/probe/AnimationProbe.java`，一次 560ms 单行滚动）：

| 帧预算 | 帧数 | 每帧位移（行距 28.5px） |
| --- | --- | --- |
| 33ms（改前） | 16 | 均 1.877px |
| 16.67ms（60Hz） | 33 | 均 0.881px |
| 8.33ms（120Hz） | 67 | 均 0.428px |

调度本身的对照见 `tools/probe/ScheduleProbe.java`。

### 0.A.2b 为什么不用 WebView2 直接跑原版前端

原版 TaskbarLyrics 的歌词页面是 `Web/Lyrics/` 里的 HTML/CSS/JS，由
**WebView2**（`Microsoft.Web.WebView2.Wpf` + `WebView2Loader.dll`）渲染；
本机也装了 Runtime（154.0.4258.62）。所以"干脆把原版前端整个搬过来"看起来很诱人。
评估结论是**不做**，理由按分量排序：

1. **抓帧通道是死结。** 我们的窗口是 `UpdateLayeredWindow` 的分层窗口，
   内容必须由我们自己提交位图。WebView2 在这个架构下唯一的取图方式是
   `ICoreWebView2::CapturePreview`——**异步**、输出是 **PNG 编码的 IStream**，
   还要解码回来。通行经验值是 30~60ms/帧，比 Java2D 的 0.19ms（静止）/
   1.9ms（过渡）慢一到两个数量级。为了"更像原版"反而会把帧率打下去。
2. **另一条路（DComp）与分层窗口互斥。** `ICoreWebView2CompositionController`
   能把内容以 per-pixel alpha 合成到窗口上，帧率也确实能满，但它要绑 DirectComposition
   visual，而 layered window 不接受外部合成。改用它就得放弃分层窗口的透明叠加。
3. **宿主 JVM 是 jlink 裁剪的**，只有 18 个模块，**没有 JavaFX**，
   `javafx.web`（老 WebKit）这条路直接不存在。
4. 走 WebView2 还意味着要在 JNA 里手写几十个 COM 接口（Environment / Controller /
   CompositionController / IDComposition*），并且会拉起 Chromium 的多进程
   （常驻 150~300MB）——对一个音乐播放器插件来说太重了。

**真正决定丝滑的不是渲染引擎，是帧数。** 实测单帧绘制 0.19ms / 1.9ms，
我们的绘制比 Chromium 还便宜；改前不丝滑纯粹是因为节拍写死在 33ms。
见下一节「帧调度」。

### 0.A.3 配置界面

#### 调设置时画面必须全程可见（不能重建窗口）

以前的做法是把几何相关的设置合成一个 `geometryKey`，一变就 `destroy()` →
`sleep(600)` → `CreateWindowEx`。结果是拖 xOffset / widthPercent 时每次都要黑屏
600ms，用户等于在盲调；而且 layoutScalePercent 这种**根本不影响窗口大小**的设置
也被算进 key 里，白重建。

WPF（原版）改 `Window.Left` / `Window.Top` / `Window.Width` 只是 Move/Resize，
没有任何理由在这里动窗口本身。现在：

| 变化 | 处理 |
| --- | --- |
| 位置、宽度占比、固定宽度、偏移量、缩放、锚点、DPI | `TaskbarLayerWindow.setBounds()`——同一个 hwnd 上 SetWindowPos |
| 尺寸真的变了 | 顺便重建幕后 DIB + BufferedImage（DIB 尺寸是定的，改不了） |
| 嵌入 ↔ 浮动条 | **唯一**必须重建的情况：父窗口在 CreateWindowEx 时定死 |
| 任务栏句柄失效（Explorer 重启） | 重建 |
| 暂时没有足够宽的空闲区 | 画面保持原样等下次重扫，绝不拆窗口（否则弹个通知歌词就闪一下） |

三个实现要点：

1. **换 DIB 要先把旧的从 DC 里摘出来**。被 `SelectObject` 选中的位图 `DeleteObject`
   是删不掉的：先选一个 1×1 的替身进去，旧图换出后删掉，再把新 DIB 选入、删替身。
2. **`setBounds` 返回"到底动没动"**。几何没变时不碰 SetWindowPos——不然每帧一次
  系统调用，纯粹给 DWM 添堵。
3. **任务栏空闲区每 500ms 重扫一次**（`SCAN_INTERVAL_MS`）。图标增减会让空闲段变化，
   但 `findTaskbars` + `freeSlots` 要枚举窗口，逐帧做太贵。

验证见 `tools/probe/GeometryProbe.java`：真实任务栏上 hwnd 不变、GetWindowRect 跟上、
回读不透明像素数 > 0（分层窗口尺寸与 `UpdateLayeredWindow` 的 psize 不同步时会全黑），
连续 11 次改几何墨量最少的一帧仍有 21600 像素。11 项断言全过。

#### 挑空闲区必须把自己排除在外，还要加迟滞

**症状**：歌词窗口每 500ms 在任务栏左右两边闪一次。真机录的位置序列：

```
x: 1577,1577,559,559,1577,1577,559,559,...  跨度 1018px（占 2560px 任务栏的 39.8%）
10 秒内 20 次跳变，周期正好等于重扫间隔 500ms
```

**根因是自我指涉**：`freeSlots()` 把任务栏的*所有*可见子窗口当成占用区，而
embed 模式下歌词窗口**自己就是任务栏的子窗口**。于是每轮重扫都把歌词当前占的
那块地划走，剩下的两块里"最宽的"必然是另一块 → 挪过去 → 下一轮又反过来。

两块空闲区始终都在：`[558..1116 w=558]` 和 `[1578..2017 w=439]`（本机实测）。
只要包含自身，就永远两只靴子轮流穿。

修两层（`Win32.freeSlots` 与 `render/SlotChooser.kt`）：

1. **扫描时按 `LYRICS_WINDOW_CLASS` 把自己排除**。这也是为什么窗口类名要写成
   `Win32` 上的公开常量——两处必须一致。
2. **选择加迟滞（`SlotChooser`）**：当前这一块还放得下就继续用，另一块必须
   **同时**宽出 `SWITCH_MIN_PX`(60) 和 `SWITCH_MARGIN_PERCENT`(25%) 才值得跳。
   光做第 1 点是不够的——任务栏上的托盘时钟（`9:05` → `12:34`）、网速浮窗位数、
   输入法图标进出都会让空闲区边界抖几像素，锚点 `WIDEST` 照样会反复换优选。
   另外 `setBounds()` 忽略 1px 以内的漂移，避免为看不见的移动重建 DIB。

边界处理：用户改锚点时**不被**迟滞粘住（那时必须听他的）；当前那块彻底没了
（窄于 `MIN_SLOT_WIDTH`）或 Explorer 重启会让位重选。判定"还是原来那块"用
**重叠面积 ≥ 一半**，不能拿 `start` 直接比——稍有抖动就失配了。

#### 暂停时的显隐是渐变，不是消失

用户反馈"暂停时窗口直接消失，播放后直接弹出"。原版把「没在播放」当成一个
场景（`presentation.js` 的 `SCENES.NO_PLAYBACK`），场景切换有过渡；窗口级的
淡入淡出参数则来自 `style.css` 的 `.lyrics-layer` 层过渡：

| 量 | 原版位置 | 值 |
| --- | --- | --- |
| 时长 | `--layer-transition-duration` | 300ms |
| 位移 | `--layer-transition-offset` | 2px（隐藏态 `translateY(-2px)`） |
| 曲线 | `.lyrics-layer` 的 `transition` | `cubic-bezier(0.2, 0.9, 0.1, 1)` |

实现分两半，**缺一半都不成立**（`render/SurfaceFade.kt`）：

1. **透明度交给系统**：`UpdateLayeredWindow` 的 `BLENDFUNCTION.sourceConstantAlpha`
   就是整个窗口的整体透明度，由 DWM 合成时才乘上去——淡出期间**一个像素都不用重画**。
   千万别在画布里逐层乘 alpha：各图层已经 alpha 混合过，再单独乘每一层与
   「整体乘」不等价，正确做法只能离屏合成后整体乘，多一次全画布 blit。
2. **位移加在绘制上**：`LyricsPainter.paint` 的 `offsetY` 参数。⚠️ **不能**用
   `Graphics2D.translate()` —— `setClip(Shape)` 会把入参按当前 transform 变换，
   而这份代码 `getClip()/setClip()` 反复配对（视口裁剪、扫描裁剪矩形），
   带平移就会每次累积漂移。位移显式加到各绘制点的 y 与 `drawImage` 的变换上。

隐藏期间的 300ms 里**必须继续画最后一帧内容**（`TaskbarRenderer.lastContent`），
否则淡出第一帧画布就空了，等于没过渡。淡出结束才放掉内容、把帧率退到 10fps。
过渡必须**可逆**：暂停到一半点播放，从当前程度继续走，不能沉到 0 再浮上来
（`SurfaceFade.retarget` 把当前 level 当新起点；重复设同一目标不重启时间线）。

验证：`SurfaceFadeProbe`（28 项：曲线/时长/可逆/幂等，独立二分实现的
cubic-bezier 复算期望值，不用被测代码自证）+ `FadePaintProbe`（7 项：offsetY=0
与默认路径逐像素一致、offsetY=6 = 原图下移 6px、视口外被裁）。
`FadeRenderProbe` 做屏幕级端到端（同一画布只改窗口 alpha，`BitBlt` 抓屏对照
基线）——⚠️ `PrintWindow` 读到的是合成**前**的内容，每像素 alpha 恒 255，
验证不了 `SourceConstantAlpha`；另外熄屏/锁屏时抓到的全是黑，探针会自动降级。

#### 窗口自己的生与死，也得走同一条曲线

上面那一节只覆盖「内容显隐」。用户接着指出：**软件打开时窗口是直接弹出来的，
关闭时也是直接消失的**——这两条路径跟播不播放毫无关系，窗口是硬创建、硬销毁的。

对应关系是这样的：暂停时 window 还活着，只是内容没了；而这里**连 window 本身都要退场**。
所以除了把同一个 `SurfaceFade` 接到生命周期两端之外，还有三件事跟暂停那套不一样：

1. **`destroy()` 必须等动画跑完**，不能一到 `stop()` 就拆窗口。先拆再补动画，
   画面上一帧都看不到——等于白写。`dismissWindow()` 是**阻塞式**的：把 fade 跑到 0，
   每帧真的 `UpdateLayeredWindow` 推上去，然后才 `destroy()`。
   等待要用独立的 `park()` **而不是 `waitUntil()`**：后者循环条件是 `while (running)`，
   退出时 `running` 已是 false，它会立刻返回，整段淡出会在一个循环里挤完。
   另外给了硬上限 `EXIT_FADE_MAX_NANOS`（700ms）——宿主可能正在关窗流程里，
   插件没资格把它拖住。
2. **每次建成窗口都要从「完全隐藏」起步**（`SurfaceFade.beginHidden`），目标也设成隐藏。
   注意不能用 `snapTo(false)`：它把起点和目标都设成 false 之后两者相等，
   第一帧照样满亮度。必须让 `target` 也是 0，随后的第一次 `retarget(true)`
   才能正经起一条 300ms 的时间线。
3. **首帧那一笔冷启动开销必须落在时间线之外**。Java2D 第一次做文字布局要初始化
   字体子系统，本机实测**第一帧 636ms，之后每帧 0.4ms**（`FirstPaintCostProbe`）。
   这笔钱原本正好卡在第一帧与第二帧中间，而淡入从第一帧起算——
   结果就是"透明度 0 上屏 → 741ms 后直接满亮度"，300ms 的过渡早跑完了，
   用户看到的仍旧是啪一下。两条对策同时上：
   - `LyricsPainter.preload()` 在**建窗口之前**（那时画面上一个像素都没有）先把账付掉；
     它是进程级的一次性成本，`preloaded` 标志保证只做一次。代价是启用插件到画面出现
     之间多了约 490ms 的静默（SPW 进程内若 Java2D 已热，会更短）。
   - `introPending` + `SurfaceFade.begin(level, 1.0, now)`：第一帧**推上屏之后**
     才把时间线原点挪到那一刻，兜住任何未知的首帧开销。

判断某次退出要不要动画，看画面当时到底存不存在：任务栏句柄失效（Explorer 重启）、
窗口被外力销毁这两种情况画面早已不在，白等 300ms 没意义；插件停用、切换
嵌入/浮动条这两种看得见，就得淡。

验证：`WindowFadeProbe` 在真实渲染线程上跑「启动 → 稳定 → 关闭」两个来回（开动画 +
对照组），19 项断言。可信度来自两件事：
- **`PrintWindow` 不可用、熄屏抓不到像素**，所以透明度机读**渲染线程自己写的留档**
  （`TaskbarRenderer.alphaTrace()`，淡变期间逐帧记录，静止时去重）；
- 单看透明度序列不足以证明，还必须证明**窗口是被等完才拆的**——所以同时测
  `stop()` 到返回之间的真实耗时。对照组（`animateAutoHide = false`）必须两项同时塌掉
  （stop 2ms、留档 0 条），那 300ms 的来源才归因得准。

⚠️ 期望值不许来自被测代码：探针里带了一份**独立实现**的 cubic-bezier。
另外**中段核验**（α 192→64 的时间差）比「首帧到收尾」靠谱——首帧那边时间线原点会被重设，
收尾那边 8 位 alpha 早已饱和（乘积 < 0.5 即归零）而状态机要收到 1e-4 才停，两头都不干净。

#### seekbar 是连续浮点：得反过来把它吸附回整数

宿主支持五种控件：`switch` / `list`（`entries` + `entry_values`）/
`seekbar`（**浮点**，代码里要用 **Float** 读）/ `edittext` / `button`。

`ConfigHelper.get(key, default)` 的签名是 `<T> T get(String, T)`，
**键缺失或类型不符时返回默认值而不抛异常**——所以类型写错最差是回落成默认值，
这让界面可以放心用 seekbar 存数字。

设置按五组拆分，每组一个配置文件：
`general.json` / `display.json` / `typography.json` / `appearance.json` / `cover.json`。

宿主没有 step 字段（官方示例的 seekbar 只有 `min` / `max` / `default_value`），
滑块位置是 `(max − min) × 比例 + min`，所以拖一下会存下：

```json
{"typography":{"textGammaPercent":130.121,"fontSize":13.025173}}
```

界面上的数字也就跟着是一长串小数。因为改不了它的步进，这里**反过来做**——
`PluginSettings.snapNumericSettings()`：注册在配置监听器里，值一落盘就把它
四舍五入、按 min/max 夹一下，再用 `set()` + `save()` 写回同一个键。
宿主会收到自己的写入，但那时值已经是整数了，第二轮直接跳过，不会来回抖。

三个必须踩准的点：

1. **先试写 Int，读回来核对，不认再退 Float**。按 Int 写界面显示 `130`，
   按 Float 写是 `130.0`——宿主从头到尾只认浮点时（`get` 会对 Int 返回默认值）
   才会退到 Float，两种类型的上限都要保证**读回来的值是对的**。
2. **`@Synchronized` 挡不住自己**。写回会触发自己的监听器、监听器又回调进来，
   而对象锁是可重入的。必须再加一个 `snapping` 标记，让嵌套的那次直接返回，
   由外层把一轮走完（实测漏了它，一轮会被嵌套的那次抢走 10 个键）。
3. **读取侧一律自己取整**（`PluginSettings.int()`）。写回有可能失败（宿主不让写），
   那时最坏是界面继续显示小数，但渲染用的值必须已经是整数——字号、圆角、
   偏移量都容不下 13.025173 这种值。

`preference_config.json` 里的 `min` / `max` / `default_value` 也全部写成整数字面量
（`130` 而不是 `130.0`），这样界面一进去就是整数，不必等第一次吸附。

验证见 `tools/probe/SettingsSnapProbe.java`：假 ConfigHelper 模拟宽松/严格两种宿主，
13 个样本（含负数、超上下限、已是整数），34 项断言全过。

### 已知限制

- **仅适配任务栏在底部**。任务栏在顶部/左右时几何算法需要另行处理（目前未实现）。
- **Explorer 重启后**任务栏窗口会失效，需要监听并重建。
- 逐字动画依赖字级时间，而本机内嵌歌词**没有字级标签**，只能按字数合成。

---

## ⚠️ 0. 版本现实（先读这一节，它推翻了初版设计的若干前提）

以下结论全部来自**实测**（读本机 SPW 发行包与 jitpack 制品），不是文档推断。

### 0.1 SPW 从未发布过 1.19.0

官方更新日志里最高版本是 **1.18.5**（本机安装的正是 1.18.5，读自
`app/Salt Player for Windows.cfg` 的 `-Djpackage.app-version`），下面是 1.18.0 / 1.17.1。
**1.19.0 不存在。**

而插件 API 文档里一批关键能力标注的是 `@SinceApi("1.19.0", ...)`，即**目前任何用户都用不到**。

### 0.2 现役 SPW 的真实接口面（以 API `0.1.0-dev20` 为准）

下载 dev20 的 API jar 后逐个核对，它只有 **10 个类**：

```
SinceApi, SpwPlugin, PluginContext, PlaybackExtensionPoint,
UnstableSpwWorkshopApi, ConfigManager, ConfigHelper, WorkshopApi,
Channel, WorkshopPluginManager
```

| 能力 | 1.18.x 实际 |
| --- | --- |
| `onLyricsLinesUpdated`（完整歌词时间轴） | ❌ 不存在 |
| `onLyricsLineUpdated`（当前行） | ✅ 存在，**唯一歌词数据源** |
| `LyricsLine` 的 `startTime/endTime/lyricsCells/pureMainText/pureSubText` | ✅ **字段齐全**，逐字与翻译可做 |
| `WorkshopApi.library`（读内嵌封面） | ❌ 不存在 |
| `MediaItem` 字段 | 只有 `title/artist/album/albumArtist/path`，**无 `id`、无 `duration`** |
| `PluginPermission` / 权限系统 | ❌ 整类不存在 |
| `hookRegistrar` / `HookHandle` / Hook 全部 | ❌ 不存在 |
| `KeyBindingManager` / `ActionShortcut` | ❌ 不存在 |
| `PluginContext` | ✅ 有 `pluginId/pluginVersion/pluginPath/spwVersion/spwChannel` |
| `ConfigHelper` | ✅ `get/set/save/reload/getConfigPath` |

**结论：必须编译到 dev20，且不要引用任何 dev21/dev22 的成员。**
编译到 dev20 的好处是编译器会替我们拦住误用。

### 0.3 打包方式必须手工实现

`spw-workshop-gradle-plugin` 只发布了 **dev21 与 dev22**（多模块时代产物），
dev20 没有配套打包插件。因此本工程不使用 `com.xuncorp.spw.workshop` 插件，
改为手工 jar + Manifest 属性 + zip（结构：`classes/` + `lib/`），
这一做法与社区插件 TaskbarLyricsPlugin 一致，已在 SPW 上验证可用。

### 0.4 编译只需要 JDK 21，不是 JDK 25

dev20 的 class 文件版本是 **major 65 = Java 21**。
（文档里"Java 25 + Kotlin 2.4.20"指的是 dev22，不适用于本工程。）
宿主运行时是 Java 25（`runtime/release` 中 `JAVA_VERSION="25.0.3"`），
输出 Java 21 字节码两边都能跑。

### 0.5 宿主 JVM 是裁剪过的 jlink 镜像，可用模块非常少

`runtime/release` 的 `MODULES` 只有：

```
java.base java.compiler java.datatransfer java.xml java.prefs java.desktop
java.instrument java.logging java.management java.security.sasl java.naming
java.security.jgss java.transaction.xa java.sql jdk.accessibility
jdk.crypto.ec jdk.security.auth jdk.unsupported
```

由此必须遵守：

| 缺失模块 | 后果 |
| --- | --- |
| `jdk.httpserver` | **不能用 `com.sun.net.httpserver`**，会 `NoClassDefFoundError`。本工程手写 `ServerSocket`（见 `TinyHttpServer.kt`） |
| `java.net.http` | 不能用 Java 11 的 `HttpClient`，需 HTTPS 时只能用 `HttpsURLConnection` |
| `jdk.zipfs` | 不能用 `FileSystems.newFileSystem(zip)`，解压只能用 `java.util.zip.ZipInputStream` |
| `jdk.charsets` | GBK/Big5 等扩展字符集不可用，注意 GBK 编码 LRC 的读取 |

`java.desktop` 在，所以 Swing/AWT 可用。

### 0.6 官方文档有一处坐标错误

文档给的 `com.github.Moriafly:spw-workshop-api:0.1.0-dev22` 在 JitPack 上返回的是
**308 字节的坏制品**。多模块重构后正确的坐标应为
`com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev22`
（dev20 及更早仍是单模块坐标 `com.github.Moriafly:spw-workshop-api`）。

### 0.7 对方案的影响

1. **歌词时间轴改为「边播边攒 + 按歌曲持久化」**，不再有"一次拿全"。
   首次播放从进入的那一句开始攒，前面没播到的行拿不到；二次播放直接读缓存。
2. **封面不由插件提供**。实测 SPW 向 SMTC 暴露的缩略图可用（`HasThumbnail: True`），
   ANYNC 本来就会从 SMTC 取封面，因此**插件不需要任何权限**。
3. **零权限**是硬性要求：1.18.x 根本没有权限系统，声明权限也无意义。
4. 1.19.0 发布后可以升级到 dev22 并恢复"完整时间轴 + 内嵌封面"，
   届时用 `PluginContext.spwVersion` 做运行时分支即可。

---

## 1. 目标与非目标

**目标**

1. 用 Salt Player 自己解析好的歌词与封面驱动任务栏歌词，**不发起任何网络歌词检索**。
2. 免手动启动：插件随播放器启用而自动托管渲染器。
3. 可发布（GitHub / Steam 创意工坊），包体积与依赖门槛可控。

**非目标**

- 不改写 ANYNC 的 UI 层（逐字扫描、翻译、主题、频谱全部原样保留）。
- 不追求零依赖：渲染器仍需要 .NET 8 桌面运行时（走检测 + 引导，不自带）。

## 2. 为什么不能只做「进程托管」

ANYNC 的歌词来源全是它自己上网搜的（QQ 音乐 / 网易云 / 酷狗 / LRCLIB），
它通过 Windows SMTC 拿歌曲身份，再拿这个身份去各平台匹配。

实测 SPW 已经把自己完整注册进 SMTC（标题 / 歌手 / 专辑 / 封面 / 播放状态 / 精确播放位置都有），
所以 ANYNC 能驱动 SPW——**但用的是网上搜来的歌词，不是 SPW 已解析的那份**。

而 SPW 侧的数据质量明显更高：

| SPW 提供 | 说明 |
| --- | --- |
| `onLyricsLineUpdated(LyricsLine?)` | 当前歌词行。**1.18.x 上唯一的歌词数据源**（完整时间轴要 1.19.0） |
| `LyricsLine.lyricsCells` | 逐字单元，每字带 `startTime` / `endTime` / `text` |
| `LyricsLine.pureMainText` / `pureSubText` | 原文与翻译 |
| `LyricsLine.startTime` / `endTime` | 该行的时间范围 |
| ~~`WorkshopApi.library.getCoverById(id)`~~ | ❌ 1.18.x 不存在，封面改由渲染器从 SMTC 缩略图取 |

对于本地音乐、冷门曲目，网络检索经常搜不到或搜错版本，而 SPW 手上就是正确的歌词。

## 3. 关键改动点（本方案成立的基础）

`LyricResolutionCoordinator.ResolveWithResultAsync` 的执行顺序是**严格阶梯式**：

```
结果缓存 → 歌曲映射短路（纯音乐 / 首选源） → 本地 provider → 在线多源并发
```

并且本地分支命中后**直接返回**：

```csharp
var local = await ResolveLocalAsync(mappedTrack, token);
if (local is not null)
{
    return LyricResolutionResult.FromLyrics(local);   // 在线源一个都不发起
}
var online = await ResolveOnlineAsync(mappedTrack, searchPlan, requestId, selection.Order, token);
```

因此有两条互不干扰的注入路径：

| 注入点 | 作用 | 复杂度 |
| --- | --- | --- |
| `localProvider`（`ILyricProvider`） | 供词，且天然短路全部在线检索 | 极低：只实现一个方法 |
| `sourceSelectionResolver` 返回空 `Order` | 兜底彻底禁掉在线检索（当 SPW 无歌词时也不上网） | 极低：包一层 lambda |

**不需要**动 `ILyricSource`、`LyricifyPayloadDecoder/Parser`、`LyricProviderTrustPolicy`、`KnownLyricProviders`
（那些只服务于在线源）。这是本方案最重要的收益。

第二处的依据是 `ResolveOnlineAsync` 开头：

```csharp
if (trustOrder.Count == 0)
{
    return LyricResolutionResult.FromLyrics(null);   // 直接判定无歌词，不发任何请求
}
```

## 4. 整体架构

```
Salt Player for Windows（宿主 JVM，Compose Desktop）
  └── SPW 插件（Kotlin，本仓库）
        ├── PlaybackExtensionPoint   ← 收歌曲身份 / 歌词时间轴 / 播放位置
        ├── WorkshopApi.library      ← 读内嵌封面（LIBRARY_READ）
        └── 本地 HTTP 服务 127.0.0.1:<port>
              ├── /api/v1/state
              ├── /api/v1/lyrics
              └── /api/v1/cover
                    ▲ 回环取词，无外网
                    │
  TaskbarLyrics.exe（fork 版，C#/.NET 8 + WPF + WebView2）
        ├── SpwLyricProvider : ILyricProvider      ← 新增
        ├── 组合 provider（SPW 优先，回退本地文件）  ← 新增
        └── 其余原样（SMTC 会话、窗口、WebView2 歌词 UI、频谱）
```

歌词窗口、逐字动画、主题、设置页、频谱全部走 ANYNC 原实现，插件与渲染器之间只多了一条回环 HTTP。

## 5. 协议 v1（插件 ↔ 渲染器）

全部为 `GET`，仅监听 `127.0.0.1`。端口由插件选定并写入插件数据目录的 `endpoint.json`，
渲染器启动时读取该文件拿到端口，避免硬编码冲突。

### `GET /api/v1/state`

```json
{
  "schema": 1,
  "revision": 12,
  "hasSong": true,
  "song": {
    "key": "a1b2c3d4",
    "title": "红豆",
    "artist": "王菲",
    "album": "唱游",
    "durationMs": 256027
  },
  "lyricsState": "ready",
  "lyricsRevision": 3,
  "timelinePartial": true,
  "suppressOnlineSearch": true
}
```

- `lyricsState`：`pending` | `ready` | `none`
- `timelinePartial`：**恒为 true**。提示渲染器这条时间轴是边播边攒的，
  可能不含本曲开头部分（见第 0.7 节第 1 条）
- `suppressOnlineSearch`：插件侧策略，渲染器据此决定是否禁用在线源
- `revision`：歌曲身份版本，变化即代表换曲

### `GET /api/v1/lyrics?songKey=<key>&revision=<n>`

```json
{
  "schema": 1,
  "revision": 3,
  "songKey": "a1b2c3d4",
  "source": "spw-embedded",
  "lines": [
    {
      "startMs": 12000,
      "endMs": 16000,
      "text": "还没好好地感受",
      "translation": "Before I could truly feel it",
      "cells": [
        { "startMs": 12000, "endMs": 12500, "text": "还" },
        { "startMs": 12500, "endMs": 13100, "text": "没" }
      ]
    }
  ]
}
```

无歌词时返回 `404` 或 `{"lines": []}`，渲染器据此走降级。

### `GET /api/v1/cover?songKey=<key>`

**不提供。** SPW 1.18.x 没有曲库 API，插件拿不到内嵌封面字节。
封面由渲染器自己从 SMTC 缩略图获取（实测 SPW 暴露的 `Thumbnail` 可用），
因此这个接口取消，插件也不需要申请任何权限。

如果后续 SPW 升到 1.19.0，可以恢复本接口并申请 `LIBRARY_READ`。

## 6. 数据映射（SPW → ANYNC）

| SPW | ANYNC | 说明 |
| --- | --- | --- |
| `LyricsLine.pureMainText` | `LyricLine.Text` | 原文 |
| `LyricsLine.pureSubText` | `LyricLine.Translation` | 翻译，可能为 null |
| `LyricsLine.startTime` | `LyricLine.Timestamp` | `Long ms → TimeSpan` |
| `LyricsLine.lyricsCells[i].startTime` | `LyricSyllable.RelativeOffset` | **需减去本行 startTime**（ANYNC 用的是相对偏移） |
| `LyricsLine.lyricsCells[i].endTime - startTime` | `LyricSyllable.Duration` | 单字时长 |
| `LyricsLine.lyricsCells[i].text` | `LyricSyllable.Text` | 单字文本 |
| `MediaItem.title/artist/album` | `TrackInfo.Title/Artist/Album` | 用于身份校验 |
| `MediaItem.duration` | `TrackInfo.Duration` | 匹配用 |

注意两个坑：

1. **逐字时间是相对的**。`LyricSyllable(TimeSpan RelativeOffset, TimeSpan Duration, string Text)`，
   而 SPW 的 `Cell.startTime/endTime` 是绝对时间，必须换算。
2. **`LyricLine` 没有 endTime 字段**（只有 `Timestamp`）。行结束时间由 ANYNC 的
   `LyricDocumentSemanticProjector` 按下一行推断，SPW 的行 `endTime` 会被丢弃——
   但逐字 `Duration` 保留，逐字高亮不受影响。

## 7. 改动清单

### 7.1 新增（fork 的 ANYNC 仓库内）

| 文件 | 内容 |
| --- | --- |
| `TaskbarLyrics.Core/Services.SpwLyricProvider.cs` | 实现 `ILyricProvider`，调插件本地服务，构造 `LyricDocument` |
| `TaskbarLyrics.Core/Services.CompositeLocalLyricProvider.cs` | 依次尝试 SPW → 原 `LocalLyricProvider`，任一命中即返回 |

### 7.2 修改（fork 的 ANYNC 仓库内，共两处）

**`TaskbarLyrics.App/AppCompositionRoot.cs` → `CreateLyricResolutionCoordinator`**

```csharp
var spwProvider = await SpwEndpoint.TryCreateAsync();   // 读 endpoint.json，探测存活
var localProvider = spwProvider is null
    ? (settings.EnableLocalLyrics && settings.LocalMusicFolders.Count > 0
        ? new LocalLyricProvider(settings.LocalMusicFolders) : null)
    : new CompositeLocalLyricProvider(
        spwProvider,
        settings.EnableLocalLyrics && settings.LocalMusicFolders.Count > 0
            ? new LocalLyricProvider(settings.LocalMusicFolders) : null);
```

并把 `sourceSelectionResolver` 包一层：

```csharp
sourceSelectionResolver: track =>
    spwProvider is { SuppressOnlineSearch: true }
        ? new LyricSourceSelection([], null)                 // 禁掉全部在线源
        : settings.GetLyricSourceSelection(track.SourceApp)
```

**注意**：`LyricResolutionCoordinator` 构造时若 `sources` 为空数组，
默认的 `LyricProviderTrustPolicy.CreateDefault` 会因「信任顺序包含未注册源」抛异常。
本方案不传空数组（`sources` 保持原样），只在 `sourceSelectionResolver` 返回空 `Order`，
因此**不触发该校验**。若后续要传空数组，必须同时传 `new LyricProviderTrustPolicy([], [])`。

### 7.3 本插件仓库（Kotlin）

| 文件 | 职责 |
| --- | --- |
| `TaskbarLyricsPlugin.kt` | 生命周期：采集状态 + 起服务 + 启动渲染器（已有骨架） |
| `playback/PlaybackExtension.kt` | 收 `onBeforeLoadLyrics` / `onLyricsLinesUpdated` / `onPositionUpdated` |
| `lyrics/LyricsStore.kt` | 持有当前歌曲身份、歌词时间轴、封面字节，维护 revision |
| `server/LyricHttpServer.kt` | 回环 HTTP 服务，实现三接口 |
| `renderer/RendererLauncher.kt` | 启动 / 停止渲染器（已有） |
| `renderer/RendererLocator.kt` | 定位渲染器（已有） |
| `config/PluginSettings.kt` | 设置读取（已有，需增加端口与「仅内置歌词」开关） |

## 8. 权限

**本插件不申请任何权限。**

原因有两层：

1. **技术上做不到声明**：SPW 1.18.x 的 API 里根本没有 `PluginPermission` 类
   （dev20 的 jar 只有 10 个类，权限体系不存在），Manifest 里写权限也没有意义。
2. **实际上也不需要**：歌词与播放状态来自扩展点回调（不需要权限），
   封面由渲染器从 SMTC 缩略图获取（不需要权限）。

未来 SPW 升级到 1.19.0、要恢复"插件直接提供内嵌封面"时，
再申请 `LIBRARY_READ`（届时可以用 `PluginContext.spwVersion` 做版本分支）。
**无论如何都不要申请 `CLASS_TRANSFORM`**（最高危，仅 UI Hook 需要）。

## 9. 风险与降级

| 风险 | 处理 |
| --- | --- |
| **首次播放前半段歌词拿不到**（1.18.x 只能边播边攒） | 这是宿主能力的硬限制，无法绕过。已做按歌曲持久化：二次播放直接读缓存，立刻完整。缓存目录 `<插件数据目录>/lyrics-cache/<songKey>.tsv` |
| 缓存写入过于频繁 | 合并 1.5 秒内的多次变更再落盘（防抖） |
| 渲染器先于插件启动 | `endpoint.json` 方案：渲染器启动时读文件探测，失败则回退原在线检索 |
| SPW 尚未加载完歌词 | `lyricsState = pending`，渲染器等待下一次拉取，不判定为「无歌词」 |
| SPW 不明确告知「无歌词」 | 用时间窗判定：换曲后 5 秒仍无任何歌词行则置 `none` |
| 插件未运行 / 端口不通 | `SpwLyricProvider` 返回 null，渲染器行为与改造前完全一致 |
| 换歌瞬间歌词错配 | 请求带 `songKey`，响应带 `songKey`，不一致则丢弃（返回 409） |
| 渲染器被杀后残留 | 插件 shutdown hook + 启动前存活检测（已实现） |
| 端口冲突 | 在 47300-47399 内逐个探测可用端口 |
| SPW 无歌词且用户要求不联网 | `suppressOnlineSearch = true` → 在线源 Order 置空，显示无歌词 |
| 宿主 JVM 缺模块 | 不用 `jdk.httpserver` / `java.net.http` / `jdk.zipfs`（见第 0.5 节） |

## 10. 发布注意

1. **授权**：ANYNC 为 MIT。fork 必须原样保留 `LICENSE` 与版权声明，
   并在 README 明确标注「基于 ANYNC/TaskbarLyrics 修改」，说明改动范围。
2. **体积**：走框架依赖（不做 self-contained），渲染器约 15-25 MB；
   插件启动时检测 `Microsoft.WindowsDesktop.App 8.x`，缺失则提示安装链接。
   这样 `.spmod` 能压在 Steam 创意工坊上限内。
3. **命名**：插件 ID 唯一且稳定（`com.spwtb.spw-taskbar-lyrics`），更新时只递增版本号。
4. **打标**：GitHub 仓库加 `salt-player-plugins` topic。
5. **不建议混淆**：SPW 官方鼓励 Mod 开源且不混淆代码。
