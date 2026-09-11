package io.nekohasekai.sagernet.aidl

object RequestFlowBinderBudget {
    const val BINDER_SAFE_BYTES = 900_000
    const val MAX_FLOWS = 300
    private const val PARCEL_STRING_OVERHEAD = 8
    private const val PER_ITEM_FIELDS = 20

    fun worstCasePayloadBytes(flowCount: Int = MAX_FLOWS): Int {
        val perString = intArrayOf(64, 8, 128, 128, 64, 64, 256, 256, 128, 128, 64, 64, 32)
        val strings = perString.sum() + perString.size * PARCEL_STRING_OVERHEAD
        val numbers = 48
        return flowCount * (strings + numbers + PER_ITEM_FIELDS * 4) + 256
    }

    fun isWithinBinderLimit(flowCount: Int = MAX_FLOWS): Boolean =
        worstCasePayloadBytes(flowCount) < BINDER_SAFE_BYTES
}
