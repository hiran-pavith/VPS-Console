package com.vpsconsole.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.vpsconsole.app.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer is not provided")
}
