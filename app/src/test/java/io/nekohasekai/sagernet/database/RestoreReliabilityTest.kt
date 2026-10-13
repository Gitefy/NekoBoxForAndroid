package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import java.net.InetAddress
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RestoreReliabilityTest {
    @Test fun interruptedPreparationMustRemainRecoverable() {
        val dir = Files.createTempDirectory("audit3-prep").toFile()
        try {
            // Force the second snapshot write to fail, before any live DB mutation.
            RestoreCoordinator.sagerSnapshotFile(dir).mkdir()
            var mutated = false
            val failure = runCatching {
                RestoreCoordinator.commit(dir, emptyList(), { emptyList() },
                    { "{\"groups\":[],\"profiles\":[],\"rules\":[]}".toByteArray() },
                    { mutated = true; true }, { mutated = true; true }, { true })
            }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(mutated)
            // Remove only the injected obstacle, as if storage became writable again.
            RestoreCoordinator.sagerSnapshotFile(dir).delete()
            val out = RestoreCoordinator.recoverLocked(dir, { true }, { true })
            assertTrue("Pre-commit I/O failure must not permanently block all future starts", out.success)
        } finally { dir.deleteRecursively() }
    }

    @Test fun interruptedSuccessfulCleanupMustRemainRecoverable() {
        val dir = Files.createTempDirectory("audit3-cleanup").toFile()
        try {
            RestoreCoordinator.snapshotFile(dir).writeBytes(RestoreCoordinator.encodeRows(emptyList()))
            RestoreCoordinator.sagerSnapshotFile(dir).writeText("{\"groups\":[],\"profiles\":[],\"rules\":[]}")
            RestoreCoordinator.journalFile(dir).writeText("COMPLETE")
            // Cleanup removes snapshots first, retaining COMPLETE until deletion is durable.
            RestoreCoordinator.snapshotFile(dir).delete()
            val out = RestoreCoordinator.recoverLocked(dir, { true }, { true })
            assertTrue("Completed restore must survive interruption during cleanup", out.success)
        } finally { dir.deleteRecursively() }
    }

    @Test fun metadataWriteFailureMustReleaseAcquiredLock() {
        val dir = Files.createTempDirectory("audit3-lock").toFile()
        try {
            RestoreCoordinator.lockMetaFile(dir).mkdir()
            val failure = runCatching { RestoreCoordinator.tryExclusivePermit(dir) }.exceptionOrNull()
            assertNotNull(failure)
            RestoreCoordinator.lockMetaFile(dir).delete()
            val retry = RestoreCoordinator.tryExclusivePermit(dir)
            retry?.close()
            assertNotNull("Failure after tryLock must release it so retry can proceed", retry)
        } finally { dir.deleteRecursively() }
    }

    @Test fun forceResolveTuicMustPreserveTlsHostname() {
        val updater = object : GroupUpdater() {
            override suspend fun doUpdate(proxyGroup: ProxyGroup, subscription: SubscriptionBean,
                userInterface: GroupManager.Interface?, byUser: Boolean) = Unit
            fun rewrite(bean: AbstractBean) = rewriteAddress(bean, listOf(InetAddress.getByName("192.0.2.1")), false)
        }
        for (explicit in listOf("", "tls.example.com")) {
            val beans = listOf(TuicBean(), AnyTLSBean(), JuicityBean(), VMessBean())
            for (bean in beans) {
                bean.initializeDefaultValues()
                bean.serverAddress = "node.example.com"
                when (bean) {
                    is TuicBean -> bean.sni = explicit
                    is AnyTLSBean -> bean.sni = explicit
                    is JuicityBean -> bean.sni = explicit
                    is VMessBean -> { bean.security = "reality"; bean.sni = explicit }
                }
                updater.rewrite(bean)
                assertEquals("192.0.2.1", bean.serverAddress)
                val sni = when (bean) {
                    is TuicBean -> bean.sni
                    is AnyTLSBean -> bean.sni
                    is JuicityBean -> bean.sni
                    is VMessBean -> bean.sni
                    else -> error("unexpected bean")
                }
                assertEquals(explicit.ifEmpty { "node.example.com" }, sni)
            }
        }
    }
    @Test fun cancelledAcquireMustReleaseUndeliveredPermit() = runBlocking {
        val dir = Files.createTempDirectory("audit3-cancel-lock").toFile()
        val acquired = CountDownLatch(1)
        val allowReturn = CountDownLatch(1)
        try {
            RestoreCoordinator.lockSink = { event ->
                if (event.op == "acquired") {
                    acquired.countDown()
                    check(allowReturn.await(3, TimeUnit.SECONDS))
                }
            }
            val job = launch(Dispatchers.Default) {
                RestoreCoordinator.acquirePermit(dir, RestoreCoordinator.LockKind.APPLY) { false }.permit?.close()
            }
            assertTrue(acquired.await(3, TimeUnit.SECONDS))
            job.cancel()
            allowReturn.countDown()
            job.join()
            RestoreCoordinator.lockSink = null
            val retry = RestoreCoordinator.tryExclusivePermit(dir)
            retry?.close()
            assertNotNull("Cancellation must not discard an owned file lock", retry)
        } finally {
            allowReturn.countDown()
            RestoreCoordinator.lockSink = null
            dir.deleteRecursively()
        }
    }

    @Test fun doubleCloseMustNotClearNextHolder() {
        val dir = Files.createTempDirectory("restore-double-close").toFile()
        try {
            val first = RestoreCoordinator.tryExclusivePermit(dir)!!
            first.close()
            val next = RestoreCoordinator.tryExclusivePermit(dir, RestoreCoordinator.LockKind.USER_RESTORE)!!
            try {
                first.close()
                assertEquals(RestoreCoordinator.LockKind.USER_RESTORE, RestoreCoordinator.readHolderKind(dir))
                assertTrue(RestoreCoordinator.isActive())
            } finally { next.close() }
        } finally { dir.deleteRecursively() }
    }

    @Test fun failedCleanupRetainsTerminalMarkerAndRetries() {
        val dir = Files.createTempDirectory("restore-cleanup-retry").toFile()
        try {
            RestoreCoordinator.journalFile(dir).writeText("COMPLETE")
            val obstacle = RestoreCoordinator.sagerSnapshotFile(dir)
            obstacle.mkdir()
            java.io.File(obstacle, "busy").writeText("busy")
            assertFalse(RestoreCoordinator.recoverLocked(dir, { error("must not roll back") }, { error("must not roll back") }).success)
            assertEquals(RestoreCoordinator.Phase.COMPLETE, RestoreCoordinator.readPhase(dir))
            obstacle.deleteRecursively()
            assertTrue(RestoreCoordinator.recoverLocked(dir, { false }, { false }).success)
        } finally { dir.deleteRecursively() }
    }
}
