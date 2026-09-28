package com.max.core.media

/**
 * Android: OkHttp, see [OkHttpMediaHttp] (needs `android.permission.INTERNET`). Not compiled in
 * this repository's CI yet (no Android SDK); see docs/architecture.md.
 */
actual fun defaultMediaHttp(config: MediaHttpConfig): MediaHttp = okHttpMediaHttp(config)
