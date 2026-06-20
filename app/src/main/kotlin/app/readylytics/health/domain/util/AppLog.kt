package app.readylytics.health.domain.util

interface DomainLogSink {
    fun debug(
        tag: String,
        message: String,
    )

    fun warn(
        tag: String,
        message: String,
        throwable: Throwable?,
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

        override fun warn(
            tag: String,
            message: String,
            throwable: Throwable?,
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

    fun warn(
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        sink.warn(tag, message, throwable)
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

inline fun logW(
    tag: String,
    throwable: Throwable? = null,
    msg: () -> String,
) {
    DomainLogger.warn(tag, msg(), throwable)
}

inline fun logE(
    tag: String,
    throwable: Throwable? = null,
    msg: () -> String,
) {
    DomainLogger.error(tag, msg(), throwable)
}
