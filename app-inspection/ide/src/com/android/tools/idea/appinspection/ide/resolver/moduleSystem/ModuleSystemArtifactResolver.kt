/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.tools.idea.appinspection.ide.resolver.moduleSystem

import com.android.tools.idea.appinspection.ide.resolver.INSPECTOR_JAR
import com.android.tools.idea.appinspection.ide.resolver.ModuleSystemArtifactFinder
import com.android.tools.idea.appinspection.ide.resolver.createRandomTempDir
import com.android.tools.idea.appinspection.ide.resolver.extractZipIfNeeded
import com.android.tools.idea.appinspection.ide.resolver.resolveExistsOrNull
import com.android.tools.idea.appinspection.inspector.api.AppInspectionArtifactNotFoundException
import com.android.tools.idea.appinspection.inspector.api.launch.ArtifactCoordinate
import com.android.tools.idea.appinspection.inspector.ide.resolver.ArtifactResolver
import com.android.tools.idea.concurrency.AndroidDispatchers
import com.android.tools.idea.io.FileService
import java.nio.file.Path
import kotlinx.coroutines.withContext

/**
 * This resolver uses the IDE's module system to look for inspector artifacts.
 *
 * This is not the preferred way to resolve artifacts as it looks at local artifacts which could be
 * modified by the user. However, it is useful in situations in which the artifact can't be resolved
 * any other way.
 *
 * In blaze projects, this resolver looks at artifacts located inside google3's third_party
 * repository.
 */
class ModuleSystemArtifactResolver(
  private val fileService: FileService,
  private val moduleSystemArtifactFinder: ModuleSystemArtifactFinder
) : ArtifactResolver {
  override suspend fun resolveArtifact(artifactCoordinate: ArtifactCoordinate): Path =
    withContext(AndroidDispatchers.diskIoThread) {
      val libraryPath =
        moduleSystemArtifactFinder.findLibrary(artifactCoordinate)
          ?: throw AppInspectionArtifactNotFoundException(
            "Artifact $artifactCoordinate could not be found in module system.",
            artifactCoordinate
          )
      val extractedPath = extractZipIfNeeded(fileService.createRandomTempDir(), libraryPath)
      extractedPath.resolveExistsOrNull(INSPECTOR_JAR)
        ?: throw AppInspectionArtifactNotFoundException(
          "inspector jar could not be found in $libraryPath",
          artifactCoordinate
        )
    }
}
