package com.github.ajalt.mordant.rendering

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.TerminalRecorder
import com.github.ajalt.mordant.widgets.Text
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.js.JsName
import kotlin.test.Test

class PipelineInvariantTest {
    private fun terminal(level: AnsiLevel, width: Int = 20): Terminal {
        val recorder = TerminalRecorder(
            ansiLevel = level,
            width = width,
            height = 24,
            hyperlinks = true,
            outputInteractive = true,
            inputInteractive = true,
        )
        return Terminal(
            ansiLevel = level,
            width = width,
            height = 24,
            hyperlinks = true,
            terminalInterface = recorder,
        )
    }

    // Downsampling must reach a fixed point: rendering output that was already rendered for a
    // lower capability level must not change the bytes a second time. See ANALYSIS.md §2.3.
    @[Test JsName("ansi16_downsample_is_idempotent")]
    fun `ansi16 downsample is idempotent`() {
        val t = terminal(AnsiLevel.ANSI16)
        val styled = TextColors.rgb("#123456")("x")
        val first = t.render(styled)
        t.render(first) shouldBe first
    }

    @[Test JsName("truecolor_output_is_idempotent")]
    fun `truecolor output is idempotent`() {
        val t = terminal(AnsiLevel.TRUECOLOR)
        val styled = TextColors.rgb("#123456")("x")
        val first = t.render(styled)
        t.render(first) shouldBe first
    }

    @[Test JsName("none_level_strips_all_ansi")]
    fun `none level strips all ansi`() {
        val t = terminal(AnsiLevel.NONE)
        val styled = (TextColors.rgb("#123456") + TextStyles.bold)("x")
        val first = t.render(styled)
        first shouldBe "x"
        first.shouldNotContain("\u001b")
        // Idempotent at the zero-capability fixed point as well.
        t.render(first) shouldBe "x"
    }

    // Characterization test for ANALYSIS.md §3 risk 2: Text.wrap's BREAK_WORD branch emits the
    // final full-width chunk twice (Text.kt lines 162-170). The correct output after fixing the
    // bug is two lines ("abc" / "def"); this test pins the current duplicated behavior so that a
    // fix has to update it deliberately.
    @[Test JsName("break_word_duplicates_final_chunk_characterization")]
    fun `break word duplicates final chunk characterization`() {
        val t = terminal(AnsiLevel.NONE, width = 3)
        val widget = Text(
            "abcdef",
            whitespace = Whitespace.PRE_WRAP,
            overflowWrap = OverflowWrap.BREAK_WORD,
        )
        t.render(widget) shouldBe "abc\ndef\nabcdef"
    }

    // When the word length is not a multiple of the line width, the final partial chunk is not
    // duplicated; this pins the other side of the boundary for the same Text.kt branch.
    @[Test JsName("break_word_partial_final_chunk_is_not_duplicated_characterization")]
    fun `break word partial final chunk is not duplicated characterization`() {
        val t = terminal(AnsiLevel.NONE, width = 3)
        val widget = Text(
            "abcde",
            whitespace = Whitespace.PRE_WRAP,
            overflowWrap = OverflowWrap.BREAK_WORD,
        )
        t.render(widget) shouldBe "abc\nde"
    }
}
