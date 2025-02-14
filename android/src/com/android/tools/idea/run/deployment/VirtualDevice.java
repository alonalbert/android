/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tools.idea.run.deployment;

import com.android.sdklib.AndroidVersion;
import com.android.tools.idea.flags.StudioFlags;
import com.android.tools.idea.run.AndroidDevice;
import com.android.tools.idea.run.DeploymentApplicationService;
import com.android.tools.idea.run.LaunchCompatibility;
import com.android.tools.idea.run.LaunchableAndroidDevice;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.ui.LayeredIcon;
import icons.StudioIcons;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A virtual device. If it's in the Android Virtual Device Manager Device.myKey is a VirtualDevicePath and myNameKey is not null. If not,
 * Device.myKey may be a VirtualDevicePath, VirtualDeviceName, or SerialNumber depending on what the IDevice returns and myNameKey is null.
 */
public final class VirtualDevice extends Device {
  private static final Icon ourPhoneIcon = StudioIcons.DeviceExplorer.VIRTUAL_DEVICE_PHONE;
  private static final Icon ourWearIcon = StudioIcons.DeviceExplorer.VIRTUAL_DEVICE_WEAR;
  private static final Icon ourTvIcon = StudioIcons.DeviceExplorer.VIRTUAL_DEVICE_TV;

  /**
   * The virtual device names match with ConnectedDevices that don't support the avd path emulator console subcommand added to the emulator
   * in Version 30.0.18
   */
  private final @Nullable VirtualDeviceName myNameKey;

  private final @NotNull Collection<Snapshot> mySnapshots;
  private final boolean mySelectDeviceSnapshotComboBoxSnapshotsEnabled;

  @NotNull
  public static VirtualDevice newConnectedDevice(@NotNull ConnectedDevice connectedDevice,
                                                 @NotNull KeyToConnectionTimeMap map,
                                                 @Nullable VirtualDevice virtualDevice) {
    Device device;
    VirtualDeviceName nameKey;

    if (virtualDevice == null) {
      device = connectedDevice;
      nameKey = null;
    }
    else {
      device = virtualDevice;
      nameKey = virtualDevice.myNameKey;
    }

    Key key = device.getKey();

    return new Builder()
      .setName(device.getName())
      .setLaunchCompatibility(connectedDevice.getLaunchCompatibility())
      .setKey(key)
      .setConnectionTime(map.get(key))
      .setAndroidDevice(connectedDevice.getAndroidDevice())
      .setNameKey(nameKey)
      .addAllSnapshots(device.getSnapshots())
      .setType(device.getType())
      .build();
  }

  static final class Builder extends Device.Builder {
    private @Nullable VirtualDeviceName myNameKey;
    private final @NotNull Collection<Snapshot> mySnapshots = new ArrayList<>();
    private boolean mySelectDeviceSnapshotComboBoxSnapshotsEnabled = StudioFlags.SELECT_DEVICE_SNAPSHOT_COMBO_BOX_SNAPSHOTS_ENABLED.get();

    @NotNull
    Builder setName(@NotNull String name) {
      myName = name;
      return this;
    }

    @NotNull
    Builder setLaunchCompatibility(LaunchCompatibility launchCompatibility) {
      myLaunchCompatibility = launchCompatibility;
      return this;
    }

    @NotNull
    Builder setKey(@NotNull Key key) {
      myKey = key;
      return this;
    }

    @NotNull
    @VisibleForTesting
    Builder setConnectionTime(@NotNull Instant connectionTime) {
      myConnectionTime = connectionTime;
      return this;
    }

    @NotNull
    Builder setAndroidDevice(@NotNull AndroidDevice androidDevice) {
      myAndroidDevice = androidDevice;
      return this;
    }

    @NotNull
    Builder setNameKey(@Nullable VirtualDeviceName nameKey) {
      myNameKey = nameKey;
      return this;
    }

    @NotNull
    @VisibleForTesting
    Builder addSnapshot(@NotNull Snapshot snapshot) {
      mySnapshots.add(snapshot);
      return this;
    }

    @NotNull
    Builder addAllSnapshots(@NotNull Collection<Snapshot> snapshots) {
      mySnapshots.addAll(snapshots);
      return this;
    }

    @NotNull
    @VisibleForTesting
    Builder setSelectDeviceSnapshotComboBoxSnapshotsEnabled(boolean selectDeviceSnapshotComboBoxSnapshotsEnabled) {
      mySelectDeviceSnapshotComboBoxSnapshotsEnabled = selectDeviceSnapshotComboBoxSnapshotsEnabled;
      return this;
    }

    @NotNull
    Builder setType(@NotNull Type type) {
      myType = type;
      return this;
    }

    @NotNull
    @Override
    VirtualDevice build() {
      return new VirtualDevice(this);
    }
  }

  private VirtualDevice(@NotNull Builder builder) {
    super(builder);

    myNameKey = builder.myNameKey;
    mySnapshots = new ArrayList<>(builder.mySnapshots);
    mySelectDeviceSnapshotComboBoxSnapshotsEnabled = builder.mySelectDeviceSnapshotComboBoxSnapshotsEnabled;
  }

  @NotNull
  public Optional<VirtualDeviceName> getNameKey() {
    return Optional.ofNullable(myNameKey);
  }

  void coldBoot(@NotNull Project project) {
    ((LaunchableAndroidDevice)getAndroidDevice()).coldBoot(project);
  }

  void quickBoot(@NotNull Project project) {
    ((LaunchableAndroidDevice)getAndroidDevice()).quickBoot(project);
  }

  void bootWithSnapshot(@NotNull Project project, @NotNull Path snapshot) {
    ((LaunchableAndroidDevice)getAndroidDevice()).bootWithSnapshot(project, snapshot.toString());
  }

  @NotNull
  @Override
  Icon getIcon() {
    var icon = switch (getType()) {
      case PHONE -> ourPhoneIcon;
      case WEAR -> ourWearIcon;
      case TV -> ourTvIcon;
    };

    if (isConnected()) {
      icon = ExecutionUtil.getLiveIndicator(icon);
    }

    return switch (getLaunchCompatibility().getState()) {
      case OK -> icon;
      case WARNING -> new LayeredIcon(icon, AllIcons.General.WarningDecorator);
      case ERROR -> new LayeredIcon(icon, StudioIcons.Common.ERROR_DECORATOR);
    };
  }

  @Override
  public boolean isConnected() {
    return getConnectionTime() != null;
  }

  @NotNull
  @Override
  Collection<Snapshot> getSnapshots() {
    return mySnapshots;
  }

  @NotNull
  @Override
  public Target getDefaultTarget() {
    if (!mySelectDeviceSnapshotComboBoxSnapshotsEnabled) {
      return new QuickBootTarget(getKey());
    }

    if (isConnected()) {
      return new RunningDeviceTarget(getKey());
    }

    return new QuickBootTarget(getKey());
  }

  @NotNull
  @Override
  Collection<Target> getTargets() {
    if (!mySelectDeviceSnapshotComboBoxSnapshotsEnabled) {
      return Collections.singletonList(new QuickBootTarget(getKey()));
    }

    if (isConnected()) {
      return Collections.singletonList(new RunningDeviceTarget(getKey()));
    }

    if (mySnapshots.isEmpty()) {
      return Collections.singletonList(new QuickBootTarget(getKey()));
    }

    Collection<Target> targets = new ArrayList<>(2 + mySnapshots.size());
    Key deviceKey = getKey();

    targets.add(new ColdBootTarget(deviceKey));
    targets.add(new QuickBootTarget(deviceKey));

    mySnapshots.stream()
      .map(Snapshot::getDirectory)
      .map(snapshotKey -> new BootWithSnapshotTarget(deviceKey, snapshotKey))
      .forEach(targets::add);

    return targets;
  }

  @NotNull
  @Override
  ListenableFuture<AndroidVersion> getAndroidVersionAsync() {
    Object androidDevice = getAndroidDevice();

    if (androidDevice instanceof LaunchableAndroidDevice) {
      return Futures.immediateFuture(((LaunchableAndroidDevice)androidDevice).getAvdInfo().getAndroidVersion());
    }

    var service = DeploymentApplicationService.getInstance();

    // noinspection UnstableApiUsage
    return Futures.transformAsync(getDdmlibDeviceAsync(), service::getVersion, MoreExecutors.directExecutor());
  }

  @Override
  public boolean equals(@Nullable Object object) {
    if (!(object instanceof VirtualDevice device)) {
      return false;
    }

    return getName().equals(device.getName()) &&
           getType().equals(device.getType()) &&
           getLaunchCompatibility().equals(device.getLaunchCompatibility()) &&
           getKey().equals(device.getKey()) &&
           Objects.equals(getConnectionTime(), device.getConnectionTime()) &&
           getAndroidDevice().equals(device.getAndroidDevice()) &&
           Objects.equals(myNameKey, device.myNameKey) &&
           mySnapshots.equals(device.mySnapshots) &&
           mySelectDeviceSnapshotComboBoxSnapshotsEnabled == device.mySelectDeviceSnapshotComboBoxSnapshotsEnabled;
  }

  @Override
  public int hashCode() {
    return Objects.hash(getName(),
                        getType(),
                        getLaunchCompatibility(),
                        getKey(),
                        getConnectionTime(),
                        getAndroidDevice(),
                        myNameKey,
                        mySnapshots,
                        mySelectDeviceSnapshotComboBoxSnapshotsEnabled);
  }
}
