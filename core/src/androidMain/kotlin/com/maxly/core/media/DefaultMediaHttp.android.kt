package com.maxly.core.media

/**
 * Android: OkHttp, see [OkHttpMediaHttp] (needs `android.permission.INTERNET`).
 * `androidMain` is compiled in CI and is not run on a device; see docs/architecture.md.
 */
actual fun defaultMediaHttp(config: MediaHttpConfig): MediaHttp = okHttpMediaHttp(config)
