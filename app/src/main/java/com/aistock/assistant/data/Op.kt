package com.aistock.assistant.data

import androidx.room.withTransaction
import com.aistock.assistant.database.AppDatabase
import kotlin.coroutines.cancellation.CancellationException

/** Result of a business operation. Errors are codes, never exceptions, so screens and the assistant can explain them. */
sealed class Op<out T> {
    data class Ok<T>(val value: T) : Op<T>()
    data class Err(val code: String) : Op<Nothing>()

    val isOk: Boolean get() = this is Ok
    fun valueOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): String? = (this as? Err)?.code
}

object ErrCode {
    const val INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK"
    const val NOT_FOUND = "NOT_FOUND"
    const val DUPLICATE = "DUPLICATE"
    const val INVALID = "INVALID"
    const val NEEDS_PARTY = "NEEDS_PARTY"
    const val ALREADY_DONE = "ALREADY_DONE"
    const val FAILED = "FAILED"
    /** The free trial or plan has ended: records can be seen but not changed until the owner pays. */
    const val READ_ONLY = "READ_ONLY"
}

class BizException(val code: String) : Exception(code)

fun fail(code: String): Nothing = throw BizException(code)

fun <T> Op<T>.orThrow(): T = when (this) {
    is Op.Ok -> value
    is Op.Err -> throw BizException(code)
}

/** Runs [block] in one database transaction. Any failure rolls everything back and is reported as an error code. */
suspend fun <T> AppDatabase.op(block: suspend () -> T): Op<T> = if (Access.readOnly()) Op.Err(ErrCode.READ_ONLY) else try {
    Op.Ok(withTransaction { block() })
} catch (e: BizException) {
    Op.Err(e.code)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Op.Err(ErrCode.FAILED)
}

/** Business rules that can be changed in Settings. */
interface StockRules {
    fun allowNegativeStock(): Boolean
}

fun newId(): String = java.util.UUID.randomUUID().toString()
