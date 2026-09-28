package com.max.core.media

/** JVM (desktop): OkHttp, see [OkHttpMediaHttp]. */
actual fun defaultMediaHttp(config: MediaHttpConfig): MediaHttp = okHttpMediaHttp(config)
