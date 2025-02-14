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
package com.android.tools.adtui.categorytable

import com.android.testutils.ImageDiffUtil
import com.android.tools.adtui.swing.FakeUi
import com.google.common.truth.Truth.assertThat
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.util.ui.ImageUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.UIManager
import org.junit.Assert.assertThrows
import org.junit.Test

class IconTableComponentTest {
  @Test
  fun updateTablePresentation() {
    val icon = UIManager.get("Tree.expandedIcon", null) as Icon
    val label = IconLabel(icon)
    val presentationManager = TablePresentationManager()
    val selected =
      TablePresentation(foreground = JBColor.BLUE, background = JBColor.RED, rowSelected = true)
    val unselected =
      TablePresentation(foreground = JBColor.BLUE, background = JBColor.RED, rowSelected = false)

    assertThat(label.icon).isEqualTo(icon)

    label.updateTablePresentation(presentationManager, selected)
    assertSameImage(render(label, label.icon), render(label, icon.applyColor(JBColor.BLUE)))

    label.updateTablePresentation(presentationManager, unselected)
    assertThat(label.icon).isEqualTo(icon)
  }

  @Test
  fun nullIcon() {
    val panel =
      JPanel().apply {
        layout = BorderLayout()
        size = Dimension(32, 32)
      }
    val fakeUi = FakeUi(panel)

    val renderNoIcon = fakeUi.render()

    val label = IconLabel(null).apply { size = Dimension(32, 32) }
    panel.add(label)
    fakeUi.layout()
    val renderNullIcon = fakeUi.render()

    assertSameImage(renderNoIcon, renderNullIcon)

    label.baseIcon = UIManager.get("Tree.expandedIcon", null) as Icon

    assertThrows(AssertionError::class.java) { assertSameImage(fakeUi.render(), renderNullIcon) }
  }

  @Test
  fun animatedIcon() {
    val panel =
      JPanel().apply {
        layout = BorderLayout()
        size = Dimension(32, 32)
      }
    val icon = ColorableAnimatedSpinnerIcon()
    val iconLabel = IconLabel(icon)
    panel.add(iconLabel)

    iconLabel.iconColor = JBColor.RED
    assertThat(iconLabel.icon).isInstanceOf(AnimatedIcon::class.java)

    // If we want to test that rendering actually happens repeatedly, we can do the
    // following, but it depends on an experimental API:
    //  val fakeUi = FakeUi(panel, createFakeWindow = true)
    //  val renders = AtomicInteger()
    //  iconLabel.putClientProperty(AnimatedIcon.REFRESH_DELEGATE, Runnable {
    // renders.incrementAndGet(); fakeUi.render() })
    //  runInEdtAndWait {
    //    fakeUi.render()
    //  }
    //  Thread.sleep(500)
    //  assertThat(renders.get()).isGreaterThan(1)
  }
}

private fun assertSameImage(image1: BufferedImage, image2: BufferedImage) {
  ImageDiffUtil.assertImageSimilar("icon", image1, image2, 0.0, 0)
}

private fun render(component: Component, icon: Icon): BufferedImage {
  val image = ImageUtil.createImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
  val graphics = image.createGraphics()
  graphics.color = Gray.TRANSPARENT
  graphics.fillRect(0, 0, image.width, image.height)
  icon.paintIcon(component, graphics, 0, 0)
  return image
}
