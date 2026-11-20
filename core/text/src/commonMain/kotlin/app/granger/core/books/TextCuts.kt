package app.granger.core.books

/** Cuts [text] into pieces of at most [max] characters, preferring spaces and never splitting a surrogate pair. */
fun cutAtSpaces(text: String, max: Int): List<String> {
    if (text.length <= max) return listOf(text)
    val out = ArrayList<String>()
    var start = 0
    while (text.length - start > max) {
        var end = text.lastIndexOf(' ', start + max)
        if (end <= start + max / 2) {
            end = start + max
            if (Character.isHighSurrogate(text[end - 1])) end--
        }
        out.add(text.substring(start, end).trim())
        start = end
        while (start < text.length && text[start] == ' ') start++
    }
    if (start < text.length) out.add(text.substring(start))
    return out.filter { it.isNotEmpty() }
}
