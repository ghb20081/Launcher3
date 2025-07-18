/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.launcher3.tapl

import androidx.test.uiautomator.UiObject2

/**
 * A class representing the Bubble menu item in the app long-press menu, which moves the app into a
 * bubble.
 */
class BubbleMenuItem(
    private val launcher: LauncherInstrumentation,
    private val uiObject: UiObject2,
) {

    fun click() {
        launcher.addContextLayer("want to create bubble from app long-press menu").use {
            LauncherInstrumentation.log(
                "clicking on bubble menu item ${uiObject.visibleCenter} in ${
                    launcher.getVisibleBounds(
                        uiObject
                    )
                }"
            )
            launcher.clickLauncherObject(uiObject)
        }
    }
}
