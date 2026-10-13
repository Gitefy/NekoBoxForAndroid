package io.nekohasekai.sagernet.database

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.junit.Assert.*
import org.junit.Test

class ProfileTestResultIsolationTest {
    @Test fun delayedTestResultKeepsLatestProfileEditsAndTraffic() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, SagerDatabase::class.java).build()
        try {
            val dao = database.proxyDao()
            val original = ProxyEntity(id = 20, groupId = 10).apply {
                putBean(SOCKSBean().apply { serverAddress = "192.0.2.1"; name = "original"; applyDefaultValues() })
            }
            dao.insert(listOf(original))
            val edited = dao.getById(20)!!.apply {
                socksBean!!.serverAddress = "192.0.2.2"
                socksBean!!.name = "edited"
                userOrder = 7
                tx = 123
                rx = 456
            }
            dao.updateProxy(edited)
            assertEquals(1, dao.updateTestResult(original.id, 1, 42, null))
            val saved = dao.getById(20)!!
            assertEquals("192.0.2.2", saved.socksBean!!.serverAddress)
            assertEquals("edited", saved.socksBean!!.name)
            assertEquals(7L, saved.userOrder)
            assertEquals(123L, saved.tx)
            assertEquals(456L, saved.rx)
            assertEquals(42, saved.ping)
            dao.deleteById(20)
            assertEquals(0, dao.updateTestResult(original.id, 1, 99, null))
            assertNull(dao.getById(20))
        } finally { database.close() }
    }
}
