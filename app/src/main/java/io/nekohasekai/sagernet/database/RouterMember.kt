package io.nekohasekai.sagernet.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.Serializable

@Entity(
    tableName = "router_members",
    primaryKeys = ["routerId", "proxyId"],
    indices = [Index("proxyId")]
)
data class RouterMember(
    var routerId: Long = 0L,
    var proxyId: Long = 0L,
    var userOrder: Long = 0L,
    var lastMatchedAt: Long = 0L
) : Serializable() {

    override fun initializeDefaultValues() {
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        output.writeInt(0)
        output.writeLong(routerId)
        output.writeLong(proxyId)
        output.writeLong(userOrder)
        output.writeLong(lastMatchedAt)
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        input.readInt()
        routerId = input.readLong()
        proxyId = input.readLong()
        userOrder = input.readLong()
        lastMatchedAt = input.readLong()
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM router_members ORDER BY routerId, userOrder, proxyId")
        fun all(): List<RouterMember>

        @Query("SELECT * FROM router_members WHERE routerId = :routerId ORDER BY userOrder, proxyId")
        fun getByRouter(routerId: Long): List<RouterMember>

        @Query("DELETE FROM router_members WHERE routerId = :routerId")
        fun deleteByRouter(routerId: Long): Int

        @Query("DELETE FROM router_members WHERE proxyId = :proxyId")
        fun deleteByProxy(proxyId: Long): Int

        @Insert
        fun insert(members: List<RouterMember>)

        @Transaction
        fun replaceMembers(routerId: Long, members: List<RouterMember>) {
            deleteByRouter(routerId)
            if (members.isNotEmpty()) {
                insert(members.map { it.copy(routerId = routerId) })
            }
        }

        @Query("DELETE FROM router_members")
        fun reset()
    }

    companion object {
        @JvmField
        val CREATOR = object : Serializable.CREATOR<RouterMember>() {

            override fun newInstance(): RouterMember {
                return RouterMember()
            }

            override fun newArray(size: Int): Array<RouterMember?> {
                return arrayOfNulls(size)
            }
        }
    }
}
