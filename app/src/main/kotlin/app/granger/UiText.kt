package app.granger

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Text a view model hands to the UI without holding a Context: a string resource, a plural, or [Raw]
 * content (a title, a tag) that isn't translated. Args may themselves be [UiText]; they resolve first.
 */
sealed interface UiText {
    class Res(@StringRes val id: Int, vararg args: Any) : UiText {
        val args: List<Any> = args.toList()
        override fun equals(other: Any?) = other is Res && other.id == id && other.args == args
        override fun hashCode() = 31 * id + args.hashCode()
        override fun toString() = "Res($id, $args)"
    }

    class Plural(@PluralsRes val id: Int, val count: Int, vararg args: Any) : UiText {
        val args: List<Any> = args.toList()
        override fun equals(other: Any?) = other is Plural && other.id == id && other.count == count && other.args == args
        override fun hashCode() = (31 * id + count) * 31 + args.hashCode()
        override fun toString() = "Plural($id, $count, $args)"
    }

    data class Raw(val text: String) : UiText

    @Composable
    fun resolve(): String = when (this) {
        is Res -> stringResource(id, *resolved(args))
        is Plural -> pluralStringResource(id, count, *resolved(args))
        is Raw -> text
    }

    fun resolve(context: Context): String = when (this) {
        is Res -> context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
        is Plural -> context.resources.getQuantityString(id, count, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
        is Raw -> text
    }
}

@Composable
private fun resolved(args: List<Any>): Array<Any> = args.map { if (it is UiText) it.resolve() else it }.toTypedArray()
