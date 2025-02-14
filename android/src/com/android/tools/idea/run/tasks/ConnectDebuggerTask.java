/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.tools.idea.run.tasks;

import com.android.ddmlib.IDevice;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.xdebugger.impl.XDebugSessionImpl;
import org.jetbrains.annotations.NotNull;

/**
 * A {@link ConnectDebuggerTask} is similar to a {@link LaunchTask}, except that running it creates a new launch descriptor and process.
 */
public interface ConnectDebuggerTask {

  @NotNull
  XDebugSessionImpl perform(@NotNull IDevice device,
                            @NotNull String applicationId,
                            @NotNull ExecutionEnvironment environment,
                            @NotNull ProgressIndicator progressIndicator, ConsoleView console);
}
