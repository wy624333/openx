package com.wy.openx.data

import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

fun Throwable.readableMessage(): String {
    return when (this) {
        is CancellationException -> throw this
        is HttpException -> {
            val body = try {
                response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            "HTTP ${code()} ${message()}${if (!body.isNullOrBlank()) "\n$body" else ""}"
        }
        else -> message ?: this::class.java.simpleName
    }
}
