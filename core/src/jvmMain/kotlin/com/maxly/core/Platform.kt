package com.maxly.core

internal actual fun platformName(): String = "jvm"

internal actual fun epochMillis(): Long = System.currentTimeMillis()
