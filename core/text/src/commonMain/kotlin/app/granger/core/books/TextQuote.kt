package app.granger.core.books

/**
 * A passage to find on the page: [highlight] as it appears in the text, with a little of what comes before and after.
 * Shared since Plan I4b Task 8 (a book hit carries one, and Home's feeds are shared); `Snippets` makes them on Android.
 */
data class TextQuote(val before: String, val highlight: String, val after: String)
