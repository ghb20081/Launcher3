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

package com.android.launcher3.util

import android.util.FloatProperty
import kotlin.reflect.KMutableProperty1

/** Maps any Kotlin mutable property (var) to [FloatProperty]. */
class KFloatProperty<T>(private val kProperty: KMutableProperty1<T, Float>) :
    FloatProperty<T>(kProperty.name) {
    override fun get(target: T) = kProperty.get(target)

    override fun setValue(target: T, value: Float) {
        kProperty.set(target, value)
    }
}
