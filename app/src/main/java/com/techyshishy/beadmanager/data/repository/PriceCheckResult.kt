package com.techyshishy.beadmanager.data.repository

/**
 * Outcome of [CatalogRepository.checkAndUpdatePacks].
 *
 * [failedPackIds] contains the Room row IDs of packs whose live fetch threw an exception.
 * These packs retain their existing cached values in Room; their data should be presented
 * to the user with a "fetch failed" indicator rather than treated as confirmed-unavailable.
 */
data class PriceCheckResult(val failedPackIds: Set<Long>)
