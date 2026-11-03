package app.granger.device

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhoneCountryTest {
    @Test fun theNetworkWinsOverTheSimAndTheLocale() {
        // A Japanese phone set to US English, roaming on a Tanzanian network with a Japanese SIM.
        assertThat(PhoneCountry.pick(network = "tz", sim = "jp", locale = "US")).isEqualTo("TZ")
    }

    @Test fun theSimWhenThereIsNoNetwork() {
        assertThat(PhoneCountry.pick(network = "", sim = "tz", locale = "US")).isEqualTo("TZ")
        assertThat(PhoneCountry.pick(network = null, sim = "tz", locale = "US")).isEqualTo("TZ")
    }

    @Test fun theLocaleWithNoRadioOrSim() {
        assertThat(PhoneCountry.pick(network = null, sim = null, locale = "US")).isEqualTo("US")
    }

    @Test fun malformedCodesAreSkipped() {
        assertThat(PhoneCountry.pick(network = "310", sim = " ", locale = "es")).isEqualTo("ES")
        assertThat(PhoneCountry.pick(network = null, sim = null, locale = "")).isEqualTo("")
    }
}
