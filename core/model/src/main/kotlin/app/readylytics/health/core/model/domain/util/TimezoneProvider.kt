package app.readylytics.health.core.model.domain.util

import kotlinx.coroutines.flow.Flow
import java.time.ZoneId

interface TimezoneProvider {
    val timezone: Flow<ZoneId>
}
