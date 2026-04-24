package com.techyshishy.beadmanager.data.scraper

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class BobPackPriceFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: BobPackPriceFetcher

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetcher = BobPackPriceFetcher(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun productJson(
        price: String,
        inventoryQuantity: Int,
        inventoryPolicy: String,
    ) = """
        {
          "product": {
            "variants": [
              {
                "price": "$price",
                "inventory_quantity": $inventoryQuantity,
                "inventory_policy": "$inventoryPolicy"
              }
            ]
          }
        }
    """.trimIndent()

    private fun baseUrl() = server.url("/products/miyuki-delica-bead-11-0-db0001-gunmetal").toString()

    // MockWebServer appends ".json" to the URL path via BobPackPriceFetcher — the test
    // server receives a request to "<path>.json" and returns the enqueued response.

    @Test
    fun `fetch returns correct priceCents and available true when in stock`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(productJson(price = "3.15", inventoryQuantity = 50, inventoryPolicy = "deny")),
        )

        val result = fetcher.fetch(baseUrl())

        assertEquals(315, result.priceCents)
        assertTrue(result.available)
        assertNull("tier2 should be null for flat pricing", result.tier2PriceCents)
        assertNull("tier3 should be null for flat pricing", result.tier3PriceCents)
        assertNull("tier4 should be null for flat pricing", result.tier4PriceCents)
    }

    @Test
    fun `fetch marks pack unavailable when qty 0 and policy deny`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(productJson(price = "3.15", inventoryQuantity = 0, inventoryPolicy = "deny")),
        )

        val result = fetcher.fetch(baseUrl())

        assertFalse(result.available)
    }

    @Test
    fun `fetch marks pack available when qty 0 but policy continue`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(productJson(price = "3.15", inventoryQuantity = 0, inventoryPolicy = "continue")),
        )

        val result = fetcher.fetch(baseUrl())

        assertTrue(result.available)
    }

    @Test(expected = IOException::class)
    fun `fetch throws IOException on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        fetcher.fetch(baseUrl())
    }

    @Test(expected = IOException::class)
    fun `fetch throws on missing variants array`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"product": {"variants": []}}"""),
        )

        fetcher.fetch(baseUrl())
    }
}
