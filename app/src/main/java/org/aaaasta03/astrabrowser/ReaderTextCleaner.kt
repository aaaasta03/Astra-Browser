package org.aaaasta03.astrabrowser

/**
 * Converts GeckoView's extracted Markdown-like content into readable plain text.
 */
object ReaderTextCleaner {
    fun clean(content: String): String {
        var text = content
        text = text.replace(Regex("""!\[([^\]]*)]\([^)]+\)"""), "$1")
        text = text.replace(
            Regex("""\[([^\]]+)]\((?:https?://|mailto:)[^)]+\)"""),
            "$1"
        )
        text = text.replace(Regex("""(?m)^\s{0,3}#{1,6}\s+"""), "")
        text = text.replace(Regex("""(?m)^\s*>\s?"""), "")
        text = text.replace(Regex("""\*\*(.*?)\*\*"""), "$1")
        text = text.replace(Regex("""(?<!\*)\*(?!\*)(.*?)\*(?!\*)"""), "$1")
        text = text.replace(Regex("""(?m)^\s*[-*_]{3,}\s*$"""), "")
        return text.lines().joinToString("\n") { it.trimEnd() }
            .trim()
            .replace(Regex("""\n{3,}"""), "\n\n")
    }
}
