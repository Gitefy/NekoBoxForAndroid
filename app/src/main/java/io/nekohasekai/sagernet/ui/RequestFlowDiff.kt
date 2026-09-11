package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.aidl.RequestFlowData

object RequestFlowDiff {
    fun sameItem(oldItem: RequestFlowData, newItem: RequestFlowData): Boolean {
        return oldItem.id == newItem.id && oldItem.createdAt == newItem.createdAt
    }

    fun sameContent(oldItem: RequestFlowData, newItem: RequestFlowData): Boolean {
        return oldItem == newItem
    }

    fun statsOnly(oldItem: RequestFlowData, newItem: RequestFlowData): Boolean {
        if (!sameItem(oldItem, newItem)) return false
        return oldItem.copy(
            uploadBytes = newItem.uploadBytes,
            downloadBytes = newItem.downloadBytes,
            closed = newItem.closed,
            closedAt = newItem.closedAt,
        ) == newItem
    }
}
