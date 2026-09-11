package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch

interface CallbackWithCommandResult : SagerConnection.Callback {
    fun onCommandResult(requestId: String, outcome: CommandOutcome, instanceGeneration: Long, persisted: Boolean, errorCode: String?)
}
