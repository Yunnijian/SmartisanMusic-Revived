package com.smartisan.music

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal object AppDispatchers {
    val IO: CoroutineDispatcher
        get() = Dispatchers.IO
}
