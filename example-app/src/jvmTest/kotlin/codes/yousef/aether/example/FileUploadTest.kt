package codes.yousef.aether.example

import codes.yousef.aether.core.upload.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Unit tests for File Upload functionality.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileUploadTest {

    @Test
    fun testUploadConfig() {
        val config = UploadConfig(
            maxFileSize = 10 * 1024 * 1024,
            maxRequestSize = 50 * 1024 * 1024,
            maxFiles = 10,
            allowedContentTypes = setOf("image/png", "image/jpeg"),
            allowedExtensions = setOf("png", "jpg", "jpeg")
        )
        
        assertEquals(10 * 1024 * 1024, config.maxFileSize)
        assertEquals(50 * 1024 * 1024, config.maxRequestSize)
        assertEquals(10, config.maxFiles)
        assertTrue("image/png" in config.allowedContentTypes)
        assertTrue("png" in config.allowedExtensions)
    }

    @Test
    fun testDefaultUploadConfig() {
        val config = UploadConfig()
        
        assertEquals(10 * 1024 * 1024, config.maxFileSize)  // 10MB default
        assertEquals(50 * 1024 * 1024, config.maxRequestSize)  // 50MB default
        assertEquals(10, config.maxFiles)  // 10 files default
        assertTrue(config.allowedContentTypes.isEmpty())  // Allow all by default
        assertTrue(config.allowedExtensions.isEmpty())  // Allow all by default
    }

    @Test
    fun testMultipartFormField() {
        val field = MultipartPart.FormField(
            name = "username",
            value = "testuser",
            contentType = "text/plain"
        )
        
        assertEquals("username", field.name)
        assertEquals("testuser", field.value)
        assertEquals("text/plain", field.contentType)
    }

    @Test
    fun testMultipartFilePart() = runBlocking {
        val content = "Hello, World!".toByteArray()
        val filePart = MultipartPart.FilePart(
            name = "document",
            filename = "test.txt",
            contentType = "text/plain",
            size = content.size.toLong(),
            dataProvider = { content }
        )
        
        assertEquals("document", filePart.name)
        assertEquals("test.txt", filePart.filename)
        assertEquals("text/plain", filePart.contentType)
        assertEquals(content.size.toLong(), filePart.size)
        
        val bytes = filePart.bytes()
        assertTrue(content.contentEquals(bytes))
        
        val text = filePart.text()
        assertEquals("Hello, World!", text)
    }

    @Test
    fun testMultipartData() = runBlocking {
        val parts = listOf(
            MultipartPart.FormField("title", "My Document"),
            MultipartPart.FormField("description", "Test file"),
            MultipartPart.FilePart(
                name = "file",
                filename = "doc.pdf",
                contentType = "application/pdf",
                size = 1024,
                dataProvider = { ByteArray(1024) }
            )
        )
        
        val multipartData = MultipartData(parts)
        
        // Test all()
        assertEquals(3, multipartData.all().size)
        
        // Test formFields()
        val fields = multipartData.formFields()
        assertEquals(2, fields.size)
        
        // Test files()
        val files = multipartData.files()
        assertEquals(1, files.size)
        
        // Test getValue()
        assertEquals("My Document", multipartData.getValue("title"))
        assertEquals("Test file", multipartData.getValue("description"))
        
        // Test file()
        val file = multipartData.file("file")
        assertNotNull(file)
        assertEquals("doc.pdf", file.filename)
        
        // Test formField()
        val titleField = multipartData.formField("title")
        assertNotNull(titleField)
        assertEquals("My Document", titleField.value)
    }

    @Test
    fun testUploadException() {
        val exception = UploadException(
            message = "File too large",
            errorCode = UploadErrorCode.FILE_TOO_LARGE
        )
        
        assertEquals("File too large", exception.message)
        assertEquals(UploadErrorCode.FILE_TOO_LARGE, exception.errorCode)
    }

    @Test
    fun testUploadErrorCodes() {
        // Verify all error codes exist
        val codes = UploadErrorCode.values()
        
        assertTrue(UploadErrorCode.FILE_TOO_LARGE in codes)
        assertTrue(UploadErrorCode.REQUEST_TOO_LARGE in codes)
        assertTrue(UploadErrorCode.TOO_MANY_FILES in codes)
        assertTrue(UploadErrorCode.INVALID_CONTENT_TYPE in codes)
        assertTrue(UploadErrorCode.INVALID_EXTENSION in codes)
        assertTrue(UploadErrorCode.PARSE_ERROR in codes)
        assertTrue(UploadErrorCode.IO_ERROR in codes)
    }
}
