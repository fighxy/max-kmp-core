package com.max.core

internal actual fun platformName(): String = "android"

internal actual fun epochMillis(): Long = System.currentTimeMillis()
