package com.estemshan.engine

import kotlin.js.Date

actual fun nowMillis(): Long = Date.now().toLong()
