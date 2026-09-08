package io.nekohasekai.sagernet.database.preference

import androidx.preference.PreferenceDataStore

@Suppress("MemberVisibilityCanBePrivate", "unused")
open class RoomPreferenceDataStore(private val kvPairDao: KeyValuePair.Dao) :
    PreferenceDataStore() {

    // Read-through cache of the underlying key-value table. DataStore property reads
    // used to be one synchronous SQLite point query each (often several per UI event,
    // since PreferenceProxy also evaluates its default on every read). All routine
    // writes go through this class, so the cache is kept in sync on every put/remove;
    // direct DAO writers (settings restore, deprecated-key cleanup) must call
    // invalidateCache() afterwards.
    private val cache = HashMap<String, KeyValuePair?>()

    private fun cached(key: String): KeyValuePair? = synchronized(cache) {
        if (cache.containsKey(key)) cache[key]
        else kvPairDao[key].also { cache[key] = it }
    }

    private fun cachePut(key: String, value: KeyValuePair?) = synchronized(cache) {
        cache[key] = value
    }

    fun invalidateCache() = synchronized(cache) {
        cache.clear()
    }

    fun getBoolean(key: String) = cached(key)?.boolean
    fun getFloat(key: String) = cached(key)?.float
    fun getInt(key: String) = cached(key)?.long?.toInt()
    fun getLong(key: String) = cached(key)?.long
    fun getString(key: String) = cached(key)?.string
    fun getStringSet(key: String) = cached(key)?.stringSet
    fun reset(): Int {
        val count = kvPairDao.reset()
        invalidateCache()
        return count
    }

    override fun getBoolean(key: String, defValue: Boolean) = getBoolean(key) ?: defValue
    override fun getFloat(key: String, defValue: Float) = getFloat(key) ?: defValue
    override fun getInt(key: String, defValue: Int) = getInt(key) ?: defValue
    override fun getLong(key: String, defValue: Long) = getLong(key) ?: defValue
    override fun getString(key: String, defValue: String?) = getString(key) ?: defValue
    override fun getStringSet(key: String, defValue: MutableSet<String>?) =
        getStringSet(key) ?: defValue

    fun putBoolean(key: String, value: Boolean?) =
        if (value == null) remove(key) else putBoolean(key, value)

    fun putFloat(key: String, value: Float?) =
        if (value == null) remove(key) else putFloat(key, value)

    fun putInt(key: String, value: Int?) =
        if (value == null) remove(key) else putLong(key, value.toLong())

    fun putLong(key: String, value: Long?) = if (value == null) remove(key) else putLong(key, value)
    override fun putBoolean(key: String, value: Boolean) {
        val pair = KeyValuePair(key).put(value)
        kvPairDao.put(pair)
        cachePut(key, pair)
        fireChangeListener(key)
    }

    override fun putFloat(key: String, value: Float) {
        val pair = KeyValuePair(key).put(value)
        kvPairDao.put(pair)
        cachePut(key, pair)
        fireChangeListener(key)
    }

    override fun putInt(key: String, value: Int) {
        val pair = KeyValuePair(key).put(value.toLong())
        kvPairDao.put(pair)
        cachePut(key, pair)
        fireChangeListener(key)
    }

    override fun putLong(key: String, value: Long) {
        val pair = KeyValuePair(key).put(value)
        kvPairDao.put(pair)
        cachePut(key, pair)
        fireChangeListener(key)
    }

    override fun putString(key: String, value: String?) = if (value == null) remove(key) else {
        val pair = KeyValuePair(key).put(value)
        kvPairDao.put(pair)
        cachePut(key, pair)
        fireChangeListener(key)
    }

    override fun putStringSet(key: String, values: MutableSet<String>?) =
        if (values == null) remove(key) else {
            val pair = KeyValuePair(key).put(values)
            kvPairDao.put(pair)
            cachePut(key, pair)
            fireChangeListener(key)
        }

    fun remove(key: String) {
        kvPairDao.delete(key)
        cachePut(key, null)
        fireChangeListener(key)
    }

    private val listeners = HashSet<OnPreferenceDataStoreChangeListener>()
    private fun fireChangeListener(key: String) {
        val listeners = synchronized(listeners) {
            listeners.toList()
        }
        listeners.forEach { it.onPreferenceDataStoreChanged(this, key) }
    }

    fun registerChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun unregisterChangeListener(listener: OnPreferenceDataStoreChangeListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }
}
