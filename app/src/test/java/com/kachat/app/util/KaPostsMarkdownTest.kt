package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks this parser to the iOS one.
 *
 * Every expectation below was produced by running the equivalent cases through
 * `KaPostsMarkdown.swift` and recording exactly what it emitted. A post written on one platform is
 * read on the others, so a rule that drifts shows up as one reader seeing asterisks where another
 * sees bold - and the drift would be silent without this. If a rule genuinely needs to change,
 * change both parsers and re-record here.
 */
class KaPostsMarkdownTest {

    /** Compact "text + styled fragments" rendering of a parse, for readable assertions. */
    private fun describe(source: String): String {
        val rendered = KaPostsMarkdown.render(source)
        val spans = rendered.spans.joinToString("") { span ->
            val tags = buildList {
                if (span.style.bold) add("b")
                if (span.style.italic) add("i")
                if (span.style.underline) add("u")
                if (span.style.strikethrough) add("s")
                if (span.style.subtext) add("sub")
                span.style.link?.let { add("link=$it") }
            }.joinToString(",")
            "|[${span.start},${span.end}]'${rendered.text.substring(span.start, span.end)}'$tags"
        }
        return rendered.text + spans
    }

    @Test
    fun `inline markers match iOS`() {
        assertEquals("Bold|[0,4]'Bold'b", describe("**Bold**"))
        assertEquals("Italic|[0,6]'Italic'i", describe("*Italic*"))
        assertEquals("Underlined|[0,10]'Underlined'u", describe("__Underlined__"))
        assertEquals("Strikethrough|[0,13]'Strikethrough's", describe("~~Strikethrough~~"))
    }

    @Test
    fun `block markers match iOS`() {
        assertEquals("Subtext|[0,7]'Subtext'sub", describe("-# Subtext"))
        assertEquals("1. One\n2. Two\n3. Three", describe("1. One\n2. Two\n3. Three"))
        assertEquals("• Alpha\n• Beta", describe("* Alpha\n- Beta"))
        // -# is checked before the "- " bullet, so subtext is not read as a bullet reading "# x".
        assertEquals("quiet\n• bullet|[0,5]'quiet'sub", describe("-# quiet\n- bullet"))
    }

    @Test
    fun `links match iOS`() {
        assertEquals(
            "kachat.app|[0,10]'kachat.app'link=https://kachat.app/",
            describe("[kachat.app](https://kachat.app/)"),
        )
        // A bare host gets https, so a social-post style link still works.
        assertEquals(
            "Hello world, see docs now|[6,11]'world'b|[17,21]'docs'link=https://kachat.app",
            describe("Hello **world**, see [docs](kachat.app) now"),
        )
        // A scheme we will not open is left as literal text rather than made tappable. The
        // parenthesis inside makes the target `javascript:alert(1`, which is exactly the shape
        // that used to slip past the allowlist by being rewritten to `https://javascript:alert(1`
        // before the scheme was read.
        assertEquals("[click](javascript:alert(1))", describe("[click](javascript:alert(1))"))
        assertEquals("[a](data:text/html,<b>)", describe("[a](data:text/html,<b>)"))
        assertEquals("[d](ftp://h/f)", describe("[d](ftp://h/f)"))
        // Scheme-relative and in-app links are fine.
        assertEquals("b|[0,1]'b'link=https://evil.com/p", describe("[b](//evil.com/p)"))
        assertEquals("c|[0,1]'c'link=kachat://kapost/abc", describe("[c](kachat://kapost/abc)"))
    }

    @Test
    fun `nesting matches iOS`() {
        assertEquals(
            "bold with italic inside|[0,10]'bold with 'b|[10,16]'italic'b,i|[16,23]' inside'b",
            describe("**bold with *italic* inside**"),
        )
        assertEquals("• Important item|[2,11]'Important'b", describe("* **Important** item"))
    }

    @Test
    fun `punctuation is not formatting`() {
        // An opening marker followed by whitespace is arithmetic, not italics.
        assertEquals("2 * 3 = 6 and a * b", describe("2 * 3 = 6 and a * b"))
        // Empty and unclosed runs stay literal.
        assertEquals("**** and * alone", describe("**** and * alone"))
        assertEquals("**never closed", describe("**never closed"))
        assertEquals(
            "Just a normal post with a URL https://x.com and @alice.kas",
            describe("Just a normal post with a URL https://x.com and @alice.kas"),
        )
    }

    // MARK: - Toolbar edits

    private fun edit(
        action: KaPostsMarkdown.ToolbarAction,
        text: String,
        start: Int,
        end: Int,
    ): String {
        val result = KaPostsMarkdown.apply(action, text, start, end)
        return "${result.text}|sel='${result.text.substring(result.selectionStart, result.selectionEnd)}'"
    }

    @Test
    fun `inline toolbar actions match iOS`() {
        assertEquals("hello **world**|sel='world'", edit(KaPostsMarkdown.ToolbarAction.BOLD, "hello world", 6, 11))
        assertEquals("*hello* world|sel='hello'", edit(KaPostsMarkdown.ToolbarAction.ITALIC, "hello world", 0, 5))
        assertEquals("__abc__|sel='abc'", edit(KaPostsMarkdown.ToolbarAction.UNDERLINE, "abc", 0, 3))
        assertEquals("~~abc~~|sel='abc'", edit(KaPostsMarkdown.ToolbarAction.STRIKETHROUGH, "abc", 0, 3))
        // Nothing selected: markers inserted, caret between them.
        assertEquals("hello ****|sel=''", edit(KaPostsMarkdown.ToolbarAction.BOLD, "hello ", 6, 6))
    }

    @Test
    fun `inline toolbar actions toggle off match iOS`() {
        // Markers inside the selection, and markers just outside it, both unwrap.
        assertEquals("hello|sel='hello'", edit(KaPostsMarkdown.ToolbarAction.BOLD, "**hello**", 0, 9))
        assertEquals("hello|sel='hello'", edit(KaPostsMarkdown.ToolbarAction.BOLD, "**hello**", 2, 7))
        assertEquals("buy milk|sel='buy milk'", edit(KaPostsMarkdown.ToolbarAction.BULLET_LIST, "* buy milk", 2, 6))
    }

    @Test
    fun `line toolbar actions match iOS`() {
        assertEquals("* buy milk|sel='* buy milk'", edit(KaPostsMarkdown.ToolbarAction.BULLET_LIST, "buy milk", 0, 8))
        assertEquals(
            "* one\n* two\n* three|sel='* one\n* two\n* three'",
            edit(KaPostsMarkdown.ToolbarAction.BULLET_LIST, "one\ntwo\nthree", 0, 13),
        )
        assertEquals(
            "1. one\n2. two\n3. three|sel='1. one\n2. two\n3. three'",
            edit(KaPostsMarkdown.ToolbarAction.NUMBERED_LIST, "one\ntwo\nthree", 0, 13),
        )
        // Switching list kinds replaces the marker instead of stacking them.
        assertEquals(
            "1. one\n2. two|sel='1. one\n2. two'",
            edit(KaPostsMarkdown.ToolbarAction.NUMBERED_LIST, "* one\n* two", 0, 11),
        )
        assertEquals("-# small print|sel='-# small print'", edit(KaPostsMarkdown.ToolbarAction.SUBTEXT, "small print", 0, 11))
        // Only the lines the selection actually touches.
        assertEquals(
            "alpha\n* beta\ngamma|sel='* beta'",
            edit(KaPostsMarkdown.ToolbarAction.BULLET_LIST, "alpha\nbeta\ngamma", 7, 8),
        )
    }

    @Test
    fun `link toolbar action matches iOS`() {
        // Something selected: it becomes the label, and the target is pre-selected to type over.
        assertEquals(
            "see [docs](https://) here|sel='https://'",
            edit(KaPostsMarkdown.ToolbarAction.LINK, "see docs here", 4, 8),
        )
        // Nothing selected: the label placeholder is pre-selected instead.
        assertEquals(
            "see [link text](https://)|sel='link text'",
            edit(KaPostsMarkdown.ToolbarAction.LINK, "see ", 4, 4),
        )
    }
}
