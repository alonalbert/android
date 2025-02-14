/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.run.activity;

import com.android.ddmlib.IDevice;
import com.android.tools.idea.flags.StudioFlags;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import java.util.LinkedList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public class DefaultStartActivityFlagsProvider implements StartActivityFlagsProvider {

  private final boolean myWaitForDebugger;
  @NotNull private final String myExtraFlags;

  @NotNull private final Project myProject;

  public DefaultStartActivityFlagsProvider(@NotNull Project project, boolean waitForDebugger,
                                           @NotNull String extraFlags) {
    myProject = project;
    myWaitForDebugger = waitForDebugger;
    myExtraFlags = extraFlags;
  }

  @Override
  @NotNull
  public String getFlags(@NotNull IDevice device) {
    List<String> flags = new LinkedList<>();
    if (myWaitForDebugger) {
      flags.add("-D");

      // Request Android app VM to start and then suspend all its threads
      if (suspendEnabled() && suspendSupported(device)) {
        flags.add("--suspend");
      }
    }
    if (!myExtraFlags.isEmpty()) {
      flags.add(myExtraFlags);
    }

    return StringUtil.join(flags, " ");
  }

  private boolean suspendSupported(IDevice device) {
      return ActivityManagerCapabilities.suspendSupported(myProject, device);
  }

  private static boolean suspendEnabled() {
      return StudioFlags.DEBUG_ATTEMPT_SUSPENDED_START.get();
  }
}
