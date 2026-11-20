package app.granger

/**
 * Text a view model hands to the UI without holding a Context: a string resource, a plural, or [Raw] content (a title,
 * a tag) that isn't translated. Args may themselves be [UiText]; they resolve first.
 *
 * Shared since Plan I3 Task 4. Shared models name a resource by [Res.key] (the Android resource name, which is also the
 * iPhone's String Catalog key); Android code still writes `UiText.Res(R.string.x, …)`, which sets [Res.id] instead
 * (the app's `UiTextAndroid.kt`). Android resolves either; the iPhone resolves keys.
 */
sealed interface UiText {
    class Res private constructor(val key: String, val id: Int, val args: List<Any>) : UiText {
        constructor(key: String, vararg args: Any) : this(key, 0, args.toList())

        override fun equals(other: Any?) = other is Res && other.key == key && other.id == id && other.args == args
        override fun hashCode() = (31 * key.hashCode() + id) * 31 + args.hashCode()
        override fun toString() = "Res(${key.ifEmpty { id.toString() }}, $args)"

        companion object {
            /** An Android string resource by its id (`UiText.Res(R.string.x, …)` on Android). */
            fun ofId(id: Int, vararg args: Any): Res = Res("", id, args.toList())
        }
    }

    class Plural private constructor(val key: String, val id: Int, val count: Int, val args: List<Any>) : UiText {
        constructor(key: String, count: Int, vararg args: Any) : this(key, 0, count, args.toList())

        override fun equals(other: Any?) = other is Plural && other.key == key && other.id == id && other.count == count && other.args == args
        override fun hashCode() = ((31 * key.hashCode() + id) * 31 + count) * 31 + args.hashCode()
        override fun toString() = "Plural(${key.ifEmpty { id.toString() }}, $count, $args)"

        companion object {
            /** An Android plurals resource by its id (`UiText.Plural(R.plurals.x, n, …)` on Android). */
            fun ofId(id: Int, count: Int, vararg args: Any): Plural = Plural("", id, count, args.toList())
        }
    }

    data class Raw(val text: String) : UiText
}
