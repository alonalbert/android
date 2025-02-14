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
package com.android.tools.idea.logcat.messages

import com.android.tools.idea.logcat.LogcatBundle
import com.android.tools.idea.logcat.messages.AndroidLogcatFormattingOptions.Companion.DEFAULT_COMPACT
import com.android.tools.idea.logcat.messages.AndroidLogcatFormattingOptions.Companion.DEFAULT_STANDARD
import com.android.tools.idea.logcat.messages.FormattingOptions.Style.COMPACT
import com.android.tools.idea.logcat.messages.FormattingOptions.Style.STANDARD
import com.google.wireless.android.sdk.stats.LogcatUsageEvent
import com.google.wireless.android.sdk.stats.LogcatUsageEvent.LogcatFormatConfiguration.Preset
import com.intellij.CommonBundle
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.selected
import java.awt.event.ActionEvent
import java.awt.event.ItemEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/**
 * A variant of [LogcatFormatDialogBase] that controls the Standard & Compact formatting presets.
 */
internal class LogcatFormatPresetsDialog(
  private val project: Project,
  private val initialFormatting: FormattingOptions.Style,
  var defaultFormatting: FormattingOptions.Style,
  apply: ApplyAction
) : LogcatFormatDialogBase(project, apply) {

  val standardFormattingOptions = STANDARD.formattingOptions.copy()
  val compactFormattingOptions = COMPACT.formattingOptions.copy()
  private lateinit var styleComboBoxComponent: ComboBox<FormattingOptions.Style>
  private lateinit var setAsDefaultCheckBoxComponent: JBCheckBox
  private var doNotApplyToFormattingOptions: Boolean = false
  private var applyButton = ApplyButton()

  override fun createDialogWrapper(): DialogWrapper = MyDialogWrapper(project, createPanel(initialFormatting.formattingOptions))

  override fun createComponents(layoutBuilder: Panel, formattingOptions: FormattingOptions) {
    globalSettingsGroup(layoutBuilder)
    super.createComponents(layoutBuilder, formattingOptions)
  }

  private fun globalSettingsGroup(layoutBuilder: Panel) {
    layoutBuilder.row {
      label(LogcatBundle.message("logcat.format.presets.dialog.view"))
      val model = DefaultComboBoxModel(FormattingOptions.Style.values())
      val renderer = SimpleListCellRenderer.create<FormattingOptions.Style>("") { it?.displayName }
      //TODO: replace with proper setter
      val styleComboBox = comboBox(model, renderer).bindItem(getter = { initialFormatting }, setter = {})
      val setAsDefaultCheckBox = checkBox(LogcatBundle.message("logcat.format.presets.dialog.default"))
        .selected(initialFormatting == defaultFormatting)
      setAsDefaultCheckBox.applyToComponent {
        addItemListener {
          if (isSelected) {
            defaultFormatting = styleComboBox.component.item
          }
        }
      }
      cell(ActionLink(LogcatBundle.message("logcat.format.presets.dialog.restore.default")) { restoreDefault() }).align(AlignX.RIGHT)
      styleComboBox.applyToComponent {
        addItemListener {
          if (it.stateChange == ItemEvent.DESELECTED) {
            return@addItemListener
          }
          val previousOptions: FormattingOptions
          val currentOptions: FormattingOptions
          if (item == STANDARD) {
            previousOptions = compactFormattingOptions
            currentOptions = standardFormattingOptions
          }
          else {
            previousOptions = standardFormattingOptions
            currentOptions = compactFormattingOptions
          }
          applyToFormattingOptions(previousOptions)

          // Do not apply changes to the current style while we manually change update the components.
          doNotApplyToFormattingOptions = true
          try {
            applyToComponents(currentOptions)
          }
          finally {
            doNotApplyToFormattingOptions = false
          }

          setAsDefaultCheckBox.component.isSelected = item == defaultFormatting
        }
        styleComboBoxComponent = styleComboBox.component
        setAsDefaultCheckBoxComponent = setAsDefaultCheckBox.component
      }
    }
  }

  private fun restoreDefault() {
    val formattingOptions = when (styleComboBoxComponent.item!!) {
      STANDARD -> DEFAULT_STANDARD
      COMPACT -> DEFAULT_COMPACT
    }
    applyToComponents(formattingOptions)
  }

  override fun onComponentsChanged(isUserAction: Boolean) {
    super.onComponentsChanged(isUserAction)
    if (doNotApplyToFormattingOptions || !isUserAction) {
      return
    }
    // Apply the current state of the UI to the current style. This is only done when the user interacts with the control.
    applyToFormattingOptions(if (styleComboBoxComponent.item == STANDARD) standardFormattingOptions else compactFormattingOptions)
    applyButton.isEnabled = true
  }

  /**
   * We need to extend DialogWrapper ourselves rather than use `components.dialog()` because we need to add an `Apply` button and there
   * seems no easy way to do this with the `components.dialog()` version.
   *
   * It does provide a way to set the actions but in a way that completely replaces the default `OK` and `Cancel` buttons. There seems to be
   * no way to reuse them.
   */
  private inner class MyDialogWrapper(project: Project, private val panel: JComponent)
    : DialogWrapper(project, null, true, IdeModalityType.PROJECT) {
    override fun createCenterPanel(): JComponent = panel

    init {
      title = LogcatBundle.message("logcat.header.options.title")
      isResizable = true
      init()
    }

    override fun doOKAction() {
      onApply(isApplyButton = false)
      super.doOKAction()
    }

    override fun createActions(): Array<Action> = super.createActions() + applyButton
  }

  override fun getLogcatFormatDialogEvent(): LogcatUsageEvent.LogcatFormatDialog.Builder {
    val event = super.getLogcatFormatDialogEvent()
    event.configurationBuilder.preset = if (styleComboBoxComponent.item == STANDARD) Preset.STANDARD else Preset.COMPACT
    event.isDefaultPreset = setAsDefaultCheckBoxComponent.isSelected
    return event
  }

  private inner class ApplyButton : AbstractAction(CommonBundle.getApplyButtonText()) {
    init {
      isEnabled = false
    }

    override fun actionPerformed(e: ActionEvent) {
      onApply(isApplyButton = true)
      isEnabled = false
    }
  }
}
