package com.crunchyme

/** Tiny log facade so pure Kotlin code stays runnable on the JVM. */
object Logx {
    @Volatile
    var sink: (String) -> Unit = {}

    fun d(tag: String, message: String) = sink("D/$tag: $message")

    fun w(tag: String, message: String) = sink("W/$tag: $message")
}
