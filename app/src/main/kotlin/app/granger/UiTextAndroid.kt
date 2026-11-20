package app.granger

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/** `UiText.Res(R.string.x, …)`, as Android code has always written it (the shared [UiText] keys by name). */
operator fun UiText.Res.Companion.invoke(@StringRes id: Int, vararg args: Any): UiText.Res = ofId(id, *args)

/** `UiText.Plural(R.plurals.x, n, …)`. */
operator fun UiText.Plural.Companion.invoke(@PluralsRes id: Int, count: Int, vararg args: Any): UiText.Plural = ofId(id, count, *args)

/** The string resource: its id, or the one its key names (`ScreenTextIds`, for text a shared model made). */
@get:StringRes
val UiText.Res.resId: Int get() = if (id != 0) id else ScreenTextIds.string(key)

@get:PluralsRes
val UiText.Plural.resId: Int get() = if (id != 0) id else ScreenTextIds.plural(key)

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Res -> stringResource(resId, *resolved(args))
    is UiText.Plural -> pluralStringResource(resId, count, *resolved(args))
    is UiText.Raw -> text
}

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Res -> context.getString(resId, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(resId, count, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())
    is UiText.Raw -> text
}

@Composable
private fun resolved(args: List<Any>): Array<Any> = args.map { if (it is UiText) it.resolve() else it }.toTypedArray()
