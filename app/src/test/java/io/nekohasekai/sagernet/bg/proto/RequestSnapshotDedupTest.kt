package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.RequestFlowData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestSnapshotDedupTest {
    @Test
    fun identicalRawSnapshotSkipsParse() {
        val raw = """{"flows":[{"id":"a","uploadBytes":1}]}"""
        assertFalse(RequestSnapshotDedup.shouldSkipUnparsed(null, raw))
        assertTrue(RequestSnapshotDedup.shouldSkipUnparsed(raw, raw))
        assertFalse(RequestSnapshotDedup.shouldSkipUnparsed(raw, """{"flows":[]}"""))
    }

    @Test
    fun structuredFingerprintMatchesLegacyJoinStringSkipDecisions() {
        val first = listOf(
            flow("a", createdAt = 1, upload = 1, download = 2, closed = false, logical = "x", finalTag = "y"),
            flow("b", createdAt = 2, upload = 3, download = 4, closed = true, logical = "u", finalTag = "v"),
        )
        val same = listOf(
            flow("a", createdAt = 1, upload = 1, download = 2, closed = false, logical = "x", finalTag = "y"),
            flow("b", createdAt = 2, upload = 3, download = 4, closed = true, logical = "u", finalTag = "v"),
        )
        val bytesChanged = listOf(
            flow("a", createdAt = 1, upload = 9, download = 2, closed = false, logical = "x", finalTag = "y"),
            flow("b", createdAt = 2, upload = 3, download = 4, closed = true, logical = "u", finalTag = "v"),
        )
        val empty = emptyList<RequestFlowData>()
        val cases = listOf(empty, first, same, bytesChanged, listOf(flow("z")))
        for (previous in cases) {
            for (next in cases) {
                val sendLegacy =
                    RequestSnapshotDedup.legacyString(previous) != RequestSnapshotDedup.legacyString(next)
                val skipNew = RequestSnapshotDedup.shouldSkipPublish(
                    RequestSnapshotDedup.Fingerprint.listOf(previous),
                    RequestSnapshotDedup.Fingerprint.listOf(next),
                )
                assertEquals(sendLegacy, !skipNew)
            }
        }
        assertFalse(RequestSnapshotDedup.shouldSkipPublish(null, emptyList()))
        assertTrue(
            RequestSnapshotDedup.shouldSkipPublish(
                RequestSnapshotDedup.Fingerprint.listOf(first),
                RequestSnapshotDedup.Fingerprint.listOf(same),
            )
        )
        assertFalse(
            RequestSnapshotDedup.shouldSkipPublish(
                RequestSnapshotDedup.Fingerprint.listOf(first),
                RequestSnapshotDedup.Fingerprint.listOf(bytesChanged),
            )
        )
        assertFalse(
            RequestSnapshotDedup.shouldSkipPublish(
                emptyList(),
                RequestSnapshotDedup.Fingerprint.listOf(first),
            )
        )
        assertFalse(
            RequestSnapshotDedup.shouldSkipPublish(
                RequestSnapshotDedup.Fingerprint.listOf(first),
                emptyList(),
            )
        )
        assertFalse(
            RequestSnapshotDedup.shouldSkipPublish(
                RequestSnapshotDedup.Fingerprint.listOf(listOf(flow("a"), flow("b"))),
                RequestSnapshotDedup.Fingerprint.listOf(listOf(flow("b"), flow("a"))),
            )
        )
    }

    @Test
    fun unusedJsonFieldsDoNotEnterFingerprint() {
        val a = flow("a", domain = "one.example")
        val b = flow("a", domain = "two.example")
        assertEquals(
            RequestSnapshotDedup.Fingerprint.of(a),
            RequestSnapshotDedup.Fingerprint.of(b),
        )
        assertEquals(RequestSnapshotDedup.legacyString(listOf(a)), RequestSnapshotDedup.legacyString(listOf(b)))
    }

    @Test
    fun nullPreviousStateIsANewSessionAndNeverSkips() {
        val raw = """{"flows":[]}"""
        val fingerprint = RequestSnapshotDedup.Fingerprint.listOf(listOf(flow("a")))
        assertFalse(RequestSnapshotDedup.shouldSkipUnparsed(null, raw))
        assertFalse(RequestSnapshotDedup.shouldSkipUnparsed(null, raw))
        assertFalse(RequestSnapshotDedup.shouldSkipPublish(null, fingerprint))
        assertFalse(RequestSnapshotDedup.shouldSkipPublish(null, emptyList()))
    }

    @Test
    fun emptyFingerprintAfterResetPublishesThenSkips() {
        val empty = emptyList<RequestSnapshotDedup.Fingerprint>()
        assertFalse(RequestSnapshotDedup.shouldSkipPublish(null, empty))
        assertTrue(RequestSnapshotDedup.shouldSkipPublish(empty, empty))
        assertFalse(RequestSnapshotDedup.shouldSkipPublish(null, empty))
    }

    private fun flow(
        id: String,
        createdAt: Long = 0L,
        upload: Long = 0L,
        download: Long = 0L,
        closed: Boolean = false,
        logical: String = "",
        finalTag: String = "",
        domain: String = "",
    ) = RequestFlowData(
        id = id,
        createdAt = createdAt,
        uploadBytes = upload,
        downloadBytes = download,
        closed = closed,
        logicalOutbound = logical,
        finalOutboundTag = finalTag,
        domain = domain,
    )
}
