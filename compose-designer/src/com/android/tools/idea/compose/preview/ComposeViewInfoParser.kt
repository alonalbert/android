/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.tools.idea.compose.preview

import com.android.ide.common.rendering.api.ViewInfo
import com.android.tools.idea.compose.preview.util.findComposeViewAdapter
import com.intellij.openapi.diagnostic.Logger

interface SourceLocation {
  @Deprecated("This field is not provided by the Compose runtime from dev16+") val className: String

  @Deprecated("This field is not provided by the Compose runtime from dev16+")
  val methodName: String

  val fileName: String

  /** 1-indexed line number. */
  val lineNumber: Int

  /**
   * Package name hash generated by the runtime to disambiguate multiple files with the name file
   * name.
   */
  val packageHash: Int

  /** Returns true if there is no source location information */
  fun isEmpty() = lineNumber == -1 && className == "" && packageHash == -1
}

internal data class SourceLocationImpl(
  override val className: String,
  override val methodName: String,
  override val fileName: String,
  override val lineNumber: Int,
  override val packageHash: Int
) : SourceLocation

typealias LineNumberMapper = (SourceLocation) -> SourceLocation

private val identifyLineNumberMapper: LineNumberMapper = { sourceLocation -> sourceLocation }

/**
 * Parse the viewObject for ComposeViewAdapter. For now we use reflection to parse these information
 * without much structuring. In the future we hope to change this.
 */
fun parseViewInfo(
  rootViewInfo: ViewInfo,
  lineNumberMapper: LineNumberMapper = identifyLineNumberMapper,
  logger: Logger
): List<ComposeViewInfo> {
  try {
    val viewObj = findComposeViewAdapter(rootViewInfo.viewObject) ?: return listOf()
    // With JDK 11, Kotlin reflection fails to find the declaredProperties (b/162686073).
    // For now, we are stuck using java reflection to find the property and to use contains to avoid
    // the
    // name mangling.
    val viewInfoField =
      viewObj::class
        .java
        .declaredMethods
        .single { it.name.contains("getViewInfos") }
        .also { it.isAccessible = true }
    val composeViewInfos = viewInfoField.invoke(viewObj) as List<*>
    return parseBounds(composeViewInfos, lineNumberMapper, logger)
  } catch (e: Exception) {
    logger.debug(e)
    return listOf()
  }
}

private fun parseBounds(
  elements: List<Any?>,
  fileLocationMapper: LineNumberMapper,
  logger: Logger
): List<ComposeViewInfo> =
  elements.mapNotNull { item ->
    try {
      val fileName = item!!.javaClass.getMethod("getFileName").invoke(item) as String
      val lineNumber = item.javaClass.getMethod("getLineNumber").invoke(item) as Int
      val method =
        try {
          item.javaClass.getMethod("getMethodName").invoke(item) as String
        } catch (_: Throwable) {
          // Method name is not used from dev16 on
          ""
        }
      val bounds = getBound(item)
      val children = item.javaClass.getMethod("getChildren").invoke(item) as List<Any?>

      // Additional source information was added in dev16 to locate the files and method was
      // removed.
      val packageHash =
        if (method.isEmpty()) {
          try {
            item.javaClass.getMethod("getLocation").invoke(item)?.let {
              it.javaClass.getMethod("getPackageHash").invoke(it) as Int
            }
          } catch (_: Throwable) {
            // Package information is not available before dev16
            null
          } ?: -1
        } else -1

      val sourceLocation =
        fileLocationMapper(
          SourceLocationImpl(
            method.substringBeforeLast("."),
            method.substringAfterLast("."),
            fileName,
            lineNumber,
            packageHash
          )
        )
      ComposeViewInfo(sourceLocation, bounds, parseBounds(children, fileLocationMapper, logger))
    } catch (t: Throwable) {
      logger.debug(t)
      null
    }
  }

private fun getBound(viewInfo: Any): PxBounds {
  val bounds = viewInfo.javaClass.getMethod("getBounds").invoke(viewInfo)
  val topPx = bounds.javaClass.getMethod("getTop").invoke(bounds)
  val bottomPx = bounds.javaClass.getMethod("getBottom").invoke(bounds)
  val rightPx = bounds.javaClass.getMethod("getRight").invoke(bounds)
  val leftPx = bounds.javaClass.getMethod("getLeft").invoke(bounds)

  return PxBounds(
    left = getInt(leftPx),
    top = getInt(topPx),
    right = getInt(rightPx),
    bottom = getInt(bottomPx)
  )
}

private fun getInt(px: Any): Int {
  // dev10 started using inline classes so we might have an Int already
  if (px is Int) return px

  val value = px.javaClass.getMethod("getValue").invoke(px)
  // In dev05, the type of Px changed from Float to Int. We need to handle both cases here for
  // backwards compatibility
  return value as? Int ?: (value as Float).toInt()
}
