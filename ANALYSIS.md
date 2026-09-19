# mordant 渲染管线分析

本文跟踪版本为仓库当前快照（`VERSION_NAME=3.1.0`）。所有行号对应当前工作树源码。

- 跟踪样本：`Panel(Text("hi"), title = Text("t"))`，终端 `Terminal(ansiLevel = AnsiLevel.NONE, width = 20, height = 24, terminalInterface = TerminalRecorder(...))`。
- 选它是因为它一次性经过 `Panel → Padded → Text`、`HorizontalRule(title)`、`maxWidthRange`、`Lines.setSize`、边框 Span 拼装和 ANSI 渲染全链路；`Panel` 是最简的“会二次 measure、会把宽度切给子 widget”的容器。

## 1. 调用链：`Terminal.print(widget)` 到 `TerminalInterface` 的每一跳

### 1.1 入口与宽度来源

1. `Terminal.print(Widget, stderr)` — `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/terminal/Terminal.kt:182-184`
   - 入参：`Widget`；做的事：`rawPrint(render(widget), stderr)`。
   - 出参：无，进入下一跳。
   - `Terminal.size` 在构造时由 `terminalInterface.detectSize(...)` 求出（`Terminal.kt:79-88`；实现见 `terminal/TerminalDetection.kt:211-228`）：显式 `width` 优先；非交互终端回退到 `nonInteractiveWidth ?: width ?: 79`。本例显式 20，所以 `t.size = Size(20, 24)`，`Widget.render` 的默认宽度参数就是 20（`rendering/Widget.kt:7`）。

2. `Terminal.render(widget)` — `Terminal.kt:230-232`
   - 入参：`Widget`；调用 `widget.render(this)` 得到 `Lines`，再交给 `renderLinesAnsi(lines, terminalInfo.ansiLevel, terminalInfo.ansiHyperLinks)`。
   - 数据结构：`Widget → Lines → String`。

3. `Panel.render(t, width)` — `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Panel.kt:140-198`
   - 入参：宽度 20。第一件事就是再调一次 `measure(t, width)`（`Panel.kt:141`）——measure 与 render 之间没有缓存，测量是 render 的子步骤。

### 1.2 宽度测量（measure 与 `maxWidthRange` 聚合）

4. `Panel.measure(t, 20)` — `Panel.kt:128-138`
   - `maxContentWidth(20) = 20 - borderWidth(2) = 18`（`Panel.kt:121-123`）。
   - 内容先被 `content.withPadding(padding)` 包成 `Padded`（`Panel.kt:120`；默认 `DEFAULT_PADDING = Padding(0)`，`Padded.get` 对空 padding 直接返回原 widget，见 `widgets/Padding.kt:91-93`），所以 `content.measure(t, 18)` 实际落到 `Text.measure`。
   - 标题：`titlePadding = 1`（主题键 `panel.title.padding`，`rendering/Theme.kt:98`），`maxTitleWidth = 18 - 1*2 = 16`，标题的 range 再 `+ borderWidth + titlePadding*2 = +4`（`Panel.kt:131-132`）。
   - 聚合用的是 `listOf(...).maxWidthRange { it }`，即 `rendering/WidthRange.kt:28-40`：对所有非 null range **逐字段取 max**（`min` 取所有 min 的最大、`max` 取所有 max 的最大），不是相加。
   - 样本实测值：
     - `Text("hi").measure(t, 18) = WidthRange(2, 2)`（`widgets/Text.kt:50-61`：`PRE` 不换行，`wrap()` 后一行 `"hi"`，min=max=行宽 2）。
     - 内容含边框：`WidthRange(4, 4)`。
     - 标题 `Text("t", NOWRAP, ELLIPSES).measure(t, 16) = WidthRange(1, 1)`，加边框与内边距后 `WidthRange(5, 5)`。
     - `maxWidthRange` 聚合 → **`Panel.measure(t, 20) = WidthRange(min=5, max=5)`**（实测确认）。
   - 若 `expand=true`，`Panel.kt:135` 把内容 range 改写成 `min = max`，即“至少占满全部可用宽度”。

5. `Text.measure` 内部 — `widgets/Text.kt:50-61`
   - 用 `NONE` 对齐、`OverflowWrap.NORMAL` 调一次 `wrap`（即“测量时不做对齐填充、不做截断/折行”，`Text.kt:52`）；`max` 是 wrap 后最长行的单元格宽之和；`min` 在可换行 whitespace 下取最长**单词**宽（`Text.kt:54-58`），这就是 `WidthRange` “min=不截断所需、max=给足空间会用”的语义（`rendering/WidthRange.kt:5-8`）。

6. `WidthRange` 的聚合算子 — `rendering/WidthRange.kt:14-18`
   - `+ Int` / `+ WidthRange`：平移与相加（padding、边框宽度走这里）；`/ divisor`：跨列单元格按列数均摊（`table/Table.kt:151-153`）。
   - 表的列聚合是另一套：逐列 `measureColumn` 后 `sumOf { it.min/max } + borderWidth`（`table/Table.kt:111-118`），再按 `priority` 在 `calculateColumnWidths` 里分可用宽度（`table/Table.kt:165-213`）。

### 1.3 布局求解：把宽度分下去，`Lines.setSize` 的位置

7. 回到 `Panel.render` — `Panel.kt:145-168`
   - 非 expand 时 `contentWidth = (measurement.max - borderWidth).coerceAtMost(maxContentWidth) = 3`（`Panel.kt:145-148`）——这就是容器“把宽度分给子 widget”的决定值：shrink-to-fit 用测量出的 max，expand 用全部可用宽度。
   - 注意渲染宽度与分配宽度是两个数：`content.render(t, maxContentWidth=18)` 先按**可用**宽度 18 渲染，再 `.setSize(contentWidth=3, textAlign = LEFT)`（`Panel.kt:150-151`）。

8. `content.render(t, 18)` → `Text.render` — `widgets/Text.kt:63-65, 67-188`
   - `Text.render` 直接调 `wrap(this.width ?: width, ...)`。`wrap` 把构造时 `parseText` 得到的 `Lines`（`Text.kt:29`；`internal/Parsing.kt:20-25`）逐 span 重新折行：处理 NEL/LS 硬换行（`Text.kt:104-108`）、空格折叠（129）、Tab 展开（132-135）、按 `whitespace.wrap` 换行（142-146）、按 `overflowWrap` 处理超长单词（149-172）。
   - 样本 `"hi"`、`PRE`、宽 18：一行一个 `Span("hi")`，`Lines(height=1, width=2)`，1 个 span。

9. `Lines.setSize(newWidth=3, ..., textAlign=LEFT)` — `rendering/Lines.kt:77-114`，行级逻辑在 `resizeLine`（`Lines.kt:116-197`）
   - 职责：把每行**补白或裁剪**到精确宽度、按垂直对齐增删行。宽度计量全部走 `span.cellWidth`（终端单元格，`Lines.kt:51-52`）。
   - 太宽：整 span 跳过（`Lines.kt:149-152`），跨界 span 用 `Span.take/drop` 切开（`Lines.kt:140, 158`）；太短：按对齐补空格（`Lines.kt:186-196`），`NONE` 对齐的补白是无样式空格（`Lines.kt:189`），其他对齐继承首尾样式。
   - 样本：`"hi"` 宽 2 < 3，`LEFT` → 追加 1 个空格 span：`[Span("hi"), Span(" ")]`，行宽精确 3。
   - 它在管线中的位置是“**子内容 render 之后、外框拼装之前**”的尺寸归一器：`Panel`（`Panel.kt:151`）、`HorizontalRule`（`widgets/HorizontalRule.kt:87`）、`Table` 单元格（`table/Table.kt:405-408`）、`VerticalLayout`（`table/VerticalLayout.kt:74-77`）都在同一个相对位置调用它。表格的顺序尤其关键：`render(t, w).withStyle(style).setSize(w, h, vAlign, hAlign)`（`table/Table.kt:405-408`）——先合并样式再定尺寸。

10. 标题/底边 — `Panel.kt:152-168`
    - 用 `HorizontalRule(title ?: EmptyWidget, ThemeString.Explicit(borderType.body.ew), borderStyle, align, titlePadding, titleOverflowTop)` 各渲染一条。样本上边 `title=Text("t")`，下边 `EmptyWidget`。
    - `HorizontalRule.render(t, contentWidth=3)`（`widgets/HorizontalRule.kt:61-101`）：`padding=1`、`minBarWidth=4+2=6`，标题按 `(3-6).coerceAtLeast(0)=0` 宽渲染（`HorizontalRule.kt:64-66`）——`Text("t")` 在宽 0、`NOWRAP` 下不折行，仍产出 1 行 1 个宽 1 的 span；`ruleWidth = 3 - 标题行宽1 - totalPadding2 = 0`，居中时左右各 0；`rule(t,0)` 返回空行（`HorizontalRule.kt:103-104`）；`flatLine(左空行, 空格span, "t", 空格span, 右空行)` 得到 `[" ", "t", " "]` 三 span、宽 3（`HorizontalRule.kt:83-84`）。
    - 底边标题为空 → `renderedTitle.isEmpty()` → 一条满宽 rule：`rule(t, 3)` 产出 `Span("───")`（解析后单 span，`HorizontalRule.kt:103-118`）。

11. 边框拼装 — `Panel.kt:178-197`
    - 垂直边框 `b.ns="│"`、角 `b.es="╮"` 等各成一个 `Span.word(..., borderStyle)`（`Panel.kt:180-181`），`flatLine`（`rendering/Lines.kt:55-69`）把 border span 和内容行 flatten 成一条 `Line`。样本最终 3 行（实测）：
      - 行 0（上边）：5 span，宽 `[1,1,1,1,1]`，文本 `╭ | t | ╮`（`╭`、空格、`t`、空格、`╮`）。
      - 行 1（内容）：4 span，宽 `[1,2,1,1]`，文本 `│ | hi | (空格) | │`——`hi` 是一个宽 2 的 span，左侧补的空格是同 span 还是独立取决于 `setSize`，此处为独立无样式空格。
      - 行 2（下边）：3 span，宽 `[1,3,1]`，文本 `╰ | ─── | ╯`。
    - 出参：`Lines(height=3, width=5)`。终端显示：
      ```
      ╭ t ╮
      │hi │
      ╰───╯
      ```

### 1.4 `Lines` / `Line` / `Span` 的生成与 `Lines.width`

- `Span`（`rendering/Span.kt:14-47`）：不可变，`word` 工厂强制“非空、不含换行/CSI、要么全空白要么无空白”（`Span.kt:21-28`）；`cellWidth` 惰性按 `stringCellWidth` 计算（`Span.kt:39`）。
- `Line`（`rendering/Lines.kt:9-11`）：span 列表 + `endStyle`（行末之后仍“活跃”的样式，用于 ANSI 重开标签和补白着色）。
- `Lines`（`rendering/Lines.kt:20-49`）：`height = lines.size`，`width = 每行 span 单元格宽之和的最大值`（`Lines.kt:23-24, 51-52`）。生成点有三处：`parseText` 把字符串解析成 `Lines`（`internal/Parsing.kt:20-105`）；`Text.wrap` 重排（`widgets/Text.kt:187`）；容器用 `flatLine`/`buildList` 现拼（`Panel.kt:171-197`、`HorizontalRule.kt:85-100`）。

### 1.5 样式合并、ANSI 能力降级与最后的输出/拦截器

12. `renderLinesAnsi(lines, level, hyperlinks)` — `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/internal/AnsiRender.kt:18-34`
    - 逐行、逐 span：先对每个 `span.style` 调 `downsample(style, level, hyperlinks)`（`AnsiRender.kt:27`），再 `makeTag(activeStyle, newStyle)` 生成与上一个有效样式之间的**差异** CSI/OSC 序列（`AnsiRender.kt:28`），最后在行尾发一个回到 `DEFAULT_STYLE` 的收尾标签（`AnsiRender.kt:32`）。相邻相同样式不产生任何转义（`makeTag` 首行 `old == new → ""`，`AnsiRender.kt:91`）。
    - 入参 `Lines`，出参 `String`（行间用 `\n`，行尾无多余换行，与 `Lines` “无尾换行”约定一致，`rendering/Lines.kt:18`）。
    - 本例 `AnsiLevel.NONE`：`downsample` 对任何非默认样式都返回 `DEFAULT_STYLE`（`AnsiRender.kt:59-60`），整串无转义，输出就是上面 3 行纯文本。
    - 数据结构：`Lines → 每 span 一对 (TextStyle, 文本) → String`。

13. 颜色降级 `downsample` — `AnsiRender.kt:58-87`
    - `NONE`：全丢；`ANSI16`：颜色一律 `toSRGB().clamp().toAnsi16()`，已是 `Ansi16` 则原样（`AnsiRender.kt:61-66`）；`ANSI256`：16/256 色原样、其余转 256（68-77）；`TRUECOLOR`：原样，仅在超链接不被支持时剥掉 OSC 8（79-85）。粗体/斜体等布尔属性与颜色空间无关，从不降级。

14. 回到 `Terminal.print`：`rawPrint` — `Terminal.kt:249-251`
    - 把渲染出的 `String` 包成 `PrintRequest(text, trailingLinebreak=false, stderr=false)`（`terminal/TerminalInterface.kt:79-90`），交给 `sendPrintRequest`。

15. `sendPrintRequest` — `Terminal.kt:272-275`
    - 若 `terminalInterface.shouldAutoUpdateSize()` 则先 `updateSize()` 重新探测终端尺寸（JVM 默认 true，`terminal/TerminalInterface.kt:76`），再调平台实现 `sendInterceptedPrintRequest`。

16. JVM 拦截器折叠与真正写出 — `mordant/src/jvmMain/kotlin/com/github/ajalt/mordant/internal/MppInternal.jvm.kt:114-124`
    - 在 `printRequestLock` 监视器内执行：`interceptors.fold(request) { acc, it -> it.intercept(acc) }`，按注册顺序逐个把 `PrintRequest` 喂给 `TerminalInterceptor.intercept`（`terminal/TerminalInterceptor.kt:3-4`），最后 `terminalInterface.completePrintRequest(result)`。**这一跳就是“真正把字符串交给 TerminalInterface”的一跳**：入参/出参都是 `PrintRequest`。
    - 默认 `StandardTerminalInterface.completePrintRequest`（`terminal/StandardTerminalInterface.kt:27-40`）：按 `stderr`/`trailingLinebreak` 分流到 `printStderr` / `println(request.text)` / `print(request.text)`（JVM 平台函数最终写 stdout/stderr）。
    - 测试用 `TerminalRecorder.completePrintRequest`（`terminal/TerminalRecorder.kt:76-84`）：把 text 追加进 `stdout`/`stderr`/`output` 三个 `StringBuilder`，`trailingLinebreak` 时补 `\n`。`print(Widget)` 不带尾换行，所以 recorder 里正好是 3 行、行间 `\n`、行尾无 `\n`。

旁路：`TextStyle.invoke(text)`（`rendering/TextStyle.kt:54` → `AnsiRender.kt:38-56`）不经过 `Terminal`：它用 `ANSI_RE` 对字符串内已有的转义序列做状态机式更新（`updateStyle`，`internal/Parsing.kt:113-247`），自己 `makeTag` 开关标签，**用的是接收者样式所属的隐式能力——不做 `downsample`**。`TextColors.red("x")` 之类产出的就是带 ANSI 的原始字符串；`rendering/TextColors.kt:14-18` 的文档明确警告这些样式“not automatically downsampled”，只有再走 `Terminal.print/render`（`Terminal.kt:222-226` 会把非 Widget 包成 `Text`，`parseText` 会把已有 ANSI 解析回 `TextStyle`，再随 `renderLinesAnsi` 降级）才会按终端能力收敛。

## 2. 不变量清单

### 2.1 measure 与 render 的宽度契约

- **维持位置**：`rendering/Widget.kt:5-8` 是契约声明；`Text.measure/render` 共用同一个私有 `wrap`（`widgets/Text.kt:50-65`）；容器在 `Panel.kt:141-151`、`table/Table.kt:111-118, 161-213` 先 measure 后按结果 render。
- **契约内容**：对同一 widget、同一传入宽度 W，render 结果应满足 `WidthRange(measure(t,W).min).min ≤ 每行单元格宽 ≤ W`，并且当可用宽度 ≥ measure.max 时不出现因折行/截断产生的内容损失。
- **可见反例（契约被破坏时的样子）**：`Text` 构造参数 `overflowWrap = TRUNCATE/ELLIPSES/BREAK_WORD` 配合 `whitespace.wrap = true` 会打破它——measure 特意用 `OverflowWrap.NORMAL` 量（`Text.kt:52`），而 render 用构造时的设置。实测 `Text("日本語", whitespace = PRE_WRAP, overflowWrap = TRUNCATE)` 在 W=3 时 `measure=WidthRange(2,2)`（最长单词“日本語”宽 6，min 应为 6；这里 min 也没反映真实单词宽，因为截断后只按未截断行量），render 却给出一行宽 **6** 的“日本語”，超过 W 一倍。放进 Panel 能直接看到边框错位：
  ```
  ╭─╮
  │日│
  ╰─╯
  ```
  右边框整行消失。`Text(width = ...)` 构造参数也会让“同一个 W”的关系失效：measure/render 内部都用 `this.width ?: width`（`Text.kt:52, 64`），调用方传什么宽度都被忽略；`Panel(title=...)` 把标题构造成 `Text(it, overflowWrap = ELLIPSES, whitespace = NOWRAP)`（`Panel.kt:109-110`），标题走的就是这条旁路，其 measure 与正文 measure 的折行规则天然不同。

### 2.2 样式合并的优先级方向（四个算子方向不一致）

- **`TextStyle.plus`：右操作数赢（`this` 是底，`other` 盖在上面）**。`rendering/TextStyle.kt:70-87`：每个属性 `other.x ?: this.x`，`other` 显式非 null 才覆盖；`DEFAULT_STYLE` 是恒等元（`TextStyle.kt:72-73`）。例子：`(red + bold) + blue` → 蓝。
- **`Span.withStyle` / `Lines.withStyle`：参数赢（叠加层在上）**。`Span.withStyle` = `Span(text, this.style + style)`（`rendering/Span.kt:46`）；`Lines.withStyle` 对每个 span 与 `endStyle` 同样 `+ style`（`rendering/Lines.kt:31-38`）。即“容器/后加的样式盖内容自带样式，但只覆盖非 null 属性”。反例：单元格里红字加表格蓝前景，若方向反了就会看到字是红的而不是蓝的。
- **`Lines.replaceStyle`：参数无条件赢（替换，不是叠加）**。`rendering/Lines.kt:43-48` → `Span.replaceStyle`（`Span.kt:47`）直接 `Span(text, style)`，连 `other` 为 null 的属性也丢掉。它与 `withStyle` 的可见差别：`(bold).replaceStyle(red)` 产出的字**不是粗体**；`(bold).withStyle(red)` 仍然粗体。
- **`foldStyles(vararg)`：参数表中靠前的赢（外层赢）**。`rendering/TextStyle.kt:143-153` 的关键是它做的是 `s + style`（新遍历到的 `s` 在左、累加值在右），与“直觉上后者盖前者”相反：先入参的 `s` 作为底被后入参的 null 属性保留……最终效果是**第一个非 null 样式对任一属性拍板**（后面的只能填它没设的属性）。
- **TableLayout 的折叠顺序与赢家**：`table/TableLayout.kt:113-121` 依次传 `cell.style, row.style, stripedStyle, sectionCol.style, tableCol.style, section.style, table.style`。按 `foldStyles` 的方向，冲突时 **cell（单元格）> row（行）> 条纹 rowStyles > 节级列 > 表级列 > section（节）> table（整表）**。随后单元格内容在 `table/Table.kt:254` 与 `table/Table.kt:405` 用 `.withStyle(cell.style)` 应用——内容自带样式仍在最里层，被折叠出的单元格样式按非 null 属性覆盖。可见反例：给整表设红前景、单元格设蓝前景，若折叠顺序写反，字会变红而不是蓝。

### 2.3 颜色降级的层级与幂等性

- **发生层级**：降级只在 `Lines → String` 的最后一跳 `renderLinesAnsi → downsample` 发生（`internal/AnsiRender.kt:27, 58-87`），measure、布局、Span 存储全程都保留完整 `RGB`/超链接等能力信息；`makeTag` 只负责把（已降级的）样式差编码成 CSI/OSC（`AnsiRender.kt:90-136`），自身不降级。
- **必须幂等**：把已降级输出再喂回终端（`Terminal.render("…已带 ANSI…")` → `Text` → `parseText` 用 `updateStyle` 把 CSI 解析回 `TextStyle`，`internal/Parsing.kt:113-247` → 再次 `renderLinesAnsi`）结果必须不变。幂等的根据是降级映射把颜色收敛到该能力级别的**不动点**：ANSI16 下 `Ansi16` 输入原样返回（`AnsiRender.kt:62` 的 `if (it is Ansi16) it`），ANSI256 下 16/256 色原样（`AnsiRender.kt:69-73`），TRUECOLOR 恒等。实测 `AnsiLevel.ANSI16` 下 `TextColors.rgb("#123456")("x")` 连渲两次字符串完全相等（`ESC[30mxESC[39m`）。若不幂等（比如每次都把已有 16 色再量化一次），二次渲染会出现颜色跳变/多出复位码。
- **`TextStyle.invoke(text)` 旁路的关系**：它在降级层**之外**（`AnsiRender.kt:38-56`），只按字符串内现有 ANSI 维护开关状态，不查 `AnsiLevel`；所以手工 `print(TextColors.rgb(...).invoke("x"))` 在 NONE 终端会把真彩色/粗体码原样打出去，看到的是字面 `ESC[38;2;…m` 或乱码。这也是“降级必须集中在最后一跳”的原因：`invoke` 产生的串一旦再进入 `Terminal.render`，`parseText` 把它解析回样式对象后仍会被同一个 `downsample` 收敛到不动点。

### 2.4 宽度计量的三个单位：码点 / UTF-16 字符 / 终端单元格

- `cellWidth(codepoint: Int)`（`internal/cellwidth.kt:22-44`）：按 **Unicode 码点**查表，返回终端**单元格**数（ASCII 快路径 1；BS/DEL 返回 -1；表外默认 1）。
- `stringCellWidth(string)`（`internal/cellwidth.kt:47-81`）：用 `codepointSequence` 逐**码点**（JVM 上即 codePoint，非 UTF-16 索引）求和，并特殊处理 ZWJ emoji 序列合并为一个 2 宽字形（`cellwidth.kt:53-77`）。单位是终端单元格。
- `Span.cellWidth`（`rendering/Span.kt:39`）：`stringCellWidth(text)` 的惰性缓存，单元格。
- `Span.take(n)` / `Span.drop(n)`（`rendering/Span.kt:40-41`）：直接 `text.take(n)`/`text.drop(n)`，是 **Kotlin String 的 UTF-16 字符（code unit）** 切分，既不是码点也不是单元格。而它的调用方 `Lines.resizeLine`（`rendering/Lines.kt:140, 158`）传的 `n` 是单元格宽度差。两者单位只在 BMP 且 East Asian Width 为 W/F 的字符以外才“碰巧一致”。
- `Lines.setSize`（`rendering/Lines.kt:77-197`）：循环与累计全部按 `span.cellWidth`（**单元格**）做判断（如 `Lines.kt:132, 139, 149`），但真正下刀时调用的是 UTF-16 版 `take/drop`——这是不变量的接缝，也是风险点 1/2 的根因。
- 换行/截断/对齐：`Text.wrap` 判断“装不下”用单元格宽（`widgets/Text.kt:141-149`），但 TRUNCATE 用 `span.text.take(wrapWidth)`（UTF-16，`Text.kt:155`）、ELLIPSES 用 `take(wrapWidth-1) + "…"`（`Text.kt:159`）、BREAK_WORD 用 `text.chunked(wrapWidth)`（UTF-16，`Text.kt:163`）；对齐补白数量 `extraWidth` 来自单元格宽差（`Text.kt:204, 216-228`），所以补白本身总是对的，被截的内容会错。
- **可见反例**：宽 1 的可换行区域里放一个“日”（宽 2），TRUNCATE 下 `take(1)` 仍给出宽 2 的“日”，行溢出；emoji（代理对，2 个 UTF-16 字符）在宽 3 裁到宽 1 时，`drop(2).take(1)` 切出半个代理对，终端里是 `�`。

### 2.5 主题查找的回退顺序

- **`ThemeStyle.of / ThemeString.of / ThemeDimension.of`（含 `ThemeFlag.of`）**：`internal/ThemeValue.kt:9-14, 31-36, 75-80`。两级选择：
  1. 构造 widget 时显式参数非 null → `Explicit`，取值时无视主题，直接回显（`ThemeValue.kt:24-26, 46-48, 90-92`）；
  2. 显式参数为 null → `Default(key, default)`，取值时 `theme.style/string/dimension(key, default)`（`ThemeValue.kt:20-22, 42-44, 86-88`）。
- **再接 `Theme.style(key, default)`**（`rendering/Theme.kt:140-142`，string/dimension 同构于 `Theme.kt:154, 160-162`）：
  3. 主题 map 中该键存在 → 用主题值（`getOrElse` 的左值）；
  4. 不存在 → 用 `of(...)` 里写死的代码默认值（`DEFAULT_STYLE`/`""`/`0`，调用点如 `Panel.kt:80-81`、`HorizontalRule.kt:34-37`）。
- 所以完整回退链是 **显式构造参数 → 主题命名键 → 代码内 default**，共 3 级（`ThemeStyle.of` 的 `default: TextStyle = DEFAULT_STYLE` 让“主题缺失”和“默认空样式”在 Panel 这类调用里不可区分）。`Theme(Plain) { ... }` 是另一条横向合成路径：map 级覆盖（`rendering/Theme.kt:189-196` 复制父主题四个 map 再改）。
- **`Theme.Plain`**（`rendering/Theme.kt:105-109`）：styles 用 `emptyMap()`——落在上面第 3 级“键不存在”，于是所有 `Theme.style(...)` 回退到第 4 级代码 default；但 strings/flags/dimensions 直接继承 `Default`，即字符串/尺寸类查找停在第 3 级（键存在）。唯一被改写的是 `progressbar.pulse = false`。
- **`Theme.PlainAscii`**（`rendering/Theme.kt:112-130`）：在 `Plain` 基础上只覆盖 strings（如 `list.bullet.text` 由 `•` 变 `*`、`hr.rule` 不变仍为 `─`）与 `markdown.table.ascii = true`。它同样落在第 3 级（键存在但值是 ASCII），styles 依旧空 map 落第 4 级。
- **可见反例**：`Panel(Text("x"), borderStyle = TextColors.red)` 即使配 `Theme.Plain` 边框仍是红的（第 1 级 Explicit 赢）；不传 borderStyle 时 `Theme.Default` 下 `panel.border` 虽在 map 中但值就是 `DEFAULT_STYLE`（`Theme.kt:31`），与 Plain 下落第 4 级在视觉上恰好一致——真正能看出 Plain/Default 差别的是 `success`/`danger` 这类有颜色的键。

## 3. 三个风险点（分属三个文件，均有可运行复现）

### 风险 1：`Span.take/drop` 按 UTF-16 切，被按单元格宽度调用，会切裂代理对

- **位置**：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Span.kt:40-41`（刀本身），触发点 `rendering/Lines.kt:140` 与 `rendering/Lines.kt:158`（`resizeLine` 用单元格宽差去调它）。
- **为什么有风险**：`Lines.setSize` 的宽度记账是单元格（`Span.cellWidth`，`Span.kt:39`），`n` 可能是 1、3 这种奇数；对辅助平面字符（emoji 等，一个字 = 2 个 UTF-16 code unit = 2 格），`text.take(1)` 会返回孤立高代理。孤立代理在 JVM 写出时变成替换字符 `�`（宽 1 格），于是“裁到 N 格”既丢了字形又算错宽度。
- **最小复现**：
  ```kotlin
  import com.github.ajalt.mordant.rendering.AnsiLevel
  import com.github.ajalt.mordant.terminal.Terminal
  import com.github.ajalt.mordant.terminal.TerminalRecorder
  import com.github.ajalt.mordant.widgets.Panel
  import com.github.ajalt.mordant.widgets.Text

  fun main() {
      val rec = TerminalRecorder(width = 3, height = 24, ansiLevel = AnsiLevel.NONE)
      val t = Terminal(ansiLevel = AnsiLevel.NONE, width = 3, height = 24, terminalInterface = rec)
      t.print(Panel(Text("😀😀"), borderType = null)) // 内容 4 格，可用 3 格，触发裁剪
      print(rec.output())
  }
  ```
- **实测实际输出**：码点序列为 `[128512, 55357]`，即 `😀` 后跟一个孤立高代理（JVM stdout 上显示为 `😀�`），长度 3 个 UTF-16 字符。
- **应有输出**：不跨字形切割，宽 3 的行要么保留第一个 emoji 再补 1 个空格（`😀 `），要么整体不裁。任何情况下都不应出现孤立代理/`�`。

### 风险 2：`Text.wrap` 的 overflow 处理用 UTF-16 计数，BREAK_WORD 会整块重复，TRUNCATE 对宽字符失效

- **位置**：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Text.kt:162-170`（BREAK_WORD 分支；TRUNCATE/ELLIPSES 在 `Text.kt:154-160`）。
- **为什么有风险**：进入该分支的前提 `cellWidth > wrapWidth` 用的是单元格宽（`Text.kt:149`），分支内却用 `span.text.chunked(wrapWidth)`（UTF-16 字符数）切块。两个问题：
  1. 当 `chunked` 恰好切出长度等于 `wrapWidth` 的最后一块时，它被作为完整行 `lines += ...` 发出（`Text.kt:163-166`），循环结束后同一个 `it` 又被赋回 `span` 并在循环外再次 `line.add(span)`（`Text.kt:167, 174-175`）——整块内容输出两遍。
  2. 对东亚宽字符，UTF-16 长度 = 字数 ≠ 单元格宽，TRUNCATE `take(wrapWidth)` 截完仍是两行宽，容器边框直接错位。
- **最小复现**（纯 ASCII 即可触发重复，宽度数字全部可断言）：
  ```kotlin
  import com.github.ajalt.mordant.rendering.AnsiLevel
  import com.github.ajalt.mordant.rendering.OverflowWrap
  import com.github.ajalt.mordant.rendering.Whitespace
  import com.github.ajalt.mordant.terminal.Terminal
  import com.github.ajalt.mordant.terminal.TerminalRecorder
  import com.github.ajalt.mordant.widgets.Text

  fun main() {
      val rec = TerminalRecorder(width = 3, height = 24, ansiLevel = AnsiLevel.NONE)
      val t = Terminal(ansiLevel = AnsiLevel.NONE, width = 3, height = 24, terminalInterface = rec)
      t.print(Text("abcdef", whitespace = Whitespace.PRE_WRAP, overflowWrap = OverflowWrap.BREAK_WORD))
      print(rec.output())
  }
  ```
- **实测实际输出**（三行，最后一行是整块重复）：
  ```
  abc
  def
  abcdef
  ```
  `measure(t,3)` 报的是 `WidthRange(6,6)`，render 高度却是 3，末行宽 6——measure/render 契约（见 2.1）同时被打破。触发条件是“所有分块都满宽”：宽 3 时 `abcd`/`abcde` 末块不满宽，输出正确（`abc/d`、`abc/de`）；`abc` 只有一块且循环结束后直接补回同一 span，也不重复；只有 `abcdef` 这种长度为行宽整数倍且多于一块时才把末块重发。
- **宽字符连带症状**：`Text("日本語", PRE_WRAP, TRUNCATE)` 在宽 3 终端实测输出一行宽 **6** 的 `日本語`；包进默认 Panel 后右边框整行消失（`╭─╮ / │日│ / ╰─╯`）。
- **应有输出**：BREAK_WORD 应按终端单元格切块且每块只出现一次（`abc / def`，两行）；TRUNCATE 后行宽不得超过给定宽度。

### 风险 3：`HorizontalRule.rule` 用 `String.length`（UTF-16）当单元格宽，宽字符/规则字符让整线宽度翻倍或切裂

- **位置**：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/HorizontalRule.kt:111-118`（`width / c.length`、`c.repeat(ruleWidth)`、余数 `c.take(remaining)`）。
- **为什么有风险**：`HorizontalRule.measure` 宣称自己精确占满传入宽度（`WidthRange(width, width)`，`HorizontalRule.kt:57-59`），但 `rule()` 用字符数 `c.length` 计算重复次数，又用 UTF-16 的 `take(remaining)` 取余。规则字符是东亚宽字符（每字 2 格）时实际宽度 = 请求宽度的 2 倍；是辅助平面字符（每字 2 UTF-16、2 格）时 `take(1)` 还会切出孤立代理。该 widget 被 `Panel` 复用来画上下边（`Panel.kt:152-168`），宽度错误会让标题边框与内容边框对不齐。
- **最小复现**：
  ```kotlin
  import com.github.ajalt.mordant.rendering.AnsiLevel
  import com.github.ajalt.mordant.terminal.Terminal
  import com.github.ajalt.mordant.terminal.TerminalRecorder
  import com.github.ajalt.mordant.widgets.HorizontalRule

  fun main() {
      val rec = TerminalRecorder(width = 5, height = 24, ansiLevel = AnsiLevel.NONE)
      val t = Terminal(ansiLevel = AnsiLevel.NONE, width = 5, height = 24, terminalInterface = rec)
      t.print(HorizontalRule(ruleCharacter = "＝"))
      print(rec.output())
  }
  ```
- **实测实际输出**：`＝＝＝＝＝`，5 个全角等号，**终端单元格宽 10**；而 `measure(t,5)` 报 `WidthRange(5,5)`。把规则字符换成 `"😀"` 时实测输出码点为 `[128512, 128512, 55357]`（两个完整 emoji 加一个孤立高代理）。
- **应有输出**：实际单元格宽严格等于请求宽度 5（宽字符应重复 2 个再加 1 格的安全余数处理，且绝不输出孤立代理），与 measure 一致。

## 4. 两组相邻步骤为什么不能换序

### 4.1 样式合并（含 `downsample` 的输入准备）→ ANSI 能力降级 → `makeTag` 编码

这三步在 `renderLinesAnsi` 里是严格相邻的：布局期 `withStyle/foldStyles` 先把所有样式合成完整的 `TextStyle`（`table/Table.kt:405`、`Panel.kt:143,180-194`），渲染期每个 span 先 `downsample` 再 `makeTag`（`internal/AnsiRender.kt:27-32`）。题目要求的“ANSI 能力降级跟紧挨着它的那一步”即此。

- **把降级提前（在布局/样式合并之前降级）会坏什么**：布局层持有的就不再是完整能力描述。后续容器再叠加样式时，被提前降级的颜色无法重新参与量化决策，且 `withStyle` 的“右操作数非 null 覆盖”会在已经 16 色化的样式上叠加真彩色——最终 `makeTag` 拿到的是 `RGB`，ANSI16 终端会直接收到 `38;2;r;g;b` 真彩色序列。可见例子：`Terminal(ansiLevel = ANSI16).print(table { ... cell(TextColors.rgb("#123456")("x")); style = TextColors.blue })`，若先降级后合并，输出里会混入 `ESC[38;2;18;52;86m` 这种 16 色终端不认的码，屏幕上出现字面乱码而不是干净的 `ESC[34m`。
- **把降级推后（`makeTag` 之后再降级）会坏什么**：`makeTag` 是按“样式差”发 CSI 的（`AnsiRender.kt:90-130`），它一旦把 `RGB` 编成 `38;2;...` 序列，后面再降级就只能对字符串做事后修补，无法再知道两个相邻 span 是否量化到同一个 16 色（相邻同色合并依赖对象级相等，`AnsiRender.kt:91`）。可见例子：`AnsiLevel.NONE` 下打印红字串，正确输出是纯文本 `x`；推后降级时 `makeTag` 已先发出 `ESC[38;2;...m...ESC[39m`，事后若补一个 reset 只会得到 `ESC[0m` 与裸真彩色码并存，终端显示字面 escape 序列。降级必须发生在“样式还是对象、且所有合并已结束”的那个点上，早一点晚一点都会把错误直接打到屏幕上。

### 4.2 `measure`（决定子宽度）→ 子内容 `render` → `Lines.setSize` 定尺寸

在 `Panel.render` 中相邻：`measure(t,width)` 定 `contentWidth`（`widgets/Panel.kt:141,145-148`），`content.render(t, maxContentWidth)` 产出内容（`Panel.kt:150-151`），`setSize(contentWidth, ..., LEFT)` 归一化；表格对应 `measureColumn → calculateColumnWidths → render(...).withStyle(...).setSize(...)`（`table/Table.kt:136-213, 405-408`）。

- **把 render 提到 measure 之前（先渲染再量）会坏什么**：容器还没有 `WidthRange` 就无法决定 shrink-to-fit 的 `contentWidth`（`Panel.kt:147`），只能按终端全宽渲染并让文本在全宽处折行。可见例子：`Terminal(width=20).print(Panel(Text("a b c d e f g h"), expand=false))`，正确行为是面板收缩到最长单词所需宽度；若先按 18 渲染并在 18 处折行，面板会变宽、文本多出无谓换行，`╭...╮` 的宽度不再包住“自然宽度”的内容。表格里后果更直接：列宽求解（按 priority 在 min/max 间 `coerceIn` 分配，`table/Table.kt:185-196`）没有输入，列宽只能全给 0 或均分，长内容被压成空列。
- **把 `setSize` 提到子内容 render 之前（或跳过 render 直接定尺寸）会坏什么**：`setSize` 的输入是已经折好行、切好 span 的 `Lines`（`rendering/Lines.kt:77-87`）；没有 render 产物就没有行可裁剪/补白，垂直对齐（`topEmptyLineCount`，`Lines.kt:101-108`）也无从算起。更细的相邻顺序是 `render().withStyle(style).setSize(...)` 中 `withStyle` 必须在 `setSize` 前：`setSize` 在 `NONE` 对齐下补的是**无样式**空格（`Lines.kt:189`），先定尺寸后上色会把补白一起染成单元格背景。可见例子：带背景色的表格单元格内容短于列宽时，正确输出只有文字后面是默认底色；顺序倒置会看到整格（含补白）被背景色填满，表格色块越界到下一列边框。

## 5. “读懂了”的交付物：新增测试（选择路线一）

在 `mordant/src/commonTest/kotlin/com/github/ajalt/mordant/rendering/PipelineInvariantTest.kt` 新增一组测试（只新增文件，不改任何既有断言），钉死两件事：

1. **2.3 的降级幂等不变量**：ANSI16/TRUECOLOR 终端下，把真彩色样式串的渲染结果再次喂回 `Terminal.render`，字符串必须逐字节相等；`AnsiLevel.NONE` 下真彩色串必须渲染成无任何 CSI 的纯文本。若将来有人把降级时机挪到 `makeTag` 之后（4.1）或破坏了量化不动点，这组断言会立刻红。
2. **风险 2（`widgets/Text.kt:162-170` BREAK_WORD 整块重复）的特征化断言**：用 `Terminal(ansiLevel = AnsiLevel.NONE, width = 3)` 渲染 `Text("abcdef", PRE_WRAP, BREAK_WORD)`，当前实际输出是重复的三行 `abc/def/abcdef`。该测试以“记录当前缺陷”的形式把行数钉死（注释标明期望的正确行为是两行），后续修复此风险时该测试必须同步改为两行——防止这个静默的数据重复悄悄溜走。

选路线一而不是直接修代码：三个风险的正确修法都需要引入“按码点/单元格切分字符串”的统一原语（要同时处理 ZWJ 序列与组合字符，`internal/cellwidth.kt:47-81` 已说明连宽 emoji 都没有完美解），在“不重构、不改架构、不顺手优化”的约束下，任何一处生产代码改动都可能影响 `Lines.setSize`、`Text.wrap`、`HorizontalRule.rule` 三个热路径及大量既有黄金输出；而新增测试可以在零行为变更下把不变量和风险同时钉住，与本次只交付分析的目标一致。
