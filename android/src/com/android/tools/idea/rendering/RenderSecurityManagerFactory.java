/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.tools.idea.rendering;

import com.android.tools.idea.log.LogWrapper;
import com.intellij.openapi.application.PathManager;
import com.android.tools.sdk.AndroidPlatform;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Factory of {@link RenderSecurityManager}.
 */
public class RenderSecurityManagerFactory {
  /**
   * Returns a {@link RenderSecurityManager} for the SDK path and project path.
   */
  @NotNull
  public static RenderSecurityManager create(@Nullable String projectPath, @Nullable AndroidPlatform platform) {
    String sdkPath = platform != null ? platform.getSdkData().getLocation().toString() : null;

    @SuppressWarnings("ConstantConditions")
    RenderSecurityManager securityManager = new RenderSecurityManager(sdkPath, projectPath, false);
    securityManager.setLogger(new LogWrapper(RenderLogger.LOG).alwaysLogAsDebug(true).allowVerbose(false));
    securityManager.setAppTempDir(PathManager.getTempPath());

    return securityManager;
  }
}
