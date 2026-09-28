package com.max.core

internal actual fun platformName(): String = "jvm"

internal actual fun epochMillis(): Long = System.currentTimeMillis()
