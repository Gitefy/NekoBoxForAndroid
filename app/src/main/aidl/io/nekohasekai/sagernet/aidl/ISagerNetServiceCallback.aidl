package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.SpeedDisplayData;
import io.nekohasekai.sagernet.aidl.TrafficDataBatch;
import io.nekohasekai.sagernet.aidl.RequestFlowBatch;

oneway interface ISagerNetServiceCallback {
  void stateChanged(int state, String profileName, String msg);
  void missingPlugin(String profileName, String pluginName);
  void cbSpeedUpdate(in SpeedDisplayData stats);
  void cbTrafficUpdate(in TrafficDataBatch stats);
  void cbSelectorUpdate(long id);
  void cbRequestUpdate(in RequestFlowBatch stats);
  void commandResult(String requestId, int outcome, long instanceGeneration, boolean persisted, String errorCode);
}
