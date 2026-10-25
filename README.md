# SPW Taskbar Lyrics

![任务栏歌词 · Taskbar Lyrics](docs/cover.png)

把歌词显示到 Windows 任务栏上的 Salt Player for Windows 插件。

**插件自己取歌词、自己画到任务栏，不依赖任何外部程序。**
装完之后就不再需要额外的任务栏歌词工具了。

**下载**：[GitHub Releases](https://github.com/wen125dukhvuidh/spw-taskbar-lyrics/releases)

> 本插件的任务栏嵌入方案、设置体系与歌词视觉参数参考/复刻自
> [ANYNC/TaskbarLyrics](https://github.com/ANYNC/TaskbarLyrics)（MIT）。
> 完整的第三方许可与版权声明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

## 它是怎么工作的

```
Salt Player for Windows 进程（Java 25，jlink 裁剪运行时）
  │
  ├─ SpwPlaybackExtension        播放扩展点，收当前歌词行与播放位置
  │     onBeforeLoadLyrics / onLyricLineUpdated / onPositionUpdated
  │
  ├─ LyricsStore                 歌词来源
  │     ① 内嵌标签解析（FLAC / MP3，一次拿到完整时间轴）
  │     ② 同目录 .lrc
  │     ③ 逐行累加兜底
  │
  ├─ CoverArt                    从音频文件读内嵌封面（不联网）
  │
  └─ TaskbarRenderer             渲染线程（唯一线程，所有 Win32 调用都在它上面）
        ├─ Win32（JNA）          找任务栏 → 算子窗口求空闲横条
        ├─ TaskbarLayerWindow    CreateWindowExW 建逐像素透明分层窗口
        ├─ LyricsPainter         Java2D 画到 TYPE_INT_ARGB_PRE 位图
        └─ UpdateLayeredWindow   推送（ULW_ALPHA）
```

为什么不拉起外部渲染器：需求是「不依赖外部程序、只靠插件」。
而且宿主显式开放了原生调用（见下），插件内直接调 Win32 是被预期的路径。

## 现在到哪一步了

| 阶段 | 状态 |
| --- | --- |
| SPW 版本 / 接口面 / 宿主 JVM 模块实测 | ✅ |
| 插件内任务栏渲染可行性验证 | ✅ 分层窗口可嵌入任务栏、真实像素回读成功 |
| 歌词采集（内嵌解析 + 封面提取） | ✅ |
| 配置界面（五组，参考 TaskbarLyrics 的设置体系） | ✅ |
| **编译打包** | ✅ 产出约 628 KB 的插件包（JNA 只带 Windows 原生库） |
| **渲染栈端到端自检**（宿主的 18 个模块 + 插件真实类） | ✅ 333 帧无异常 |
| **文字渲染按原版源码逐项对齐**（透明度 / 扫描叠加 / 阴影 / 版式） | ✅ 与源码公式一致 |
| **字重与 Chromium 对齐**（字体按文件加载 + gamma 补偿） | ✅ 墨量/笔画差 <1% |
| **双语歌词配对**（同时间戳两行 → 原文 + 译文） | ✅ 298 文件 / 561 处配对 / 0 失败 |
| **第二行规则**（译文 / `…` / 下一句三档） | ✅ 14588 行逐行核对 / 0 失败 |
| **换行滚动动画**（560ms 单行 / 760ms 翻译对，抄原版曲线） | ✅ 落点与静止帧逐像素一致（0/255） |
| **动画帧率**（跟随屏幕刷新率 + 1ms 时钟粒度 + 帧时间线） | ✅ 一次滚动 16 帧 → 33 帧，抖动 0.65ms |
| **设置项整数化**（seekbar 写回吸附 + 读取取整） | ✅ 34 项断言 / 两种宿主模型全过 |
| **调设置时不黑屏**（改位置/宽度走 Move+Resize，不重建窗口） | ✅ 真实任务栏上 11 项断言，连续 11 次改几何画面不断 |
| **歌词不左右横跳**（空闲区扫描排除自身 + 选择迟滞） | ✅ 端到端 20 轮位置恒定（修复前每 500ms 跳 1018px） |
| **显隐淡入淡出**（暂停淡出上移 2px / 播放淡入，300ms 抄原版 `.lyrics-layer`） | ✅ 状态机 28 项（可逆/幂等/曲线）+ 绘制位移 7 项逐像素 |
| **窗口自身的生灭也淡**（软件打开淡入 / 关闭淡出后拆窗，插件停用、切换浮动条同理） | ✅ 真机渲染线程 19 项（含 `animateAutoHide=false` 对照组；stop 从 2ms 变 303ms） |
| **逐字扫描连续滑动**（不是一次亮一个字：字内插值 + 90ms 追赶，抄 `CalculateWordScanProgress` 与 `.word-scan-smoothing`） | ✅ 数值 29 项（含原版用例 4 个断言点）+ 画布级 4 项；旧算法 0.3% 的毫秒在变 → 新算法 100% |
| 装机实测（SPW 里真实播放） | ✅ SPW 1.19.2：导入/启用/播放；加粗热更新已确认 |
| 多显示器 | ⬜ 暂只处理主任务栏 |

## 1.4.15 变更

- **封面管线**：原图按歌曲缓存、尺寸/圆角按参数缓存；换歌时同步解码一次，封面与歌词同帧切换（与 1.4.10 一致）；拖动「封面尺寸 / 圆角 / 整体缩放」只是从内存原图缩放，不再重读音频文件、重新解码。
- **暂停恢复**：恢复播放时刷新位置外推的时间戳，避免歌词先跳 2 秒再被拽回来。
- **设置轮询**：配置文件时间戳改为每 250ms 比对一次，不再每帧 stat 五个文件。
- **重启竞态**：渲染线程加 generation，`stop()` 超时后旧线程不会跟着新 `start()` 复活。
- **内存与线程**：歌词缓存 32 首 → 8 首；停用插件时关掉解析线程与封面解码线程。
- **唤醒**：帧等待改用唤醒计数，帧边界到达的唤醒不再丢失。
- **帧推送**：ARGB→BGRA 改为按 int 写入，JNA 结构体复用。
- **包体**：JNA 只保留 Windows 原生库，2.18 MB → 628 KB。
- **单测**：新增 LRC 解析 / 选行 / 逐字进度 / 粗体渲染 / 粗体热更新的单元测试。
- **加粗即时生效**：两处根因都修了——① 宿主给插件侧的 `ConfigHelper` 运行期不刷新嵌套值，`bold` 改为直接读 `typography.json`；② `registerFont` 只注册当前字面，启动时粗体会让家族表缺常规面，字体解析改为每个候选名字先按文件取精确字重、再退回家族表。现在两个方向都能即时切换（已真机确认）。

> 状态：编译通过、单元测试通过、分发包结构校验通过；JNA 裁剪版已在真机成功加载 `user32` 并枚举到任务栏。
> 已在 SPW 1.19.2 上实际播放验证（加粗热更新已确认）；发布前其余设置项建议按自检清单过一遍。

## 构建

```powershell
$env:JAVA_HOME = "你的 JDK 21 路径"
.\gradlew.bat plugin
```

产物：`build/libs/plugin-com.spwtb.spw-taskbar-lyrics-1.4.15.spmod`

首次构建从**腾讯云镜像**下载 Gradle、从**阿里云镜像**拉依赖（见 `settings.gradle.kts`），
国内网络可直接跑通。打包任务内置校验，会打开最终分发包逐项确认
Manifest 可解析、`Plugin-Class` 声明的类真的在包里、`extensions.idx` 非空、
声明有配置界面就带着 `preference_config.json`，任何一项缺失直接构建失败。

| 需要的环境 | 说明 |
| --- | --- |
| **JDK 21** | dev20 的 API 是 Java 21 字节码，**不需要 JDK 25**（那是 dev22 的要求） |
| Salt Player for Windows | 1.18.x / 1.19.x（实测 1.19.2） |

**不需要** .NET、不需要额外运行库——JNA 自带原生库；打包时只保留 Windows 的两个原生目录，`lib/` 里约 424 KB。

> 当前 1.4.15 使用 `0.1.0-dev20`（SPW 1.18.x API）/ Java 21 构建；已在 SPW 1.19.2 上实测可用。
> 官方当前推荐 dev22/JDK25，迁移计划放到 1.5.0，避免在发布前引入构建风险。

## 安装

1. Salt Player → 设置 → 创意工坊 → 模组管理 → 导入上面那个 `.spmod`（`.zip` 也收）→ 启用
2. 首次启用需要**重启播放器**
3. 之后播放带内嵌歌词的音乐，歌词会直接出现在任务栏上

## 配置项

配置界面分五组，语义与命名对齐 [TaskbarLyrics](https://github.com/ANYNC/TaskbarLyrics) 的设置体系。

**通用**

| 项 | 说明 |
| --- | --- |
| 显示位置 | 左侧 / 居中 / 右侧 |
| 无播放时隐藏 | 暂停或没有歌词时完全不占任务栏 |
| 水平 / 垂直微调 | ±400 / ±200 像素 |
| 重启任务栏歌词 / 显示当前状态 | 排查用按钮 |
| 还原默认设置 | 把五组设置全部写回默认值，立即生效且不可撤销 |

**显示**：宽度占比、固定宽度、整体缩放、第二行（不显示 / 显示下一句 / 显示翻译 / **自动（默认）**）

> 「自动」= 本句有翻译就显示译文；整首有翻译但本句是 `//`（无翻译）显示 `…`；
> 整首都没有翻译才显示下一句。这与原版 `translationMode = ShowLyricTranslation && HasTrackTranslation` 一致。

**排版**：字体、字号、加粗（切换后需重启客户端）、**笔画粗细补偿（100=不补偿，130=与原版一致）**、
文本对齐、逐字扫描、歌词偏移（±5000ms）

**外观**：文字颜色（跟随系统 / 浅色 / 深色 / 自定义）、自定义色、背景开关、
背景不透明度、背景颜色、边框、文字阴影、背景圆角

**封面**：显示开关、尺寸、与歌词间距、圆角、左/右侧

> 「还原默认设置」把五组配置全部写回默认值并重建窗口。**宿主的配置页不会自动刷新**
> （它在打开时读一次值、之后不订阅配置变更），所以还原后要关闭本页再打开才能看到新值；
> 在陈旧页面上继续调整控件，会把打开时读到的旧值一并写回。

> 以下项**不作为开关暴露**，恒为固定行为：总开关（恒开，不用时在工坊里停用插件）、
> 显示方式（固定嵌入任务栏）、显隐淡入淡出（开）、换行动画（开）、动画帧率（自动，跟随屏幕刷新率）。

「跟随系统」读的是 `HKCU\...\Themes\Personalize\SystemUsesLightTheme`，
和 Windows 的浅色/深色主题一致。

> `PluginSettings` 里的代码默认值**必须**和 `preference_config.json` 的
> `default_value` 逐项一致。`ConfigHelper.get()` 在键缺失时返回代码默认值，
> 两者不一致就会造成"界面上关着、实际开着"——`showBackground` 就踩过这个坑
> （代码里 `true`、界面上 `false`、原版也是 `false`）。

按原版源码对齐的渲染规范（透明度体系、扫描叠加补偿公式、阴影模糊、
版式常量、**字重补偿的来由与标定数据**）全部记在
**[docs/DESIGN.md](docs/DESIGN.md) 的 0.A.2 节**。
调观感前先看那一节，不要凭眼睛试参数。

字体解析是**两级**的：先查已注册家族名，查不到再去字体目录里
**按文件名加载并注册**。原因是原版用的思源黑体
（`SourceHanSansSC-Bold.otf`）在有些机器上只是文件躺在目录里、**没注册进系统**，
光靠家族名永远找不到，会静默退到 Noto Sans SC——字重差一档。

## 已实测的环境事实

以下都是本机实测，不是推测：

**宿主环境**

- SPW 版本 **1.18.5**（历史实测；1.19.2 上插件同样可用），自带 **Java 25**（jlink 裁剪）
- `app/Salt Player for Windows.cfg` 里显式开放了原生调用与 AWT 内部包：
  ```
  --enable-native-access=ALL-UNNAMED
  --add-opens=java.desktop/sun.awt=ALL-UNNAMED
  --add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED
  ```
  且**没有** `-Djava.awt.headless=true` → 插件内建窗口是被预期的路径
- 宿主 JVM **没有 `jdk.httpserver`**、`java.net.http`、`jdk.zipfs`、`jdk.charsets`
  → 不能用 JDK 自带 HTTP 服务器、不能解压、不能解 GBK

**任务栏几何**（Windows 11 22H2，2560×1440，缩放 150%）

`Shell_TrayWnd` 是**顶层**窗口，(0,1368)-(2560,1440)，物理高 72px（= 48 逻辑px）。
它的直接子窗口决定哪里空闲：

| 子窗口 | rect | 是否算占用 |
| --- | --- | --- |
| TrafficMonitor (`#32770`) | (2,1380)-(558,1428) | ✅ |
| **居中图标区** `ReBarWindow32` | (1116,1368)-(1578,1440) | ✅ |
| 右侧托盘 `TrayNotifyWnd` | (2017,1368)-(2560,1440) | ✅ |
| `Windows.UI.Composition.DesktopWindowContentBridge` | 全宽 2560×72 | ❌ **必须排除** |

排除最后一项是关键：它覆盖整条任务栏，算作占用就永远找不到空隙。
本机算出两段空闲：`x=558 宽=558` 与 `x=1578 宽=439`。

**渲染栈**

在与宿主完全一致的 18 个模块下（`--limit-modules`），用插件真实渲染类跑通 333 帧：
窗口建成、父窗口是任务栏、z 序第 0 位（压住 XAML 合成层）、
从 `六月飞霜.flac` 解出 51×51 内嵌封面、`PrintWindow` 回读确认像素进入窗口。

**音乐库**（`tools/scan-music-library.py`）

298 个文件：**FLAC 294 + MP3 4**，**内嵌歌词覆盖率 100%**，
294/294 FLAC 都带 PICTURE 封面块，同目录 `.lrc` **0 个**。
但**没有任何字级时间标签**（`<mm:ss.xx>` 出现 0 次）——逐字高亮由按字数均分的合成器产生。

## 目录

```
spw-taskbar-lyrics/
├── LICENSE                             # 本插件 MIT 许可证
├── THIRD-PARTY-NOTICES.md              # TaskbarLyrics / JNA 等第三方声明
├── docs/DESIGN.md                      # 设计方案（含版本现实、渲染规范与实测证据）
├── tools/
│   ├── probe/TbEnum.java               # 任务栏结构探测器
│   ├── probe/TbDemo.java               # 分层窗口渲染验证
│   ├── probe/RenderProbe.java          # 渲染栈端到端自检（用插件真实类）
│   ├── probe/CaptureProbe.java         # 抓取任务栏上歌词窗口的真实像素
│   ├── probe/FrameProbe.java           # 离屏渲染一帧，可指定歌词文本与时间点
│   ├── probe/InkProbe.java             # 逐行量文字（Java 侧），墨密度/直方图/笔画宽度
│   ├── probe/ZoomCompare.java          # 裁出文字区放大并排（只为人眼确认）
│   ├── probe/glyph-stats.py            # 归一墨量 + 笔画游程（与透明度无关的字重指标）
│   ├── probe/ref.html                  # 复刻原版 CSS，交给 Edge 无头渲染出字重基准
│   ├── probe/GammaProbe.java           # 标定 gamma 补偿系数
│   ├── probe/MatchLine.java            # 程序化认出原版窗口显示的是哪一句歌词
│   ├── probe/FontProbe2.java           # 列举字体家族与各字面的墨量
│   ├── probe/BilingualProbe.java       # 双语歌词配对与第二行规则的全库机械校验
│   ├── probe/scan-bilingual.py         # 统计库里的双语歌词写法与占位符
│   ├── probe/scan-bilingual2.py        # 同上，细查组大小分布
│   ├── probe/scan-bilingual3.py        # 确认「原文在前、译文在后」的配对顺序
│   ├── probe/lyric-line.py             # 按播放位置定位内嵌歌词的当前行
│   ├── probe/smtc-position.ps1         # 只输出当前播放位置（毫秒）
│   ├── scan-music-library.py           # 音乐库格式与内嵌歌词覆盖率统计
│   └── probe-smtc.ps1                  # SMTC 会话探测
├── src/main/
│   ├── java/com/spwtb/taskbarlyrics/render/
│   │   ├── Win32.java                  # JNA 声明 + 任务栏几何 + 注册表明暗主题
│   │   └── TaskbarLayerWindow.java     # 逐像素透明分层窗口
│   ├── kotlin/com/spwtb/taskbarlyrics/
│   │   ├── TaskbarLyricsPlugin.kt      # 主类，生命周期
│   │   ├── PluginRuntime.kt            # 运行期状态、设置缓存
│   │   ├── config/PluginSettings.kt    # 五组配置读取
│   │   ├── playback/SpwPlaybackExtension.kt
│   │   ├── lyrics/LyricsStore.kt       # 歌词状态中心 + 位置外推
│   │   ├── render/LyricLineSelector.kt # 选行：主行 + 第二行（可被探针直接调用）
│   │   ├── lyrics/LyricsSource.kt      # 容器解析 + LRC 解析 + 逐字合成
│   │   ├── lyrics/CoverArt.kt          # 内嵌封面提取 + 缩放缓存
│   │   ├── lyrics/LyricModels.kt
│   │   ├── render/RenderSettings.kt    # 设置快照
│   │   ├── render/LyricsPainter.kt     # Java2D 绘制 + 字形掩膜缓存 + gamma 补偿
│   │   ├── render/FontLoader.kt        # 按文件加载并注册字体（思源黑体没注册进系统）
│   │   ├── render/TaskbarRenderer.kt   # 渲染线程与窗口生命周期
│   │   └── actions/PluginActions.kt
│   └── resources/preference_config.json
```

> Win32 那层用 Java 而不是 Kotlin：JNA 的 `Structure` 依赖**公开字段反射**，
> Kotlin 属性默认生成私有字段 + getter，得逐个加 `@JvmField` 才能工作。
> 这一层移植自实测通过的探针代码，保持原样最稳。

## 已知限制

1. **只处理主任务栏**。副屏的 `Shell_SecondaryTrayWnd` 能枚举到，但还没画上去。
2. **假定任务栏在屏幕底部**。任务栏移到顶部或左右两侧时几何算法需要另做。
3. **没有频谱**。TaskbarLyrics 的频谱靠 WASAPI 回环捕获系统输出，
   Java 侧没有对应的能力，这一项不打算做。
4. **没有全局快捷键**。当前版本未实现；1.19.x 的插件 API 已提供 `keyBindingManager`（需 `key-bindings` 权限），可作为后续功能。
5. **逐字高亮是合成的**。你的内嵌歌词没有字级时间，只能按字数均分估算，
   节奏快的段落会有轻微不同步；整行高亮与逐行滚动是准确的。
6. **Explorer 重启后**窗口会失效，渲染线程会检测到并自动重建。
7. **换歌时封面是同步解码的**：大封面可能让换歌那一帧停顿几十毫秒（和 1.4.10 一致，换来的是封面与歌词同帧切换）。原图解码过一次后就只剩缩放，拖动尺寸/圆角/缩放不会再读盘。

## 许可

本插件采用 MIT 许可证，见 [LICENSE](LICENSE)。

### 参考与致谢

本插件的任务栏嵌入方案、设置体系与歌词版式/视觉参数参考并复刻自
[ANYNC/TaskbarLyrics](https://github.com/ANYNC/TaskbarLyrics)（MIT，Copyright (c) 2026 anync）。
本项目使用 Kotlin/Java + JNA 实现，原版使用 C#/WPF + Chromium；实现语言与架构不同，
但行为、常量与版式对齐原版。

第三方依赖与完整许可文本见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
