package io.nekohasekai.sagernet.database

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRuleFactoryTest {
    @Test
    fun domainRequestCreatesExistingRuleEntityShape() {
        val draft = RequestRuleFactory.build(
            match = RequestRuleFactory.MatchKind.EXACT_DOMAIN,
            outboundKind = RequestRuleFactory.OutboundKind.PROXY,
            domain = "youtube.com",
            packageName = "com.android.chrome",
            destinationIp = "142.250.1.1",
            routerStableTag = "US",
            routersByStableTag = mapOf("US" to 9L),
        )!!
        val rule = draft.toRuleEntity()
        assertEquals("full:youtube.com", rule.domains)
        assertEquals(0L, rule.outbound)
        assertEquals(0L, rule.routerGroupId)
        assertTrue(rule.enabled)
    }

    @Test
    fun packageRequestCreatesAppRuleShape() {
        val rule = RequestRuleFactory.build(
            match = RequestRuleFactory.MatchKind.APP,
            outboundKind = RequestRuleFactory.OutboundKind.DIRECT,
            domain = "youtube.com",
            packageName = "com.android.chrome",
            destinationIp = "1.1.1.1",
            routerStableTag = "US",
            routersByStableTag = mapOf("US" to 9L),
        )!!.toRuleEntity()
        assertEquals(setOf("com.android.chrome"), rule.packages)
        assertEquals(-1L, rule.outbound)
        assertEquals("", rule.domains)
    }

    @Test
    fun ipRequestCreatesIpRuleShape() {
        val rule = RequestRuleFactory.build(
            match = RequestRuleFactory.MatchKind.DEST_IP,
            outboundKind = RequestRuleFactory.OutboundKind.REJECT,
            domain = "",
            packageName = "",
            destinationIp = "142.250.1.1",
            routerStableTag = "",
            routersByStableTag = emptyMap(),
        )!!.toRuleEntity()
        assertEquals("142.250.1.1", rule.ip)
        assertEquals(-2L, rule.outbound)
    }

    @Test
    fun routerDestinationUsesStableRouterIdentity() {
        val rule = RequestRuleFactory.build(
            match = RequestRuleFactory.MatchKind.EXACT_DOMAIN,
            outboundKind = RequestRuleFactory.OutboundKind.ROUTER,
            domain = "youtube.com",
            packageName = "",
            destinationIp = "",
            routerStableTag = "US",
            routersByStableTag = mapOf("US" to 42L, "SG" to 7L),
        )!!.toRuleEntity()
        assertEquals(42L, rule.routerGroupId)
        assertEquals(0L, rule.outbound)
    }
}

class RequestRuleApplyTest {
    @Test
    fun saveOnlySuccessPersistsOnceWithoutReload() = runBlocking {
        var persists = 0
        val result = RequestRuleApply.saveOnly(persist = { persists++; true })
        assertEquals(RequestRuleApply.Outcome.SAVED_NOT_APPLIED, result.outcome)
        assertTrue(result.persisted)
        assertEquals(1, persists)
    }

    @Test
    fun saveOnlyPersistFailureDoesNotReload() = runBlocking {
        val result = RequestRuleApply.saveOnly(persist = { false })
        assertEquals(RequestRuleApply.Outcome.PERSIST_FAILED, result.outcome)
        assertFalse(result.persisted)
    }

    @Test
    fun persistenceFailureDoesNotReload() = runBlocking {
        var reloads = 0
        val result = RequestRuleApply.saveAndApply(
            persist = { false },
            reload = { reloads++; true },
        )
        assertEquals(RequestRuleApply.Outcome.PERSIST_FAILED, result.outcome)
        assertFalse(result.persisted)
        assertEquals(0, reloads)
    }

    @Test
    fun persistenceSuccessTriggersExactlyOneReload() = runBlocking {
        var persists = 0
        var reloads = 0
        val result = RequestRuleApply.saveAndApply(
            persist = { persists++; true },
            reload = { reloads++; true },
        )
        assertEquals(RequestRuleApply.Outcome.APPLIED, result.outcome)
        assertTrue(result.persisted)
        assertEquals(1, persists)
        assertEquals(1, reloads)
    }
}
