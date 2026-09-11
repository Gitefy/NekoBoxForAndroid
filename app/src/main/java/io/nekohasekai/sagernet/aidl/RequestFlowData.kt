package io.nekohasekai.sagernet.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RequestFlowData(
    var id: String = "",
    var createdAt: Long = 0L,
    var closedAt: Long = 0L,
    var closed: Boolean = false,
    var network: String = "",
    var uid: Int = 0,
    var packageName: String = "",
    var domain: String = "",
    var destinationAddress: String = "",
    var destinationPort: Int = 0,
    var originDestination: String = "",
    var matchedRuleText: String = "",
    var chain: String = "",
    var logicalOutbound: String = "",
    var finalOutboundTag: String = "",
    var uploadBytes: Long = 0L,
    var downloadBytes: Long = 0L,
    var routerStableTag: String = "",
    var routerName: String = "",
    var finalProfileName: String = "",
    var kind: String = "",
) : Parcelable
