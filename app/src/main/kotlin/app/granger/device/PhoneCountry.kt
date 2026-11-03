package app.granger.device

import android.content.Context
import android.telephony.TelephonyManager

/**
 * Where the phone is, for the suggested map (Plan E1 item 1): the network's country, then the SIM's, then the phone's
 * locale. A phone bought abroad keeps its locale (a Japanese docomo phone set to US English in Tanzania reads "US"), so the
 * locale comes last. Each is a two-letter ISO code; blanks and anything else are skipped.
 */
object PhoneCountry {
    fun pick(network: String?, sim: String?, locale: String?): String =
        listOf(network, sim, locale).map { it?.trim().orEmpty() }
            .firstOrNull { it.length == 2 && it.all(Char::isLetter) }?.uppercase().orEmpty()

    fun of(context: Context): String {
        val telephony = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
        // Neither needs a permission; a phone with no radio or no SIM returns blanks.
        val network = runCatching { telephony?.networkCountryIso }.getOrNull()
        val sim = runCatching { telephony?.simCountryIso }.getOrNull()
        return pick(network, sim, java.util.Locale.getDefault().country)
    }
}
