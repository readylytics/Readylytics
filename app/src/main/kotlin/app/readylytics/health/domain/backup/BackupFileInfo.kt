package app.readylytics.health.domain.backup

@JvmInline
value class BackupLocation(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Backup location must not be blank" }
    }

    override fun toString(): String = value
}

data class BackupFileRef(
    val name: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val location: BackupLocation,
)

typealias BackupFileInfo = BackupFileRef
