package app.readylytics.health.domain.util

enum class LogLevel { INFO, WARN, ERROR }

data class LogContext(val sessionId: String? = null)

interface DomainLogSink {
    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
        context: LogContext
    )
}

object DomainLogger {
    private object NoOpSink : DomainLogSink {
        override fun log(
            level: LogLevel,
            tag: String,
            message: String,
            throwable: Throwable?,
            context: LogContext
        ) = Unit
    }

    @Volatile
    private var sink: DomainLogSink = NoOpSink

    fun installSink(newSink: DomainLogSink) {
        sink = newSink
    }

    fun log(
        level: LogLevel,
        tag: String,
        throwable: Throwable? = null,
        context: LogContext = LogContext(),
        msg: () -> String
    ) {
        sink.log(level, tag, msg(), throwable, context)
    }
}

class ScopedLogger(val tag: String, val context: LogContext) {
    fun info(msg: () -> String) = DomainLogger.log(LogLevel.INFO, tag, context = context, msg = msg)
    fun warn(throwable: Throwable? = null, msg: () -> String) = DomainLogger.log(LogLevel.WARN, tag, throwable, context, msg)
    fun error(throwable: Throwable? = null, msg: () -> String) = DomainLogger.log(LogLevel.ERROR, tag, throwable, context, msg)
}

inline fun DomainLogger.scoped(
    tag: String,
    correlationId: String?,
    block: ScopedLogger.() -> Unit
) {
    val logger = ScopedLogger(tag, LogContext(correlationId))
    logger.block()
}

inline fun logD(
    tag: String,
    noinline msg: () -> String,
) {
    DomainLogger.log(LogLevel.INFO, tag, throwable = null, context = LogContext(), msg = msg)
}

inline fun logW(
    tag: String,
    throwable: Throwable? = null,
    noinline msg: () -> String,
) {
    DomainLogger.log(LogLevel.WARN, tag, throwable, LogContext(), msg)
}

inline fun logE(
    tag: String,
    throwable: Throwable? = null,
    noinline msg: () -> String,
) {
    DomainLogger.log(LogLevel.ERROR, tag, throwable, LogContext(), msg)
}
