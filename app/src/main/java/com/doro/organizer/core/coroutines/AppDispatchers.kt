package com.doro.organizer.core.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The dispatchers every component receives; the only place that names [Dispatchers] (architecture.md § Threading). */
class AppDispatchers(
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
) {
    companion object {
        fun production() = AppDispatchers(
            main = Dispatchers.Main.immediate,
            io = Dispatchers.IO,
            default = Dispatchers.Default,
        )
    }
}
