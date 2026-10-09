package com.maxly.core

internal expect fun platformName(): String

/** Wall-clock time in milliseconds since the Unix epoch (for `cid`, `from`, `mark`, ... in requests). */
internal expect fun epochMillis(): Long
