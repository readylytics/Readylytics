package app.readylytics.health.domain.util

interface DomainLogSink {
    fun debug(
        tag: String,
        message: String,
    )

    fun error(
        tag: String,
        message: String,
        throwable: Throwable?,
    )
}

object DomainLogger {
    private object NoOpSink : DomainLogSink {
        override fun debug(
            tag: String,
            message: String,
        ) = Unit

        override fun error(
            tag: String,
            message: String,
            throwable: Throwable?,
        ) = Unit
    }

    @Volatile
    private var sink: DomainLogSink = NoOpSink

    fun installSink(newSink: DomainLogSink) {
        sink = newSink
    }

    fun debug(
        tag: String,
        message: String,
    ) {
        sink.debug(tag, message)
    }

    fun error(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        sink.error(tag, message, throwable)
    }
}

inline fun logD(
    tag: String,
    msg: () -> String,
) {
    DomainLogger.debug(tag, msg())
}

inline fun logE(
    tag: String,
    throwable: Throwable? = null,
    msg: () -> String,
) {
    DomainLogger.error(tag, msg(), throwable)
}
