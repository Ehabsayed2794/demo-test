package com.estemshan.engine

/**
 * Wall-clock milliseconds. Declared `expect` so the engine stays portable: the
 * JVM target (:app, :services) uses [System.currentTimeMillis], the JS/Node
 * target — the Functions layer, docs/adr/0002-functions-runtime.md — uses the JS
 * Date clock. [GameSession] injects it, so tests substitute a deterministic
 * clock rather than calling this directly.
 */
expect fun nowMillis(): Long
