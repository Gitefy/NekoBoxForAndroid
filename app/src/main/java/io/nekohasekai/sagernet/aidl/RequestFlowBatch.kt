package io.nekohasekai.sagernet.aidl

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class RequestFlowBatch(
    val items: ArrayList<RequestFlowData> = arrayListOf(),
    val runtimeGeneration: Long = 0L,
) : Parcelable
