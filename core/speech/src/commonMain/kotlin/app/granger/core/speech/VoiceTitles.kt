package app.granger.core.speech

/**
 * How voices are named in the lists, as format strings with `%1$s` and `%2$s`: Android reads them from the module's
 * resources (`VoiceTitles.of(context)`), the iPhone from its own words. [BARE], the names alone with no words around
 * them, is the default for code (mostly tests) that never shows a title.
 */
class VoiceTitles(private val granger: String, private val system: String, private val systemDefault: String) {
    /** "Granger voice (Maggie)". */
    fun granger(name: String): String = fill(granger, name)

    /** "System voice iom (en-US)", or "System voice default (en-US)" when [short] is null. */
    fun system(short: String?, languageTag: String): String =
        if (short == null) fill(systemDefault, languageTag) else fill(system, short, languageTag)

    companion object {
        val BARE = VoiceTitles("%1\$s", "%1\$s (%2\$s)", "(%1\$s)")

        /** [template]'s `%1$s`, `%2$s` (and a lone `%s`) filled in; `%%` is a percent sign, as `String.format` reads it. */
        internal fun fill(template: String, vararg args: String): String {
            val out = StringBuilder()
            var i = 0
            var next = 0
            while (i < template.length) {
                val c = template[i]
                if (c != '%' || i + 1 >= template.length) { out.append(c); i++; continue }
                if (template[i + 1] == '%') { out.append('%'); i += 2; continue }
                if (template[i + 1] == 's') { out.append(args.getOrElse(next++) { "" }); i += 2; continue }
                var j = i + 1
                while (j < template.length && template[j].isDigit()) j++
                if (j > i + 1 && j + 1 < template.length && template[j] == '$' && template[j + 1] == 's') {
                    out.append(args.getOrElse(template.substring(i + 1, j).toInt() - 1) { "" })
                    i = j + 2
                } else {
                    out.append(c); i++
                }
            }
            return out.toString()
        }
    }
}
