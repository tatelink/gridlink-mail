package app.gridlink.billing

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pills' order comes from Play's prices, never from the product ids. */
@Suppress("MagicNumber") // Prices in micros are the data under test here, not magic numbers.
class TipOrderTest {

    @Test
    fun cheapestFirstWhateverTheIdsSay() {
        val ordered = orderTips(
            listOf(
                TipOption("tip_small", "$5.00", 5_000_000),
                TipOption("tip_large", "$1.00", 1_000_000),
                TipOption("tip_medium", "$2.50", 2_500_000),
            ),
        )
        assertEquals(listOf("tip_large", "tip_medium", "tip_small"), ordered.map { it.productId })
    }

    @Test
    fun aProductWithoutAPriceIsDropped() {
        val ordered = orderTips(
            listOf(TipOption("tip_small", "", 0), TipOption("tip_medium", "2,00 EUR", 2_000_000)),
        )
        assertEquals(listOf("tip_medium"), ordered.map { it.productId })
    }

    @Test
    fun theProductIdsMatchPlayConsole() {
        assertEquals(listOf("tip_small", "tip_medium", "tip_large"), TIP_PRODUCT_IDS)
    }
}
