package com.yancotv.shared.types

/** Mirrors the TS discriminated union `Result<T, E = Error>` from `@yancotv/core`. */
sealed class Result<out T, out E> {
    data class Ok<out T>(val value: T) : Result<T, Nothing>()
    data class Err<out E>(val error: E) : Result<Nothing, E>()

    val ok: Boolean get() = this is Ok
}

fun <T> ok(value: T): Result<T, Nothing> = Result.Ok(value)
fun <E> err(error: E): Result<Nothing, E> = Result.Err(error)
