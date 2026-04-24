package com.yancotv.shared.logger

/** Minimal logger interface. Platforms inject their own implementation. Mirrors `Logger` in @yancotv/core. */
interface Logger {
    fun info(msg: String)

    fun warn(msg: String)

    fun error(msg: String)
}

val NOOP_LOGGER: Logger =
    object : Logger {
        override fun info(msg: String) {}

        override fun warn(msg: String) {}

        override fun error(msg: String) {}
    }
