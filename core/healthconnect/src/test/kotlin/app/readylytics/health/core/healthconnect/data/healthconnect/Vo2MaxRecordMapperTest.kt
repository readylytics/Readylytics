package app.readylytics.health.core.healthconnect.data.healthconnect

import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import app.readylytics.health.core.model.domain.model.DomainVo2MaxRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class Vo2MaxRecordMapperTest {
    @Test
    fun mapsSdkVo2MaxRecordToDomain() {
        val now = Instant.parse("2026-09-03T10:00:00Z")
        val sdkRecord =
            Vo2MaxRecord(
                time = now,
                zoneOffset = null,
                vo2MillilitersPerMinuteKilogram = 48.5,
                measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_HEART_RATE_RATIO,
                metadata =
                    Metadata.autoRecordedWithId(
                        id = "test-vo2-123",
                        device =
                            Device(
                                type = Device.TYPE_WATCH,
                                manufacturer = "Google",
                                model = "Pixel Watch",
                            ),
                    ),
            )

        val domain = sdkRecord.toDomain()

        assertEquals("test-vo2-123", domain.id)
        assertEquals(now, domain.time)
        assertEquals(48.5, domain.vo2MillilitersPerMinuteKilogram, 0.001)
        assertEquals(Vo2MaxRecord.MEASUREMENT_METHOD_HEART_RATE_RATIO, domain.measurementMethod)
        assertEquals("Pixel Watch", domain.deviceName)
    }

    @Test
    fun mapsSdkVo2MaxRecordToDomainWithNullDevice() {
        val now = Instant.parse("2026-09-03T10:00:00Z")
        val sdkRecord =
            Vo2MaxRecord(
                time = now,
                zoneOffset = null,
                vo2MillilitersPerMinuteKilogram = 48.5,
                measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_HEART_RATE_RATIO,
                metadata =
                    Metadata.autoRecordedWithId(
                        id = "test-vo2-123",
                        device = Device(type = Device.TYPE_UNKNOWN, manufacturer = null, model = null)
                    ),
            )

        val domain = sdkRecord.toDomain()

        assertEquals("", domain.deviceName)
    }

    @Test
    fun mapsSdkVo2MaxRecordToDomainWithFallbackDeviceManufacturer() {
        val now = Instant.parse("2026-09-03T10:00:00Z")
        val sdkRecord =
            Vo2MaxRecord(
                time = now,
                zoneOffset = null,
                vo2MillilitersPerMinuteKilogram = 48.5,
                measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_HEART_RATE_RATIO,
                metadata =
                    Metadata.autoRecordedWithId(
                        id = "test-vo2-123",
                        device =
                            Device(
                                type = Device.TYPE_WATCH,
                                manufacturer = "Google",
                                model = null,
                            ),
                    ),
            )

        val domain = sdkRecord.toDomain()

        assertEquals("Google", domain.deviceName)
    }
}
