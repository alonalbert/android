/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.layoutinspector.pipeline.foregroundprocessdetection

import com.android.ddmlib.testing.FakeAdbRule
import com.android.testutils.MockitoKt.mock
import com.android.tools.adtui.model.FakeTimer
import com.android.tools.idea.appinspection.api.process.ProcessesModel
import com.android.tools.idea.appinspection.inspector.api.process.DeviceDescriptor
import com.android.tools.idea.appinspection.internal.process.toDeviceDescriptor
import com.android.tools.idea.appinspection.test.TestProcessDiscovery
import com.android.tools.idea.concurrency.AndroidCoroutineScope
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.layoutinspector.AdbServiceRule
import com.android.tools.idea.layoutinspector.createProcess
import com.android.tools.idea.layoutinspector.metrics.LayoutInspectorMetrics
import com.android.tools.idea.layoutinspector.pipeline.adb.AdbDebugViewProperties
import com.android.tools.idea.layoutinspector.pipeline.adb.FakeShellCommandHandler
import com.android.tools.idea.layoutinspector.pipeline.appinspection.DebugViewAttributes
import com.android.tools.idea.transport.TransportClient
import com.android.tools.idea.transport.faketransport.FakeGrpcServer
import com.android.tools.idea.transport.faketransport.FakeTransportService
import com.android.tools.idea.transport.faketransport.commands.CommandHandler
import com.android.tools.profiler.proto.Commands.Command
import com.android.tools.profiler.proto.Common
import com.google.common.truth.Truth.assertThat
import com.google.wireless.android.sdk.stats.DynamicLayoutInspectorTransportError
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.DisposableRule
import com.intellij.testFramework.ProjectRule
import com.intellij.util.containers.reverse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import layout_inspector.LayoutInspector
import layout_inspector.LayoutInspector.TrackingForegroundProcessSupported.SupportType
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.fail

class ForegroundProcessDetectionTest {
  @get:Rule
  val disposableRule = DisposableRule()

  private val projectRule = ProjectRule()

  private val adbRule = FakeAdbRule()
  private val adbProperties: AdbDebugViewProperties = FakeShellCommandHandler().apply {
    adbRule.withDeviceCommandHandler(this)
  }
  private val adbService = AdbServiceRule(projectRule::project, adbRule)

  @get:Rule
  val ruleChain: RuleChain = RuleChain.outerRule(projectRule)
    .around(adbRule)
    .around(adbService)

  private val timer = FakeTimer()
  private val transportService = FakeTransportService(timer, false)

  @get:Rule
  val grpcServerRule = FakeGrpcServer.createFakeGrpcServer("ForegroundProcessDetectionTest", transportService)

  private val timestampGenerator = AtomicLong()

  private val device1 = Common.Device.newBuilder()
    .setDeviceId(1)
    .setManufacturer("man1")
    .setModel("mod1")
    .setSerial("serial1")
    .setIsEmulator(false)
    .setApiLevel(1)
    .setVersion("version1")
    .setCodename("codename1")
    .setState(Common.Device.State.ONLINE)
    .build()

  private val device2 = Common.Device.newBuilder()
    .setDeviceId(2)
    .setManufacturer("man2")
    .setModel("mod2")
    .setSerial("serial2")
    .setIsEmulator(false)
    .setApiLevel(2)
    .setVersion("version2")
    .setCodename("codename2")
    .setState(Common.Device.State.ONLINE)
    .build()

  private val device3 = Common.Device.newBuilder()
    .setDeviceId(3)
    .setManufacturer("man3")
    .setModel("mod3")
    .setSerial("serial3")
    .setIsEmulator(false)
    .setApiLevel(3)
    .setVersion("version3")
    .setCodename("codename3")
    .setState(Common.Device.State.ONLINE)
    .build()

  private val device4 = Common.Device.newBuilder()
    .setDeviceId(4)
    .setManufacturer("man4")
    .setModel("mod4")
    .setSerial("serial4")
    .setIsEmulator(false)
    .setApiLevel(4)
    .setVersion("version4")
    .setCodename("codename4")
    .setState(Common.Device.State.ONLINE)
    .build()

  private val deviceToStreamMap = mapOf(
    device1 to createFakeStream(1, device1),
    device2 to createFakeStream(2, device2),
    device3 to createFakeStream(3, device3),
    device4 to createFakeStream(4, device4),
  )

  private val deviceToHandshakeSupportTypeMap = mutableMapOf(
    device1 to SupportType.SUPPORTED,
    device2 to SupportType.SUPPORTED,
    device3 to SupportType.NOT_SUPPORTED,
    device4 to SupportType.UNKNOWN,
  )

  private lateinit var transportClient: TransportClient
  private lateinit var workDispatcher: CoroutineDispatcher

  // channels used to synchronize tests that send commands
  private lateinit var handshakeSyncChannel: Channel<Pair<Common.Device, SupportType>>
  private lateinit var startTrackingSyncChannel: Channel<Common.Device>
  private lateinit var stopTrackingSyncChannel: Channel<Common.Device>

  private lateinit var coroutineScope: CoroutineScope

  @Before
  fun setUp() {
    handshakeSyncChannel = Channel()
    startTrackingSyncChannel = Channel()
    stopTrackingSyncChannel = Channel()

    workDispatcher = AndroidDispatchers.workerThread
    transportClient = TransportClient(grpcServerRule.name)

    coroutineScope = AndroidCoroutineScope(disposableRule.disposable)

    // mock device response to handshake command
    transportService.setCommandHandler(Command.CommandType.IS_TRACKING_FOREGROUND_PROCESS_SUPPORTED) { command ->
      val stream = getStreamFromCommand(command)
      val device = getDeviceFromCommand(command)
      val supportType = deviceToHandshakeSupportTypeMap[device]!!

      val event = buildForegroundProcessSupportedEvent(supportType)

      // IS_TRACKING_FOREGROUND_PROCESS_SUPPORTED expects a response from the device,
      // informing Studio of what is the device's support type for foreground process detection.
      sendEvent(stream, event)
      coroutineScope.launch { handshakeSyncChannel.send(device to supportType) }
    }

    // mock device response to start tracking command
    transportService.setCommandHandler(Command.CommandType.START_TRACKING_FOREGROUND_PROCESS) { command ->
      val device = getDeviceFromCommand(command)
      coroutineScope.launch { startTrackingSyncChannel.send(device) }
    }

    // mock device response to stop tracking command
    transportService.setCommandHandler(Command.CommandType.STOP_TRACKING_FOREGROUND_PROCESS) { command ->
      val device = getDeviceFromCommand(command)
      coroutineScope.launch { stopTrackingSyncChannel.send(device) }
    }

    adbRule.bridge.devices.forEach {
      adbRule.disconnectDevice(it.serialNumber)
    }
  }

  @After
  fun tearDown() {
    handshakeSyncChannel.close()
    startTrackingSyncChannel.close()
    stopTrackingSyncChannel.close()

    transportClient.shutdown()
    DebugViewAttributes.reset()
  }

  @Test
  fun testReceiveForegroundProcessesDevice(): Unit = runBlocking {
    val (deviceModel, processModel) = createDeviceModel(device1)
    val foregroundProcessDetection = ForegroundProcessDetection(
      projectRule.project,
      deviceModel,
      processModel,
      transportClient,
      mock(),
      mock(),
      coroutineScope,
      workDispatcher,
      onDeviceDisconnected = {},
      pollingIntervalMs = 500L
    )

    val foregroundProcessSyncChannel = Channel<NewForegroundProcess>()
    foregroundProcessDetection.foregroundProcessListeners.add(ForegroundProcessListener { device, foregroundProcess ->
      coroutineScope.launch { foregroundProcessSyncChannel.send(NewForegroundProcess(device, foregroundProcess)) }
    })

    connectDevice(device1)
    val (handshakeDevice, supportType) = handshakeSyncChannel.receive()
    val startTrackingDevice = startTrackingSyncChannel.receive()

    assertThat(handshakeDevice).isEqualTo(device1)
    assertThat(supportType).isEqualTo(SupportType.SUPPORTED)

    assertThat(startTrackingDevice).isEqualTo(device1)

    sendForegroundProcessEvent(device1, ForegroundProcess(1, "process1"))
    val received1 = foregroundProcessSyncChannel.receive()

    sendForegroundProcessEvent(device1, ForegroundProcess(2, "process2"))
    val received2 = foregroundProcessSyncChannel.receive()

    foregroundProcessDetection.stopPollingSelectedDevice()
    val stopTrackingDevice = stopTrackingSyncChannel.receive()

    assertThat(stopTrackingDevice).isEqualTo(device1)

    assertEqual(received1, device1, ForegroundProcess(1, "process1"))
    assertEqual(received2, device1, ForegroundProcess(2, "process2"))
  }

  @Test
  @Ignore
  // TODO re-enable
  fun testReceiveMultipleInstancesOfStudio(): Unit = runBlocking {
    val (deviceModel1, processModel1) = createDeviceModel(device1)
    val (deviceModel2, processModel2) = createDeviceModel(device1)

    val coroutineScope1 = AndroidCoroutineScope(disposableRule.disposable)
    val coroutineScope2 = AndroidCoroutineScope(disposableRule.disposable)

    // studio1
    val foregroundProcessDetection1 = ForegroundProcessDetection(
      projectRule.project,
      deviceModel1,
      processModel1,
      transportClient,
      mock(),
      mock(),
      coroutineScope1,
      workDispatcher,
      onDeviceDisconnected = {},
      pollingIntervalMs = 500L
    )

    // studio2
    val foregroundProcessDetection2 = ForegroundProcessDetection(
      projectRule.project,
      deviceModel2,
      processModel2,
      transportClient,
      mock(),
      mock(),
      coroutineScope2,
      workDispatcher,
      onDeviceDisconnected = {},
      pollingIntervalMs = 500L
    )

    val foregroundProcessSyncChannel = Channel<NewForegroundProcess>()
    foregroundProcessDetection1.foregroundProcessListeners.add(ForegroundProcessListener { device, foregroundProcess ->
      coroutineScope.launch { foregroundProcessSyncChannel.send(NewForegroundProcess(device, foregroundProcess)) }
    })

    connectDevice(device1)
    val (handshakeDevice1, supportType1) = handshakeSyncChannel.receive()
    val startTrackingDevice1 = startTrackingSyncChannel.receive()

    // we expect both instances of Studio to receive the "device connected" event and to initiate the handshake
    val (handshakeDevice2, supportType2) = handshakeSyncChannel.receive()
    val startTrackingDevice2 = startTrackingSyncChannel.receive()

    withTimeoutOrNull<Nothing>(500) {
      handshakeSyncChannel.receive()
      fail()
    }

    withTimeoutOrNull<Nothing>(500) {
      startTrackingSyncChannel.receive()
      fail()
    }

    assertThat(handshakeDevice1).isEqualTo(device1)
    assertThat(supportType1).isEqualTo(SupportType.SUPPORTED)
    assertThat(startTrackingDevice1).isEqualTo(device1)

    assertThat(handshakeDevice2).isEqualTo(device1)
    assertThat(supportType2).isEqualTo(SupportType.SUPPORTED)
    assertThat(startTrackingDevice2).isEqualTo(device1)

    foregroundProcessDetection1.stopPollingSelectedDevice()
    val stopTrackingDevice1 = stopTrackingSyncChannel.receive()

    foregroundProcessDetection2.stopPollingSelectedDevice()
    val stopTrackingDevice2 = stopTrackingSyncChannel.receive()

    assertThat(stopTrackingDevice1).isEqualTo(device1)
    assertThat(stopTrackingDevice2).isEqualTo(device1)
  }

  @Test
  fun testReceiveForegroundProcessesFromSelectedDevice(): Unit = runBlocking {
    val (deviceModel, processModel) = createDeviceModel(device1, device2)
    val foregroundProcessDetection = ForegroundProcessDetection(
      projectRule.project,
      deviceModel,
      processModel,
      transportClient,
      mock(),
      mock(),
      coroutineScope,
      workDispatcher,
      onDeviceDisconnected = {},
      pollingIntervalMs = 500L
    )

    val foregroundProcessSyncChannel = Channel<NewForegroundProcess>()
    foregroundProcessDetection.foregroundProcessListeners.add(ForegroundProcessListener { device, foregroundProcess ->
      coroutineScope.launch { foregroundProcessSyncChannel.send(NewForegroundProcess(device, foregroundProcess)) }
    })

    connectDevice(device1)
    handshakeSyncChannel.receive()
    startTrackingSyncChannel.receive()

    connectDevice(device2)
    handshakeSyncChannel.receive()
    // we should not start polling this device
    withTimeoutOrNull<Nothing>(500) {
      startTrackingSyncChannel.receive()
      fail()
    }

    sendForegroundProcessEvent(device1, ForegroundProcess(1, "process1"))
    val received1 = foregroundProcessSyncChannel.receive()

    foregroundProcessDetection.startPollingDevice(device2.toDeviceDescriptor())

    sendForegroundProcessEvent(device2, ForegroundProcess(2, "process2"))
    val received2 = foregroundProcessSyncChannel.receive()

    sendForegroundProcessEvent(device2, ForegroundProcess(3, "process3"))
    val received3 = foregroundProcessSyncChannel.receive()

    foregroundProcessDetection.stopPollingSelectedDevice()
    stopTrackingSyncChannel.receive()

    assertEqual(received1, device1, ForegroundProcess(1, "process1"))
    assertEqual(received2, device2, ForegroundProcess(2, "process2"))
    assertEqual(received3, device2, ForegroundProcess(3, "process3"))
  }

  @Test
  fun testHandshakeDeviceIsNotSupported(): Unit = runBlocking {
    val (deviceModel, processModel) = createDeviceModel(device3)
    val foregroundProcessDetection = ForegroundProcessDetection(
      projectRule.project,
      deviceModel,
      processModel,
      transportClient,
      mock(),
      mock(),
      coroutineScope,
      workDispatcher,
      onDeviceDisconnected = {},
      pollingIntervalMs = 500L
    )

    val foregroundProcessSyncChannel = Channel<NewForegroundProcess>()
    foregroundProcessDetection.foregroundProcessListeners.add(ForegroundProcessListener { device, foregroundProcess ->
      coroutineScope.launch { foregroundProcessSyncChannel.send(NewForegroundProcess(device, foregroundProcess)) }
    })

    connectDevice(device3)
    val (connectedDevice, supportType) = handshakeSyncChannel.receive()
    // tracking is never initiated, because device3 does not support foreground process tracking
    withTimeoutOrNull<Nothing>(500) {
      startTrackingSyncChannel.receive()
      fail()
    }

    assertThat(connectedDevice).isEqualTo(device3)
    assertThat(supportType).isEqualTo(SupportType.NOT_SUPPORTED)

    foregroundProcessDetection.stopPollingSelectedDevice()
    // stop tracking should never be sent, becase device3 does not support foreground process tracking
    withTimeoutOrNull<Nothing>(500) {
      stopTrackingSyncChannel.receive()
      fail()
    }
  }

  @Test
  fun testStopPollingSelectedDevice() = runBlocking {
    val (deviceModel, processModel) = createDeviceModel(device1, device2)
    val foregroundProcessDetection = ForegroundProcessDetection(
      projectRule.project,
      deviceModel,
      processModel,
      transportClient,
      mock(),
      mock(),
      coroutineScope,
      workDispatcher,
      onDeviceDisconnected = {},
      pollingIntervalMs = 500L
    )

    val foregroundProcessSyncChannel = Channel<NewForegroundProcess>()
    foregroundProcessDetection.foregroundProcessListeners.add(ForegroundProcessListener { device, foregroundProcess ->
      coroutineScope.launch { foregroundProcessSyncChannel.send(NewForegroundProcess(device, foregroundProcess)) }
    })

    val selectedDeviceSyncChannel = Channel<DeviceDescriptor?>()
    deviceModel.newSelectedDeviceListeners.add { device ->
      coroutineScope.launch { selectedDeviceSyncChannel.send(device) }
    }

    connectDevice(device1)
    val (handshakeDevice1, supportType1) = handshakeSyncChannel.receive()
    val startTrackingDevice0 = startTrackingSyncChannel.receive()
    // also wait for selected device to avoid race conditions when connecting device2
    val selectedDevice0 = selectedDeviceSyncChannel.receive()

    assertThat(handshakeDevice1).isEqualTo(device1)
    assertThat(supportType1).isEqualTo(SupportType.SUPPORTED)
    assertThat(startTrackingDevice0).isEqualTo(device1)
    assertThat(selectedDevice0).isEqualTo(device1.toDeviceDescriptor())

    connectDevice(device2)
    val (handshakeDevice2, supportType2) = handshakeSyncChannel.receive()

    assertThat(handshakeDevice2).isEqualTo(device2)
    assertThat(supportType2).isEqualTo(SupportType.SUPPORTED)

    // we should not start polling this device, because we're already polling device1
    withTimeoutOrNull<Nothing>(500) {
      val unexpectedDevice = startTrackingSyncChannel.receive()
      fail("Unexpectedly started tracking device \"${unexpectedDevice.deviceId}\"")
    }
    withTimeoutOrNull<Nothing>(500) {
      val unexpectedDevice = selectedDeviceSyncChannel.receive()
      fail("Unexpectedly started tracking device \"${unexpectedDevice?.serial}\"")
    }

    sendForegroundProcessEvent(device1, ForegroundProcess(1, "process1"))
    val received1 = foregroundProcessSyncChannel.receive()

    foregroundProcessDetection.startPollingDevice(device2.toDeviceDescriptor())
    val stopTrackingDevice1 = stopTrackingSyncChannel.receive()
    val startTrackingDevice1 = startTrackingSyncChannel.receive()

    assertThat(stopTrackingDevice1).isEqualTo(device1)
    assertThat(startTrackingDevice1).isEqualTo(device2)

    sendForegroundProcessEvent(device2, ForegroundProcess(2, "process2"))
    val received2 = foregroundProcessSyncChannel.receive()

    sendForegroundProcessEvent(device2, ForegroundProcess(3, "process3"))
    val received3 = foregroundProcessSyncChannel.receive()

    foregroundProcessDetection.startPollingDevice(device1.toDeviceDescriptor())
    val stopTrackingDevice2 = stopTrackingSyncChannel.receive()
    val startTrackingDevice2 = startTrackingSyncChannel.receive()

    assertThat(stopTrackingDevice2).isEqualTo(device2)
    assertThat(startTrackingDevice2).isEqualTo(device1)

    foregroundProcessDetection.stopPollingSelectedDevice()
   val stopTrackingDevice3 = stopTrackingSyncChannel.receive()

    assertThat(stopTrackingDevice3).isEqualTo(device1)

    assertEqual(received1, device1, ForegroundProcess(1, "process1"))
    assertEqual(received2, device2, ForegroundProcess(2, "process2"))
    assertEqual(received3, device2, ForegroundProcess(3, "process3"))
  }

  @Test
  fun testDeviceViewAttributeResetAfterDeviceDisconnect() = runBlockingWithFlagState(true) {
    val onDeviceDisconnectedSyncChannel = Channel<DeviceDescriptor>()
    val onDeviceDisconnected: (DeviceDescriptor) -> Unit = {
      coroutineScope.launch { onDeviceDisconnectedSyncChannel.send(it) }
    }

    val (deviceModel, processModel) = createDeviceModel(device1)
    ForegroundProcessDetection(
      projectRule.project,
      deviceModel,
      processModel,
      transportClient,
      mock(),
      mock(),
      coroutineScope,
      workDispatcher,
      onDeviceDisconnected,
      pollingIntervalMs = 500L
    )

    connectDevice(device1)
    handshakeSyncChannel.receive()
    startTrackingSyncChannel.receive()

    val changed = DebugViewAttributes.getInstance().set(projectRule.project, device1.toDeviceDescriptor().createProcess("fakeprocess"))
    assertThat(changed).isTrue()

    disconnectDevice(device1)
    onDeviceDisconnectedSyncChannel.receive()

    assertThat(adbProperties.debugViewAttributesChangesCount).isEqualTo(2)
    assertThat(adbProperties.debugViewAttributes).isNull()
  }

  @Test
  fun testStopPollingDeviceOnlyIfNotSelectedByOtherProjects(): Unit = runBlocking {
    // device model used in first project
    val (deviceModel1, processModel1) = createDeviceModel(device1, device2)
    // device model used in second project
    val (deviceModel2, _) = createDeviceModel(device1, device2)

    deviceModel2.setSelectedDevice(device1.toDeviceDescriptor())

    val foregroundProcessDetection = ForegroundProcessDetection(
      projectRule.project,
      deviceModel1,
      processModel1,
      transportClient,
      mock(),
      mock(),
      coroutineScope,
      workDispatcher,
      onDeviceDisconnected = {},
      pollingIntervalMs = 500L
    )

    val foregroundProcessSyncChannel = Channel<NewForegroundProcess>()
    foregroundProcessDetection.foregroundProcessListeners.add(ForegroundProcessListener { device, foregroundProcess ->
      coroutineScope.launch { foregroundProcessSyncChannel.send(NewForegroundProcess(device, foregroundProcess)) }
    })

    connectDevice(device1)
    val (handshakeDevice1, supportType1) = handshakeSyncChannel.receive()
    val startTrackingDevice1 = startTrackingSyncChannel.receive()

    assertThat(handshakeDevice1).isEqualTo(device1)
    assertThat(supportType1).isEqualTo(SupportType.SUPPORTED)

    assertThat(startTrackingDevice1).isEqualTo(device1)

    connectDevice(device2)
    val (handshakeDevice2, supportType2) = handshakeSyncChannel.receive()
    assertThat(handshakeDevice2).isEqualTo(device2)
    assertThat(supportType2).isEqualTo(SupportType.SUPPORTED)

    foregroundProcessDetection.startPollingDevice(device2.toDeviceDescriptor())
    val startTrackingDevice2 = startTrackingSyncChannel.receive()
    assertThat(startTrackingDevice2).isEqualTo(device2)

    // normally `startPollingDevice` would start polling on the new device (`device2`) and stop polling on the previous one (`device1`).
    // in this case it shouldn't, because `device1` is the selected device in `deviceModel2`.
    // there is only one polling per device, so stopping the polling on `device1` would stop the polling for all connected projects.
    withTimeoutOrNull<Nothing>(500) {
      stopTrackingSyncChannel.receive()
      fail()
    }

    foregroundProcessDetection.startPollingDevice(device1.toDeviceDescriptor())
    val startTrackingDevice3 = startTrackingSyncChannel.receive()
    assertThat(startTrackingDevice3).isEqualTo(device1)
    val stopTrackingDevice3 = stopTrackingSyncChannel.receive()
    assertThat(stopTrackingDevice3).isEqualTo(device2)

    // test `stopPollingSelectedDevice`
    foregroundProcessDetection.stopPollingSelectedDevice()
    withTimeoutOrNull<Nothing>(500) {
      stopTrackingSyncChannel.receive()
      fail()
    }

    foregroundProcessDetection.startPollingDevice(device1.toDeviceDescriptor())
    val startTrackingDevice4 = startTrackingSyncChannel.receive()
    assertThat(startTrackingDevice4).isEqualTo(device1)

    // test `stopInspector`
    foregroundProcessDetection.stopPollingSelectedDevice()
    withTimeoutOrNull<Nothing>(500) {
      stopTrackingSyncChannel.receive()
      fail()
    }

    Disposer.dispose(deviceModel2)

    foregroundProcessDetection.startPollingDevice(device1.toDeviceDescriptor())
    val startTrackingDevice5 = startTrackingSyncChannel.receive()
    assertThat(startTrackingDevice5).isEqualTo(device1)

    assertThat(deviceModel1.selectedDevice).isEqualTo(device1.toDeviceDescriptor())

    // `deviceModel2` has been disposed, `device1` is now the selected device only on `deviceModel1`
    // so polling should stop.
    foregroundProcessDetection.stopPollingSelectedDevice()
    val stopTrackingDevice6 = stopTrackingSyncChannel.receive()
    assertThat(stopTrackingDevice6).isEqualTo(device1)
  }

  @Test
  fun testSelectedProcessOnNotSupportedDeviceReInitiatesHandshake(): Unit = runBlocking {
    val (deviceModel, processModel) = createDeviceModel(device1)
    val foregroundProcessDetection = ForegroundProcessDetection(
      projectRule.project,
      deviceModel,
      processModel,
      transportClient,
      mock(),
      mock(),
      coroutineScope,
      workDispatcher,
      onDeviceDisconnected = {},
      pollingIntervalMs = 500L
    )

    val foregroundProcessSyncChannel = Channel<NewForegroundProcess>()
    foregroundProcessDetection.foregroundProcessListeners.add(ForegroundProcessListener { device, foregroundProcess ->
      coroutineScope.launch { foregroundProcessSyncChannel.send(NewForegroundProcess(device, foregroundProcess)) }
    })

    val selectedDeviceSyncChannel = Channel<DeviceDescriptor?>()
    deviceModel.newSelectedDeviceListeners.add { device ->
      coroutineScope.launch { selectedDeviceSyncChannel.send(device) }
    }

    connectDevice(device3)
    val (handshakeDevice1, supportType1) = handshakeSyncChannel.receive()
    assertThat(handshakeDevice1).isEqualTo(device3)
    assertThat(supportType1).isEqualTo(SupportType.NOT_SUPPORTED)

    withTimeoutOrNull<Nothing>(500) {
      val unexpectedDevice = selectedDeviceSyncChannel.receive()
      fail("Unexpectedly started tracking device \"${unexpectedDevice?.serial}\"")
    }

    withTimeoutOrNull<Nothing>(500) {
      val unexpectedDevice = startTrackingSyncChannel.receive()
      fail("Unexpectedly started tracking device \"${unexpectedDevice.deviceId}\"")
    }

    // run this in a separate coroutine, to avoid risk of deadlock
    coroutineScope.launch {
      // this should trigger the initiation of a new handshake
      processModel.selectedProcess = device3.toDeviceDescriptor().createProcess("fake_process", isRunning = true)
    }

    val (handshakeDevice2, supportType2) = handshakeSyncChannel.receive()
    assertThat(handshakeDevice2).isEqualTo(device3)
    assertThat(supportType2).isEqualTo(SupportType.NOT_SUPPORTED)

    withTimeoutOrNull<Nothing>(500) {
      val unexpectedDevice = selectedDeviceSyncChannel.receive()
      fail("Unexpectedly started tracking device \"${unexpectedDevice?.serial}\"")
    }

    withTimeoutOrNull<Nothing>(500) {
      val unexpectedDevice = startTrackingSyncChannel.receive()
      fail("Unexpectedly started tracking device \"${unexpectedDevice.deviceId}\"")
    }

    // the process is from a device that is not running, handshake should not start
    processModel.selectedProcess = device3.toDeviceDescriptor().createProcess("fake_process", isRunning = false)

    withTimeoutOrNull<Nothing>(500) {
      val unexpectedDevice = selectedDeviceSyncChannel.receive()
      fail("Unexpectedly started tracking device \"${unexpectedDevice?.serial}\"")
    }

    withTimeoutOrNull<Nothing>(500) {
      val (unexpectedDevice, _) = handshakeSyncChannel.receive()
      fail("Unexpected handshake with device \"${unexpectedDevice.deviceId}\"")
    }
  }

  @Test
  fun testSelectedProcessOnSupportedDeviceDoesNotReInitiatesHandshake(): Unit = runBlocking {
    val (deviceModel, processModel) = createDeviceModel(device1)
    val foregroundProcessDetection = ForegroundProcessDetection(
      projectRule.project,
      deviceModel,
      processModel,
      transportClient,
      mock(),
      mock(),
      coroutineScope,
      workDispatcher,
      onDeviceDisconnected = {},
      pollingIntervalMs = 500L
    )

    val foregroundProcessSyncChannel = Channel<NewForegroundProcess>()
    foregroundProcessDetection.foregroundProcessListeners.add(ForegroundProcessListener { device, foregroundProcess ->
      coroutineScope.launch { foregroundProcessSyncChannel.send(NewForegroundProcess(device, foregroundProcess)) }
    })

    connectDevice(device1)
    val (handshakeDevice1, supportType1) = handshakeSyncChannel.receive()
    assertThat(handshakeDevice1).isEqualTo(device1)
    assertThat(supportType1).isEqualTo(SupportType.SUPPORTED)

    val trackingDevice1 = startTrackingSyncChannel.receive()
    assertThat(trackingDevice1).isEqualTo(device1)

    // this should not trigger the initiation of a new handshake
    processModel.selectedProcess = device1.toDeviceDescriptor().createProcess("fake_process", isRunning = true)
    withTimeoutOrNull<Nothing>(500) {
      val (unexpectedDevice, _) = handshakeSyncChannel.receive()
      fail("Unexpected handshake with device \"${unexpectedDevice.deviceId}\"")
    }
  }

  @Test
  fun testSelectedProcessOnUnknownSupportDeviceDoesNotCreateSimultaneousHandshake(): Unit = runBlocking {
    val (deviceModel, processModel) = createDeviceModel(device4)
    ForegroundProcessDetection(
      projectRule.project,
      deviceModel,
      processModel,
      transportClient,
      mock(),
      mock(),
      coroutineScope,
      workDispatcher,
      onDeviceDisconnected = {},
      pollingIntervalMs = 500L
    )

    connectDevice(device4)
    val (handshakeDevice1, supportType1) = handshakeSyncChannel.receive()
    assertThat(handshakeDevice1).isEqualTo(device4)
    assertThat(supportType1).isEqualTo(SupportType.UNKNOWN)

    withTimeoutOrNull<Nothing>(500) {
      val unexpectedDevice = startTrackingSyncChannel.receive()
      fail("Unexpectedly started tracking device \"${unexpectedDevice.deviceId}\"")
    }

    val (handshakeDevice2, supportType2) = handshakeSyncChannel.receive()
    assertThat(handshakeDevice2).isEqualTo(device4)
    assertThat(supportType2).isEqualTo(SupportType.UNKNOWN)

    // this should not trigger the initiation of a new handshake
    processModel.selectedProcess = device4.toDeviceDescriptor().createProcess("fake_process", isRunning = true)

    val (handshakeDevice3, supportType3) = handshakeSyncChannel.receive()
    assertThat(handshakeDevice3).isEqualTo(device4)
    assertThat(supportType3).isEqualTo(SupportType.UNKNOWN)

    // stop handshake
    deviceToHandshakeSupportTypeMap[device4] = SupportType.NOT_SUPPORTED

    val (handshakeDevice4, supportType4) = handshakeSyncChannel.receive()
    assertThat(handshakeDevice4).isEqualTo(device4)
    assertThat(supportType4).isEqualTo(SupportType.NOT_SUPPORTED)

    withTimeoutOrNull<Nothing>(500) {
      val (unexpectedDevice, _) = handshakeSyncChannel.receive()
      fail("Unexpected handshake with device \"${unexpectedDevice.deviceId}\"")
    }

    // restore to UNKNOWN
    deviceToHandshakeSupportTypeMap[device4] = SupportType.UNKNOWN
  }

  @Test
  // TODO(b/260847188) re-enable
  @Ignore
  fun testCorruptedTransportIsLogged(): Unit = runBlocking {
    // see b/250589069 for definition of "corrupted transport"
    val onDeviceDisconnectedSyncChannel = Channel<DeviceDescriptor>()
    val onDeviceDisconnected: (DeviceDescriptor) -> Unit = {
      coroutineScope.launch { onDeviceDisconnectedSyncChannel.send(it) }
    }

    val layoutInspectorMetrics = mock<LayoutInspectorMetrics>()

    val (deviceModel, processModel) = createDeviceModel(device1)
    ForegroundProcessDetection(
      projectRule.project,
      deviceModel,
      processModel,
      transportClient,
      layoutInspectorMetrics,
      mock(),
      coroutineScope,
      workDispatcher,
      onDeviceDisconnected = onDeviceDisconnected,
      pollingIntervalMs = 500L
    )

    connectDevice(device1, 2)
    val (handshakeDevice1, _) = handshakeSyncChannel.receive()

    disconnectDevice(device1)
    val disconnectedDevice1 = onDeviceDisconnectedSyncChannel.receive()

    connectDevice(device1, 1)
    val (handshakeDevice2, _) = handshakeSyncChannel.receive()

    verify(layoutInspectorMetrics).logTransportError(
      DynamicLayoutInspectorTransportError.Type.TRANSPORT_OLD_TIMESTAMP_BIGGER_THAN_NEW_TIMESTAMP,
      device1.toDeviceDescriptor()
    )

    disconnectDevice(device1)
    val disconnectedDevice2 = onDeviceDisconnectedSyncChannel.receive()

    connectDevice(device1, 1)
    val (handshakeDevice3, _) = handshakeSyncChannel.receive()

    connectDevice(device2, 2)
    val (handshakeDevice4, _) = handshakeSyncChannel.receive()

    disconnectDevice(device2)
    val disconnectedDevice3 = onDeviceDisconnectedSyncChannel.receive()

    connectDevice(device2, 1)
    val (handshakeDevice5, _) = handshakeSyncChannel.receive()

    verify(layoutInspectorMetrics).logTransportError(
      DynamicLayoutInspectorTransportError.Type.TRANSPORT_OLD_TIMESTAMP_BIGGER_THAN_NEW_TIMESTAMP,
      device2.toDeviceDescriptor()
    )

    verifyNoMoreInteractions(layoutInspectorMetrics)

    assertThat(handshakeDevice1).isEqualTo(device1)
    assertThat(handshakeDevice2).isEqualTo(device1)
    assertThat(handshakeDevice3).isEqualTo(device1)
    assertThat(handshakeDevice4).isEqualTo(device2)
    assertThat(handshakeDevice5).isEqualTo(device2)
    assertThat(disconnectedDevice1).isEqualTo(device1.toDeviceDescriptor())
    assertThat(disconnectedDevice2).isEqualTo(device1.toDeviceDescriptor())
    assertThat(disconnectedDevice3).isEqualTo(device2.toDeviceDescriptor())
  }

  /**
   * Assert that [newForegroundProcess] contains the expected [device] and [foregroundProcess].
   */
  private fun assertEqual(newForegroundProcess: NewForegroundProcess, device: Common.Device, foregroundProcess: ForegroundProcess) {
    assertThat(newForegroundProcess.device).isEqualTo(device.toDeviceDescriptor())
    assertThat(newForegroundProcess.foregroundProcess).isEqualTo(foregroundProcess)
  }

  private fun sendForegroundProcessEvent(device: Common.Device, foregroundProcess: ForegroundProcess) {
    val stream = deviceToStreamMap[device]!!
    val event = createForegroundProcessEvent(foregroundProcess, stream)
    sendEvent(stream, event)
  }

  /**
   * Connect a device to the transport and to adb.
   */
  private fun connectDevice(device: Common.Device, timestamp: Long? = null) {
    val transportDevice = deviceToStreamMap[device]!!.device

    if (timestamp != null) {
      transportService.addDevice(transportDevice, timestamp)
    }
    else {
      transportService.addDevice(transportDevice)
    }

    if (adbRule.bridge.devices.none { it.serialNumber == device.serial }) {
      adbRule.attachDevice(device.serial, device.manufacturer, device.model, device.version, device.apiLevel.toString())
    }
  }

  /**
   * Disconnect a device from the transport and from adb.
   */
  private fun disconnectDevice(device: Common.Device) {
    val offlineDevice = device.toBuilder()
      .setState(Common.Device.State.OFFLINE)
      .build()

    transportService.updateDevice(device, offlineDevice)
  }

  private fun createDeviceModel(vararg devices: Common.Device): Pair<DeviceModel, ProcessesModel> {
    val testProcessDiscovery = TestProcessDiscovery()
    devices.forEach { testProcessDiscovery.addDevice(it.toDeviceDescriptor()) }
    val processModel = ProcessesModel(testProcessDiscovery)
    return DeviceModel(disposableRule.disposable, processModel) to processModel
  }

  private fun createForegroundProcessEvent(foregroundProcess: ForegroundProcess, stream: Common.Stream): Common.Event {
    val eventBuilder = Common.Event.newBuilder()
    return eventBuilder
      .setKind(Common.Event.Kind.LAYOUT_INSPECTOR_FOREGROUND_PROCESS)
      .setGroupId(stream.streamId)
      .setStream(
        eventBuilder.streamBuilder.setStreamConnected(
          eventBuilder.streamBuilder.streamConnectedBuilder
            .setStream(stream)
        )
      ).setLayoutInspectorForegroundProcess(
        eventBuilder.layoutInspectorForegroundProcessBuilder
          .setPid(foregroundProcess.pid.toString())
          .setProcessName(foregroundProcess.processName)
          .build()
      ).build()
  }

  private fun buildForegroundProcessSupportedEvent(supportType: SupportType): Common.Event {
    val foregroundProcessEventBuilder = Common.Event.newBuilder()
      .layoutInspectorTrackingForegroundProcessSupportedBuilder
      .setSupportType(supportType)

    if (supportType == SupportType.NOT_SUPPORTED) {
      foregroundProcessEventBuilder.reasonNotSupported = LayoutInspector.TrackingForegroundProcessSupported.ReasonNotSupported.DUMPSYS_NOT_FOUND
    }

    return Common.Event.newBuilder()
      .setKind(Common.Event.Kind.LAYOUT_INSPECTOR_TRACKING_FOREGROUND_PROCESS_SUPPORTED)
      .setLayoutInspectorTrackingForegroundProcessSupported(foregroundProcessEventBuilder.build())
      .build()
  }

  private fun createFakeStream(streamId: Long, device: Common.Device): Common.Stream {
    return Common.Stream.newBuilder()
      .setStreamId(streamId)
      .setDevice(device)
      .build()
  }

  private fun sendEvent(stream: Common.Stream, event: Common.Event) {
    val streamId = stream.streamId
    val eventWithTimestamp = event.toBuilder().apply {
      timestamp = timestampGenerator.getAndIncrement()
    }.build()

    transportService.addEventToStream(streamId, eventWithTimestamp)
  }

  private fun FakeTransportService.setCommandHandler(command: Command.CommandType, block: (Command) -> Unit) {
    setCommandHandler(command, object : CommandHandler(timer) {
      override fun handleCommand(command: Command, events: MutableList<Common.Event>) {
        block.invoke(command)
      }
    })
  }

  private fun getStreamFromCommand(command: Command): Common.Stream {
    return deviceToStreamMap.reverse().keys.find { stream -> stream.streamId == command.streamId }!!
  }

  private fun getDeviceFromCommand(command: Command): Common.Device {
    val stream = getStreamFromCommand(command)
    return deviceToStreamMap.reverse()[stream]!!
  }

  /**
   * Class containing all the information provided when a new foreground process shows up.
   */
  private data class NewForegroundProcess(val device: DeviceDescriptor, val foregroundProcess: ForegroundProcess)

  @Suppress("SameParameterValue")
  private fun runBlockingWithFlagState(desiredFlagState: Boolean, task: suspend () -> Unit): Unit = runBlocking {
    val flag = StudioFlags.DYNAMIC_LAYOUT_INSPECTOR_AUTO_CONNECT_TO_FOREGROUND_PROCESS_ENABLED
    val flagPreviousState = flag.get()
    flag.override(desiredFlagState)

    task()

    // restore flag state
    flag.override(flagPreviousState)
  }
}