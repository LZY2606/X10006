# Mordant 渲染链路分析

跟踪样本固定为：

```kotlin
val terminal = Terminal(ansiLevel = AnsiLevel.NONE, width = 20, height = 24)
val widget = Panel(Text("hello world"), title = Text("hi"))
terminal.print(widget)
```

除特别说明，下文的“样本值”都是这个 widget 在宽度 20、默认主题、默认 padding、rounded border 下的实际值。最终纯文本为：

```text
╭─── hi ────╮
│hello world│
╰───────────╯
```

## 1. 调用链

| # | 跳转与位置 | 进入数据 | 这一跳做什么 | 出来数据 / 样本中间值 |
|---:|---|---|---|---|
| 1 | `Terminal.print(widget)`：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/terminal/Terminal.kt:182` | `Widget`（样本是 `Panel`）和 `stderr=false` | widget 专用重载，先调用 `render(widget)`，再把结果交给 `rawPrint`。 | 进入下一跳前没有换行：`rawPrint(render(widget), false)`。 |
| 2 | `Terminal.render(widget)`：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/terminal/Terminal.kt:230` | `Terminal` 与 `Panel`；默认宽度来自 `Terminal.size`，样本是 `Size(20,24)`。 | 调用 widget 自己的布局渲染：`widget.render(this)`；此时默认参数把终端宽度 20 传给 `Panel.render`。 | 几何模型 `Lines`，还没有 ANSI 字符串。样本为 3 行、宽度 13、共 15 个 `Span`。 |
| 3 | Widget 接口：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Widget.kt:5` | `Terminal`、可用宽度，默认值是 `t.size.width`。 | 定义两阶段协议：`measure` 报 `WidthRange`，`render` 报 `Lines`。 | 样本实际分派到 `Panel.measure` / `Panel.render`。 |
| 4 | `Panel.measure`：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Panel.kt:128` | 外部宽度 20；border 占 2，内容最大宽度 18；title padding 为 1。 | 先测量内容，再加边框宽度；再测量标题，加两边边框和两个 padding；最后用 `maxWidthRange` 取较大需求。 | 内容 `Text.measure(_,18)=WidthRange(11,11)`，加边框为 `(13,13)`；标题 `Text("hi").measure(_,16)=WidthRange(2,2)`，加 4 为 `(6,6)`；面板结果 `WidthRange(13,13)`。 |
| 5 | 宽度范围类型与聚合：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/WidthRange.kt:9`、`:20`、`:28` | 子 widget 的 `(min,max)`；`Panel` 这里是内容和标题两个可空范围。 | `WidthRange` 保证 `min<=max`；`plus` 给横向装饰平移范围；通用 `maxWidthRange` 对同级子项分别取最大的 `min` 和最大的 `max`。 | 样本取 `max(min=13,min=6)=13`、`max(max=13,max=6)=13`。Table 则在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/table/Table.kt:116` 对列范围求和，因为列是水平排列。 |
| 6 | `Text` 构造与解析：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Text.kt:25`，`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/internal/Parsing.kt:20` | 原始 `String`，样本内容为 `hello world`，标题为 `hi`。 | `parseText` 先用 `parseAnsi` 把 ANSI 转成 `TextStyle`，再用 `splitWords` 切词，`splitLines` 去硬换行并造 `Span`。 | `hello world` 是 1 行 3 个 span：`hello`、空格、`world`；`hi` 是 1 行 1 个 span。二者样式都是 `DEFAULT_STYLE`。 |
| 7 | `Text.measure`：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Text.kt:53` | 可用宽度（内容 18，标题内部 16）、tab 宽度、`Whitespace`、构造器显式 `width/tabWidth`。 | 测量时强制 `TextAlign.NONE`、`OverflowWrap.NORMAL`，只保留空白折行造成的最小宽度；每行宽度是 span 的 `cellWidth` 之和。 | `hello world` 中最长行宽 11，单个最长词宽 5，因此默认 `PRE` 下为 `WidthRange(11,11)`；标题为 `WidthRange(2,2)`。 |
| 8 | `Panel.render` 求解：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Panel.kt:140` | 外部宽度 20、第 4 步的测量值 `(13,13)`。 | 再次测量；非 expand 时内容目标宽度为 `measurement.max-borderWidth=11`，且不超过可用 18；随后渲染内容、顶边、底边。 | `maxContentWidth=18`，`contentWidth=11`。内容先按 18 渲染成自然宽度 11，再被 `setSize(11, LEFT)` 规范成 11。 |
| 9 | `Text.render` / `Text.wrap`：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Text.kt:63`、`:67` | 内容宽度 18；标题后续被 `withAlign(NONE)` 后按 7 渲染。 | 处理空白、tab、折行、超长词和对齐，把输入 `Lines` 重新排成目标行；每加入一个 span 都按 `Span.cellWidth` 记账。 | 样本内容输出 1 行：`hello(5)`、空格 `(1)`、`world(5)`，共 3 个 span，行宽 11。标题 `hi` 输出 1 行 1 个 span，宽 2。 |
| 10 | `Lines.setSize`：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Lines.kt:77`，裁剪逻辑在 `:116` | 子 widget 已自然渲染出的 `Lines`，以及容器决定的目标宽高。 | 它不负责选择宽度，只执行容器已经决定的几何结果：纵向滚动/对齐、横向滚动、裁剪或补空格，使每行成为目标宽度。 | Panel 在 `Panel.kt:151` 对内容调用 `setSize(11,textAlign=LEFT)`；样本自然宽度已经是 11，所以仍是 1 行 3 span。Table 在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/table/Table.kt:405` 到 `:407` 用它把单元格规范成列宽和行高。 |
| 11 | `HorizontalRule.render` 顶/底边：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/HorizontalRule.kt:61` | Panel 传入的内部宽度 11；标题 `hi`；rule 字符 `─`；padding 1。 | 标题可用宽度为 `11-(4+2)=5`；标题行宽 2；规则总宽 `11-2-2=7`；居中时左边 3、右边 4；再由 `setSize(11,CENTER)` 规范额外标题行。 | 顶边 1 行 7 span：`───(3)`、空格、`hi(2)`、空格、`────(4)`；底边无标题，1 行 1 个宽 11 的 `───────────` span。 |
| 12 | Panel 合成边框：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Panel.kt:178` | 顶边 1 行、内容 1 行、底边 1 行，以及 corner / vertical span。 | 顶行用 `flatLine(es,top,sw)`；内容行左右加 vertical；底行用 `flatLine(ne,bottom,nw)`；`flatLine` 定义在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Lines.kt:55`。 | 3 行，宽度都为 13。第 1 行 7 个 span，第 2 行 5 个 span，第 3 行 3 个 span，总计 15 个 span。 |
| 13 | 回到 `Terminal.render`：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/terminal/Terminal.kt:231` | 上面的 `Lines(height=3,width=13)`、`AnsiLevel.NONE`、hyperlink 能力。 | 调用 `renderLinesAnsi`，把带样式的几何模型序列化成字符串。 | 样本字符串长度 41：三行各 13 字符，加 2 个 `\n`。没有尾随换行。 |
| 14 | ANSI 能力降级：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/internal/AnsiRender.kt:18`、`:58` | 每个 `Span(text,TextStyle)` 与终端能力。 | 每个 span 先 `downsample`，再由 `makeTag` 比较前后样式生成 CSI/OSC；行间插入 `\n`，每行末尾关闭样式。 | 样本所有 span 都是默认样式，`AnsiLevel.NONE` 下 `downsample` 返回 `DEFAULT_STYLE`，所有 tag 为空；输出纯 41 字符。 |
| 15 | `rawPrint`：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/terminal/Terminal.kt:249` | ANSI 字符串和 `stderr=false`。 | 包装成 `PrintRequest(text, trailingLinebreak=false, stderr=false)`。 | 样本 `PrintRequest.text` 长度 41。 |
| 16 | `sendPrintRequest`：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/terminal/Terminal.kt:272` | `PrintRequest`。 | 若接口允许，先重新探测终端尺寸；然后把请求交给 expect/actual 的拦截分发函数。 | JVM 上进入 `mordant/src/jvmMain/kotlin/com/github/ajalt/mordant/internal/MppInternal.jvm.kt:115`。 |
| 17 | 拦截器折叠：`mordant/src/jvmMain/kotlin/com/github/ajalt/mordant/internal/MppInternal.jvm.kt:115` | 原始 `PrintRequest`、`List<TerminalInterceptor>`、`TerminalInterface`。 | 在 JVM 打印锁内按列表顺序 `fold`，每个拦截器接收前一个请求并返回新请求，然后调用接口。 | 无拦截器时请求不变；接口方法是 `TerminalInterface.completePrintRequest`，接口声明在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/terminal/TerminalInterface.kt:31`。 |
| 18 | 真正交给终端接口：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/terminal/StandardTerminalInterface.kt:27` | 最终 `PrintRequest`。 | 根据 stderr 和 `trailingLinebreak` 分派到 `print`、`println` 或 `printStderr`。 | 样本 `stderr=false`、`trailingLinebreak=false`，执行 `print(request.text)`；这是 Mordant 把字符串交给实际终端 I/O 的边界。 |

Table 的水平分配也遵循同一协议：`TableImpl.measure` 在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/table/Table.kt:111` 扣掉边框并测量列；`calculateColumnWidths` 在 `:162` 到 `:213` 按固定列、Auto、Expand 的优先级分配；`TableRenderer.renderCell` 在 `:405` 到 `:407` 先 render、套单元格样式，再 `setSize` 到最终格子。

## 2. 不变量清单

### 2.1 `WidthRange` 必须描述一个合法、可用于分配的水平区间

- 维持位置：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/WidthRange.kt:9` 到 `:17` 要求 `min<=max`，并集中实现加边框、除跨列数的算术；`:28` 到 `:40` 对同级子项按最大值聚合。
- 含义：`min` 是不发生截断/词内折断时至少要给的宽度，`max` 是给足空间时愿意占用的宽度。垂直堆叠或标题与内容这类“同级占位”取最大值；表格列这类水平相邻项在 `Table.kt:116` 到 `:119` 求和。
- 可见破坏例子：若把一个长单词报成 `WidthRange(max=3,min=20)`，构造器会立即抛出 `Range min cannot be larger than max`；如果绕过构造器让容器按 `max=3` 分配，文本实际宽 20，Panel 的右边框会被内容推到第 21 列，而顶边仍按 3 列闭合，形成上下错位。

### 2.2 同一个 widget、同一个终端和同一个宽度，measure/render 必须回答同一套几何

- 维持位置：接口契约在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Widget.kt:5`；`Panel.render` 在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Panel.kt:141` 重新调用同一个 `measure`，并在 `:145` 到 `:151` 用测量最大值决定 `contentWidth`；`HorizontalRule` 在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/HorizontalRule.kt:57` 报固定宽度，并在 `:61` 到 `:100` 填满传入宽度；Table 在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/table/Table.kt:162` 到 `:213` 用测量结果分配，在 `:405` 到 `:407` 强制单元格达到分配尺寸。
- 必须满足的关系：render 使用的宽度预算必须来自同一次 measure 所描述的范围；没有折行截断时，实际宽不超过 `max`；容器给足 `min` 后不应再出现省略、词内折断或换行；需要矩形格子的容器在 render 后通过 `setSize` 补齐，因此每个表格单元、Panel 行最终严格等于分配宽度。
- 可见破坏例子：如果 `Text("abc").measure(_,3)` 报 `(3,3)`，render 却输出宽 4 的 `abc…`，`Panel` 顶边会按 5 列闭合而内容行占 6 列；肉眼可见为右侧 `│` 比对位错出一列。

哪些参数会让“同一个外部宽度”不再等价：

- `Text(width = ...)` 或 `tabWidth = ...`：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Text.kt:53` 到 `:64` 用构造器值覆盖传入宽度；两次 render 的 `Terminal` 宽度相同也可能用不同预算。
- `overflowWrap`：measure 在 `Text.kt:55` 强制 `OverflowWrap.NORMAL`，render 在 `:64` 使用真实策略；它报告的是不截断需求，不代表 render 在宽度不足时仍保持原文本。
- `align`：measure 在 `Text.kt:55` 强制 `NONE`，render 在 `:64` 使用 `LEFT/RIGHT/CENTER/JUSTIFY`；对齐会补空格，使 render 宽度等于给定预算而不是自然宽度。
- `Whitespace.NOWRAP`、`PRE` 等 `wrap=false`：`Text.kt:57` 到 `:62` 令 `min=max=自然宽度`；给小于自然宽度时不会按空白换行，只能溢出或交给外层裁剪。
- 终端状态差异：`Text(tabWidth=null)` 读取 `Terminal.tabWidth`，主题还会影响 border/title padding；所以“同一个宽度”还隐含同一个 terminal 配置。

### 2.3 `Span` 是非空、无换行、无 ANSI、空白类型纯一的最小样式段

- 维持位置：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Span.kt:21` 到 `:28` 拒绝空字符串、空白和非空白混合、`\n` 以及 CSI；`TextStyle` 与文本同存在 `Span.text/style` 中。
- 可见破坏例子：若一个 span 允许 `"a b\nc"`，`Text.wrap` 在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Text.kt:126` 到 `:175` 只把它当一个 word 记账，而 `Lines` 的换行又是隐式的；输出会在未被布局登记的地方折行，后续边框和行高都少算。若把 CSI 放进 `Span.text`，`renderLinesAnsi` 又会在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/internal/AnsiRender.kt:30` 当普通文本输出，转义符可能直接显示或错误改变终端状态。

### 2.4 行宽和容器矩形都以终端单元格计，而不是字符串长度

- 维持位置：`Span.cellWidth` 在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Span.kt:39` 缓存 `stringCellWidth`；`Line.lineWidth` 在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Lines.kt:51` 到 `:52` 求和；`Lines.width` 在 `:23` 到 `:24` 取最大行宽；`setSize` 在 `:176` 到 `:196` 用剩余单元格数补空。
- 可见破坏例子：`"媒人"` 的 Kotlin 字符数是 2，但终端宽度是 4；若按字符数放进固定宽 4 的表格单元，右边框会比预期靠右两列。现有测试 `mordant/src/commonTest/kotlin/com/github/ajalt/mordant/table/TableTest.kt:261` 也按 4 个单元格安排边框。

### 2.5 容器的 `setSize` 在子 widget render 之后执行，用来强制执行布局决策

- 维持位置：Panel 在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Panel.kt:150` 到 `:151` 先 render 后 setSize；Table 在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/table/Table.kt:405` 到 `:407` 先 render、套样式、再设宽高；具体裁剪/填充在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Lines.kt:77` 到 `:114`。
- 可见破坏例子：Panel 内容是短文本而目标宽度 11，如果不做 `setSize(11,LEFT)`，内容行只有自然宽度；左右 vertical 被 `flatLine` 紧邻短文本，底边长 13、内容行短，视觉上右边框提前出现。

### 2.6 样式合并有四种方向，不能混用“谁传参在后面”这一直觉

- `TextStyle.plus`：维持在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/TextStyle.kt:70` 到 `:87`。方向是左基底、右覆盖；`other` 中非 null 的属性赢，`other=null` 的属性保留左侧。例：`red+bold` 后再 `+blue`，前景变蓝，bold 保留。
- `Lines.withStyle`：维持在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Lines.kt:31` 到 `:37`，底层是 `Span.withStyle` 的 `this.style + style`，见 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Span.kt:46`。方向是已有文本样式为基底、传入容器样式覆盖；但传入样式没有设置的属性不会清掉文本样式。
- `Lines.replaceStyle`：维持在 `Lines.kt:43` 到 `:47`，底层 `Span.replaceStyle` 在 `Span.kt:47` 直接赋成传入样式。方向不是合并，而是传入样式整体赢，旧样式完全丢失。
- `foldStyles`：维持在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/TextStyle.kt:143` 到 `:153`。参数从左到右是 cell、row、stripe、section column、table column、section、table；每个新元素执行 `s + style`，即新元素做左侧覆盖已有累积值。最终优先级正好是参数顺序：cell > row > stripe > section column > table column > section > table。Table 传参位置在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/table/TableLayout.kt:113` 到 `:121`。
- 注意单元格内部文本自带样式还要再过一次 `Lines.withStyle`：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/table/Table.kt:405` 到 `:407`。所以表格层样式只能覆盖其设置为非 null 的属性；文本 span 自己显式设置而表格层未设置的属性仍保留。
- 可见破坏例子：cell 设红前景、table 设蓝粗体，正确结果是 cell 的红赢且粗体保留；若 fold 顺序反过来，该格子会变蓝。`replaceStyle` 若误用在文本上，则文本原有颜色和 bold 都会消失。

### 2.7 ANSI 降级只发生在终端边界，且必须对样式模型幂等

- 维持位置：widget render 产出的 `Span.style` 不检查终端能力；能力转换集中在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/internal/AnsiRender.kt:27` 调用 `downsample`，其分支在 `:58` 到 `:87`；之后 `makeTag` 在 `:90` 到 `:130` 把样式差异变成 CSI/OSC。
- 为什么在这一层：布局只关心文本和样式语义，不解析 ANSI 字节。若在布局前降级，hyperlink、RGB、bold 等能力决策会污染可在另一个终端复用的 widget；若在 `makeTag` 之后降级，已经写出的 RGB CSI 或 OSC8 无法再被撤销。
- 幂等要求：`downsample` 的输出受同一等级再次处理后保持等价。NONE 把任何样式变成 `DEFAULT_STYLE`；ANSI16 看到 `Ansi16` 不再转换；ANSI256 保留 `Ansi16/Ansi256`；TRUECOLOR 保留原颜色，只按 hyperlink 能力删除链接。这样把已经降级的字符串再用 `Text(...)` 解析、重新 render，可见样式不继续变化。
- “结果不变”的边界：这里说的是降级映射幂等，不是所有终端 ANSI 字节逐字节往返都绝对不变。重新经 `Text` 渲染会重新规整标签、合并相邻样式；但不会把已变成 NONE 的纯文本再变出颜色，也不会第二次把 ANSI256 转成不同的 ANSI16。
- `TextStyle.invoke(text)` 旁路：入口在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/TextStyle.kt:54`，实现在 `AnsiRender.kt:38` 到 `:56`。它绕过 `Terminal`、`Widget.measure/render` 和 `downsample`，直接对字符串包标签，并用 regex 识别已有 ANSI。它适合手动立刻输出，但不会按当前 `AnsiLevel` 降级；若随后走 `rawPrint`，标签会原样到终端。
- 可见破坏例子：RGB 样式在 `AnsiLevel.NONE` 下若仍输出 `ESC[38;2;...m`，重定向到文件会看到字面转义或被错误高亮；hyperlink 在不支持时若仍输出 OSC8，终端可能显示多余控制串。旁路如果误以为它也降级，在 NONE 终端同样会泄漏这些代码。

### 2.8 换行、截断、对齐与东亚宽字符必须分清码点、UTF-16 字符和终端单元格

- `cellWidth(codepoint)`：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/internal/cellwidth.kt:22` 到 `:44`。输入是 Unicode 码点 `Int`，输出终端单元格；ASCII 快速返回 1，BS/DEL 返回 -1，其它查 Unicode 宽表。
- `stringCellWidth(String)`：`cellwidth.kt:47` 到 `:80`。它通过 expect actual 的 `codepointSequence` 按码点遍历；JVM 实现在 `mordant/src/jvmMain/kotlin/com/github/ajalt/mordant/internal/MppInternal.jvm.kt:61` 到 `:63`，使用 `String.codePoints()`，因此不是按 UTF-16 字符。它还特别识别 ZWJ emoji 序列，把完整序列折叠成约两个终端单元。
- `Span.cellWidth`：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Span.kt:39`。它是终端单元格宽度，并缓存结果。
- `Span.take(n)` / `Span.drop(n)`：`Span.kt:40` 到 `:41`。这里调用 Kotlin `String.take/drop`，参数和切分位置都是 UTF-16 code unit，不是码点也不是单元格；调用方却在 `Lines.setSize` 中传入单元格预算，这是风险点之一。
- `Lines.setSize`：外层判断和 `remainingWidth` 是终端单元格，见 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Lines.kt:119`、`:132`、`:149`、`:158`、`:176`；真正切开 span 时委托给上述 UTF-16 `Span.take/drop`，见 `:140` 和 `:158`。补空在 `:187` 到 `:195` 按单元格补 ASCII 空格。
- `Text.wrap`：换行判断使用 `span.cellWidth`，见 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Text.kt:141` 到 `:149`；对齐补空也按单元格预算，见 `:197` 到 `:228`。但 `TRUNCATE` 的 `span.text.take(wrapWidth)` 在 `:157` 到 `:159`、`BREAK_WORD` 的 `chunked(wrapWidth)` 在 `:165` 到 `:170` 都是按 UTF-16 字符切。`ELLIPSES` 原先也按字符预留省略号，本次已修为码点候选字符串的单元格宽度判断，见 `Text.kt:161` 到 `:163` 和 `:264` 到 `:275`。
- parser 分词：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/internal/Parsing.kt:54` 到 `:80` 用 `while(i<t.length)` 和 `t[i]` 扫描 UTF-16 code unit，但只在空白、硬换行等边界切分；普通 astral emoji 不会在类型边界处被主动切开。真正的终端宽度仍由 `stringCellWidth` 处理。
- 可见破坏例子：`"あいう"` 每个日文假名为 1 个 UTF-16 字符但占 2 个终端单元。宽 3 的省略行如果按字符取前 2 个再加 `…`，会得到 `あい…`，终端宽 5，超出 2 列；正确结果是 `あ…`，终端宽 3。Viewport/Table 的裁剪如果按 UTF-16 数切，也会让声明宽 2 的行实际仍宽 4。

### 2.9 主题查找是“显式构造参数 > 当前主题 map > widget 代码默认值”

- `ThemeStyle.of`：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/internal/ThemeValue.kt:9` 到 `:26`。构造参数非 null 时是 `Explicit`，直接返回参数；null 时记录 key 和 widget default，再查 theme。
- `ThemeString.of`：`ThemeValue.kt:31` 到 `:48`，同样两级选择，只是值是字符串。
- `ThemeDimension.of`：`ThemeValue.kt:75` 到 `:92`，同样两级选择，只是值是整数。
- 接到 `Theme` 后：`Theme.style` 在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Theme.kt:140` 到 `:142` 查 `styles` map，miss 时使用 `ThemeStyle.Default` 携带的 default；`string` 在 `:153` 到 `:154`，`dimension` 在 `:159` 到 `:162` 同理。连起来一共三级：构造器 explicit、当前 theme map、widget 传入的代码 default。
- `Theme.Plain`：定义在 `Theme.kt:104` 到 `:109`。它的 styles map 为空，所以所有 style key 都 miss 并落到代码 default（通常 `DEFAULT_STYLE`）；strings 和 dimensions 复制 `Theme.Default`，同时关闭 `progressbar.pulse` flag。
- `Theme.PlainAscii`：定义在 `Theme.kt:111` 到 `:130`。它从 Plain 复制，再覆盖一批字符串为 ASCII、设置 `markdown.table.ascii=true`；未覆盖的 dimensions（如 `panel.title.padding=1`）仍来自 Default，styles 仍为空。
- 可见破坏例子：`Panel(borderStyle=null)` 应该允许当前主题把 `panel.border` 改成粗体或红色；若 `ThemeStyle.of` 把 null 也变成 explicit default，自定义主题边框不会生效。相反，传入非 null 绿色后还去查主题，会让调用者无法强制覆盖。

## 3. 三个风险点

### 风险 1：`ELLIPSES` 用 UTF-16 字符数预留省略号，导致宽字符行超宽（本次已修复）

- 位置：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Text.kt:161`。修复前这里是 `span.text.take(wrapWidth - 1) + "…"`；修复后由 `takeCellWidth` 在 `Text.kt:264` 到 `:275` 按候选字符串的终端单元格宽度决定保留几个码点。
- 为什么有风险：折行判断在 `Text.kt:141` 到 `:152` 使用 `Span.cellWidth`，知道 `あ` 占 2 格；但省略号截断此前却按 Kotlin `String.take` 的字符数计算。测量认为宽 3 足够的布局，render 后可能产出宽 5，直接破坏 Panel/Table 的矩形边界。
- 最小复现：

```kotlin
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.OverflowWrap
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Text

val terminal = Terminal(ansiLevel = AnsiLevel.NONE, width = 3)
val widget = Text(
    "あいう",
    whitespace = Whitespace.NORMAL,
    overflowWrap = OverflowWrap.ELLIPSES,
)
check(terminal.render(widget) == "あ…")
```

- 修复前实际输出：`あい…`。
- 应有输出：`あ…`。
- 差异：`あ`、`い`、`…` 各占 1、2、1 个终端单元，旧输出宽 `2+2+1=5`，超过声明行宽 3；新输出宽 `2+1=3`。
- 回归测试：`mordant/src/commonTest/kotlin/com/github/ajalt/mordant/rendering/TextOverflowWrapTest.kt:47` 到 `:52` 的 `ellipsesEastAsianWideCharacters` 钉住该结果。

### 风险 2：`Lines.setSize` 用单元格做判断，却让 `Span.take/drop` 按 UTF-16 字符裁剪

- 位置：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Lines.kt:140` 和 `:158`。这两处传入的是剩余终端单元格数，但被调用的 `Span.take/drop` 在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/Span.kt:40` 到 `:41` 按 UTF-16 code unit 切字符串。
- 为什么有风险：`resizeLine` 在 `Lines.kt:159` 直接把本地 `width` 记成 `newWidth`，没有重新读取被切出 span 的 `cellWidth`。当一个 span 中包含多个东亚宽字符时，切出来的 span 可能仍比目标行宽更宽，而算法以为已经裁满，不再补空或继续裁。Viewport、固定宽表格单元和 Panel 的内容规范化都会经过这里。
- 最小复现：

```kotlin
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Text
import com.github.ajalt.mordant.widgets.Viewport

val terminal = Terminal(ansiLevel = AnsiLevel.NONE, width = 3)
val lines = Viewport(Text("あいうえお"), width = 3).render(terminal, 3)
check(lines.width == 3)
check(lines.lines.single().joinToString("") { it.text } == "あ ")
```

- 当前实际输出：第一行文本是 `あいう`，`Lines.width=6`。
- 应有输出：第一行为 `あ `，`Lines.width=3`；一个宽字符占 2 格，剩余 1 格由空格补齐。
- 差异：Viewport 的 measure 在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Viewport.kt` 中声明固定宽 3，但 render 产出 6 格，调用方拿到的边框、滚动偏移和右边界都会错位。

### 风险 3：`HorizontalRule.rule` 用 `String.length` 计算多单元格规则字符，奇数宽度会溢出

- 位置：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/HorizontalRule.kt:111` 到 `:118`。
- 为什么有风险：`ruleWidth = width / c.length` 和 `remaining = width % c.length` 默认规则字符的 UTF-16 长度等于终端宽度。默认 `─` 是 1 格所以正常；但公开 API 允许任意非空、不含换行的规则字符。若调用者传入占 2 格的 `─` 之外的宽字符（例如某些宽 dash/block 字符），重复次数仍按 1 格计算，最终行宽翻倍。
- 最小复现：

```kotlin
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.HorizontalRule

val terminal = Terminal(ansiLevel = AnsiLevel.NONE, width = 9)
val rule = HorizontalRule(ruleCharacter = "あ")
val lines = rule.render(terminal, 9)
check(lines.width == 9)
check(terminal.render(rule) == "ああああ ")
```

- 当前实际输出：`HorizontalRule.measure` 在 `HorizontalRule.kt:57` 到 `:59` 报 `WidthRange(9,9)`，但 render 出 9 个 `あ`，`Lines.width=18`，字符串为 `あああああああああ`。
- 应有输出：宽字符最多重复 4 次占 8 格，剩余 1 格不能再放一个宽字符，应为 `ああああ `，总宽 9；或者 API 明确拒绝宽规则字符。
- 差异：测量值、标题宽度扣除和实际边框宽度使用了两套单位，放在 Panel 顶边时右边角会比其他行宽出 9 列。

## 4. 两组相邻步骤为什么不能换序

### 4.1 宽度测量 / 求解 不能与子 widget render、`setSize` 互换

相关位置：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Panel.kt:141` 先得到 `measurement`，`:145` 到 `:148` 求 `contentWidth`，`:150` 到 `:151` 才 render 内容并 `setSize`；顶/底边在 `:152` 到 `:168` 使用同一个 `contentWidth`。

- 提前 render：也就是在知道 `contentWidth` 前让内容按自然宽度或猜测宽度生成，再画边框。以 `Terminal(AnsiLevel.NONE, width=10)` 打印 `Panel(Text("hello world"))` 为例，内容自然宽 11，但面板外宽只有 10、内部只能给 8。正确结果会把内容行裁成内部 8：

```text
╭────────╮
│hello wo│
╰────────╯
```

如果先按自然宽度 11 生成内容并拼 vertical，内容行变成 `│hello world│`（13 格），而顶/底边仍只能按外宽 10 画成 `╭────────╮`；右边框比顶角位置靠右 3 格。

- 推后测量：也就是先 render，再根据 render 结果决定测量值。对同一个宽度 10 的例子，容器会看到内容自然宽 11，于是把面板外宽做成 13：`╭───────────╮`、`│hello world│`、`╰───────────╯`。三行内部虽然对齐，但整个 widget 超出终端 10 列；真实终端会在第 10 列硬折行，右边框和边角掉到下一行。这正是 `WidthRange.min=11` 想告诉容器“宽度不足会裁切”的信息，不能等 render 完再补算。
- `setSize` 也必须在子内容 render 之后、边框合成之前。提前则还没有可裁剪/补齐的 `Lines`；推后则 vertical/corner 已经贴到长短不一的内容行上，Panel 和 Table 的矩形边界都会破裂。

### 4.2 ANSI 能力降级不能与紧邻的 `makeTag` 串码步骤互换

相关位置：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/internal/AnsiRender.kt:27` 先对每个 span 调 `downsample`，`:28` 立刻把旧样式到新样式的差异交给 `makeTag`；`makeTag` 在 `:90` 到 `:130` 生成 CSI/OSC。

- 提前降级：如果降级发生在 `widget.render(this)` 之前，也就是所有子内容和容器样式还没合并完时，后续 Table/Panel 仍会在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/table/Table.kt:405` 到 `:407` 这类位置通过 `withStyle` 加入新的 RGB 或 hyperlink。提前降级不到这些后加入的样式，ANSI16 终端会收到本不该出现的 truecolor CSI `ESC[38;2;r;g;bm`。可见现象是低能力终端把代码显示成字面的 `[38;2;...m`，或按不可预期的颜色解释。另一个后果是 widget 在 render 前就绑定某个终端能力，同一个语义 `Lines` 无法再给更高能力终端使用：提前转成 ANSI16 后，TRUECOLOR 终端也无法恢复原始 RGB，颜色从 24 位变成 16 色。
- 推后降级：如果先执行 `makeTag`，再尝试降级，字符串里已经有 CSI/OSC 字节，而 `downsample` 的输入类型是 `TextStyle`，不是已生成字符串；它无法可靠撤销已经写出的 `ESC[38;2;...m` 或 OSC8 链接。`AnsiLevel.NONE` 的正确输出是纯文本，例如样式化的 `x` 应输出 `x`；推后后会输出 `ESC[38;2;255;0;0mxESC[39m`，重定向到文件或无颜色终端时就能看见转义码。
- 现有的旁路也证明了这个顺序的重要性：`TextStyle.invoke(text)` 在 `mordant/src/commonMain/kotlin/com/github/ajalt/mordant/rendering/TextStyle.kt:54` 直接进入 `AnsiRender.kt:38`，它会 `makeTag` 但不经过当前终端的 `downsample`。所以它只能用于明确要手写样式的旁路；标准 widget 输出必须先走语义 `Lines`，再 `downsample`，最后 `makeTag`。

## 5. 本次选择：最小修复 + 回归测试

我选择了“做一处最小改动修掉其中一个风险点，配回归测试”。

- 修复风险：风险 1，`ELLIPSES` 对东亚宽字符按 UTF-16 字符数预留省略号。
- 生产代码：`mordant/src/commonMain/kotlin/com/github/ajalt/mordant/widgets/Text.kt:161` 到 `:163` 改为调用新的 `takeCellWidth`；辅助函数在 `Text.kt:264` 到 `:275`，逐个码点追加候选字符，并用既有的 `stringCellWidth` 判断是否仍满足单元格预算。
- 回归测试：新增 `mordant/src/commonTest/kotlin/com/github/ajalt/mordant/rendering/TextOverflowWrapTest.kt:47` 到 `:52`，断言宽 3 的 `あいう` 渲染为 `あ…`。
- 选择原因：这个问题可用很小、局部的改动修复，不改变 Widget、Lines、ANSI 或表格架构；风险 2 牵涉 `Span.take/drop` 的单位语义和所有裁剪调用方，风险 3 牵涉宽规则字符是否允许部分字符或报错，二者都需要更大的设计决策，不适合在“不重构、不顺手优化”的要求下一并修改。
