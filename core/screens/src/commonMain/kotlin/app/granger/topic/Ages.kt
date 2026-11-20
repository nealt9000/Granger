package app.granger.topic

/** Age at death from birth and death years and month×100+day (0 when unknown): Napoleon, 15 Aug 1769 – 5 May 1821, is 51. */
fun ageOf(y0: Long, md0: Int, y1: Long, md1: Int): Int {
    // Years are astronomical (1 BC = 0), so the difference is right across the era.
    val years = (y1 - y0).toInt()
    return if (md0 != 0 && md1 != 0 && md1 < md0) years - 1 else years
}
