package com.doro.organizer.ui.common

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Asks the keyboard never to learn from the text fields in [content]: every input session they start carries
 * `IME_FLAG_NO_PERSONALIZED_LEARNING` (DEC-71, Q11; security.md § 11). Unconditional by design: no setting, window
 * policy or `CompositionLocal` switches it, so a wrapped field never moves in the composition.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoPersonalizedLearning(content: @Composable () -> Unit) {
    val interceptor = remember {
        PlatformTextInputInterceptor { request, next ->
            next.startInputMethod(
                object : PlatformTextInputMethodRequest {
                    override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                        request.createInputConnection(outAttributes).also {
                            outAttributes.imeOptions =
                                outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                        }
                },
            )
        }
    }
    InterceptPlatformTextInput(interceptor, content)
}
