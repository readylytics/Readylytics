package com.gregor.lauritz.healthdashboard.data.drive

import io.mockk.*
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response
import java.io.ByteArrayInputStream

class GoogleDriveRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val apiService = mockk<DriveApiService>()
    private val repository = GoogleDriveRepository(apiService)

    @Test
    fun `downloadBackup should stream data to file`() =
        runTest {
            val fileId = "test_file_id"
            val accessToken = "test_token"
            val destFile = tempFolder.newFile("dest.zip")
            val content = "test content".toByteArray()
            val inputStream = ByteArrayInputStream(content)

            val responseBody = mockk<ResponseBody>()
            every { responseBody.byteStream() } returns inputStream
            // Mockito/MockK doesn't mock close() well on some types, but let's try
            every { responseBody.close() } just Runs

            val response = Response.success(responseBody)
            coEvery { apiService.downloadFile(any(), any()) } returns response

            repository.downloadBackup(accessToken, fileId, destFile)

            assertEquals("test content", destFile.readText())
            verify { responseBody.byteStream() }
        }
}
