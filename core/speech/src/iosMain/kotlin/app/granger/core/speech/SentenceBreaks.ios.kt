package app.granger.core.speech

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreFoundation.CFLocaleCreate
import platform.CoreFoundation.CFRangeMake
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFStringTokenizerAdvanceToNextToken
import platform.CoreFoundation.CFStringTokenizerCreate
import platform.CoreFoundation.CFStringTokenizerGetCurrentTokenRange
import platform.CoreFoundation.kCFStringTokenizerTokenNone
import platform.CoreFoundation.kCFStringTokenizerUnitSentence
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSString

/**
 * `CFStringTokenizer`'s sentence units (ICU's sentence break rules underneath). Its tokens are the sentences with
 * their trailing whitespace, as `BreakIterator`'s spans are; any gap between tokens is joined to the token before, so
 * the spans cover the text in order as Android's do.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal actual fun icuSentenceBreaks(text: String, languageTag: String): List<Pair<Int, Int>>? {
    if (text.isEmpty()) return emptyList()
    // Read before the NSString cast below: after it, `text.length` would smart-cast to NSString's (a ULong).
    val n: Int = text.length
    val tagText: String = languageTag.replace('-', '_')
    @Suppress("CAST_NEVER_SUCCEEDS", "UNCHECKED_CAST")
    val string = CFBridgingRetain(text as NSString) as CFStringRef?
    @Suppress("CAST_NEVER_SUCCEEDS", "UNCHECKED_CAST")
    val tag = CFBridgingRetain(tagText as NSString) as CFStringRef?
    val locale = CFLocaleCreate(null, tag)
    val unit: ULong = kCFStringTokenizerUnitSentence
    val tokenizer = CFStringTokenizerCreate(null, string, CFRangeMake(0L, n.toLong()), unit, locale)
    try {
        tokenizer ?: return null
        val starts = mutableListOf<Int>()
        val none: ULong = kCFStringTokenizerTokenNone
        while (true) {
            val token: ULong = CFStringTokenizerAdvanceToNextToken(tokenizer)
            if (token == none) break
            val location: Long = CFStringTokenizerGetCurrentTokenRange(tokenizer).useContents { location }
            starts += location.toInt()
        }
        if (starts.isEmpty()) return listOf(0 to n)
        starts[0] = 0
        return starts.mapIndexed { i, s -> s to (starts.getOrNull(i + 1) ?: n) }
    } finally {
        tokenizer?.let { CFRelease(it) }
        locale?.let { CFRelease(it) }
        CFBridgingRelease(tag)
        CFBridgingRelease(string)
    }
}
