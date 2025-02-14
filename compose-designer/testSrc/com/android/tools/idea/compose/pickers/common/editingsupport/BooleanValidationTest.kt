/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.tools.idea.compose.pickers.common.editingsupport

import com.android.tools.adtui.model.stdui.EDITOR_NO_ERROR
import com.android.tools.adtui.model.stdui.EditingValidation
import kotlin.test.assertEquals
import org.junit.Test

class BooleanValidationTest {

  lateinit var validator: EditingValidation

  @Test
  fun testValidator() {
    validator = BooleanValidator
    assertEquals(EDITOR_NO_ERROR, validator(""))
    assertEquals(EDITOR_NO_ERROR, validator(null))
    assertEquals(EDITOR_NO_ERROR, validator("   "))
    assertEquals(EDITOR_NO_ERROR, validator("true"))
    assertEquals(EDITOR_NO_ERROR, validator("false"))

    assertEquals(ERROR_NOT_BOOLEAN, validator("Test"))
    assertEquals(ERROR_NOT_BOOLEAN, validator("t r u e"))
    assertEquals(ERROR_NOT_BOOLEAN, validator("TrUe"))
    assertEquals(ERROR_NOT_BOOLEAN, validator("FaLSe"))
  }
}
