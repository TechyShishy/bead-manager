package com.techyshishy.beadmanager.data.firestore

/**
 * One chunk of a project's RGP bead grid, stored in the subcollection
 * `projects/{id}/grid/{chunkIndex}`.
 *
 * Storing rows in chunked subcollection documents instead of inline in [ProjectEntry] prevents
 * the Firestore local SQLite mutation overlay (`document_overlays.overlay_mutation`) from
 * exceeding Android's 2 MB CursorWindow limit, which causes a fatal `SQLiteBlobTooBigException`
 * when a snapshot listener tries to initialise against a large document.
 *
 * Each chunk holds at most [com.techyshishy.beadmanager.data.firestore.FirestoreProjectSource.GRID_CHUNK_SIZE]
 * rows. The chunk at index `i` stores rows `[i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE)`. Chunks are
 * written with document ID equal to the zero-based chunk index (stored as a string), so reading
 * all chunks in order reconstitutes the full row list.
 *
 * Default values are required for Firestore no-argument deserialization.
 */
data class ProjectGridChunk(
    val rows: List<ProjectRgpRow> = emptyList(),
)
