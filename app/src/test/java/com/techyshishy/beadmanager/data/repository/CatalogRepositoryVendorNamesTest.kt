package com.techyshishy.beadmanager.data.repository

import com.techyshishy.beadmanager.data.db.VendorLinkDao
import com.techyshishy.beadmanager.data.db.VendorLinkEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogRepositoryVendorNamesTest {

    private val vendorLinkDao = mockk<VendorLinkDao>()
    private val repository = CatalogRepository(
        beadDao = mockk(relaxed = true),
        vendorLinkDao = vendorLinkDao,
        vendorPackDao = mockk(relaxed = true),
        vendorPackPriceFetcher = mockk(relaxed = true),
    )

    @Test
    fun `vendorNamesForBeads returns empty map immediately for empty input`() = runTest {
        val result = repository.vendorNamesForBeads(emptyList())

        assertTrue(result.isEmpty())
        // DAO must not be called — avoids SQLite zero-argument IN error on some API levels.
        coVerify(exactly = 0) { vendorLinkDao.linksForBeads(any()) }
    }

    @Test
    fun `vendorNamesForBeads maps beadCode+vendorKey to beadName`() = runTest {
        coEvery { vendorLinkDao.linksForBeads(listOf("DB0001", "DB0002")) } returns listOf(
            VendorLinkEntity(beadCode = "DB0001", vendorKey = "fmg", displayName = "FMG", url = "", beadName = "Gunmetal"),
            VendorLinkEntity(beadCode = "DB0001", vendorKey = "ac",  displayName = "AC",  url = "", beadName = "opaque metallic gunmetal"),
            VendorLinkEntity(beadCode = "DB0002", vendorKey = "fmg", displayName = "FMG", url = "", beadName = "Gold"),
        )

        val result = repository.vendorNamesForBeads(listOf("DB0001", "DB0002"))

        assertEquals("Gunmetal", result["DB0001" to "fmg"])
        assertEquals("opaque metallic gunmetal", result["DB0001" to "ac"])
        assertEquals("Gold", result["DB0002" to "fmg"])
    }

    @Test
    fun `vendorNamesForBeads excludes entries with null or blank beadName`() = runTest {
        coEvery { vendorLinkDao.linksForBeads(any()) } returns listOf(
            VendorLinkEntity(beadCode = "DB0001", vendorKey = "fmg", displayName = "FMG", url = "", beadName = null),
            VendorLinkEntity(beadCode = "DB0001", vendorKey = "ac",  displayName = "AC",  url = "", beadName = "  "),
            VendorLinkEntity(beadCode = "DB0001", vendorKey = "xyz", displayName = "X",   url = "", beadName = "Cobalt"),
        )

        val result = repository.vendorNamesForBeads(listOf("DB0001"))

        assertEquals(1, result.size)
        assertEquals("Cobalt", result["DB0001" to "xyz"])
    }
}
