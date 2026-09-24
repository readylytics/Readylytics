#!/bin/bash
# Patch SourcePayloadWriter
sed -i '' -e 's/private suspend fun recordDirtyRange(/private suspend fun recordDirtyRange(\n            sourceRef: Long,\n            daos: HealthRecordDaos,/g' core/database/src/main/kotlin/app/readylytics/health/core/database/data/local/SourcePayloadWriter.kt

# Oh wait, this is too hard to write with sed. I will use replace_file_content!
