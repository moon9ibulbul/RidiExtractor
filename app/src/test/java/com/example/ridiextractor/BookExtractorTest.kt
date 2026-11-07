package com.example.ridiextractor

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.zeroturnaround.zip.ZipUtil
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class BookExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testExtractUuidFromPreferences() {
        val xmlSample = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="uuid">test-uuid-12345678-abcd</string>
                <int name="app_version" value="100" />
            </map>
        """.trimIndent()

        val uuid = BookExtractor.extractUuidFromPreferences(xmlSample)
        assertEquals("test-uuid-12345678-abcd", uuid)
    }

    @Test
    fun testExtractUuidNotFound() {
        val xmlSample = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <string name="other">xyz</string>
            </map>
        """.trimIndent()

        val uuid = BookExtractor.extractUuidFromPreferences(xmlSample)
        assertNull(uuid)
    }

    @Test
    fun testInspectZipMetadataAndCover() {
        val zipFile = tempFolder.newFile("test_book.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            // Add content.opf
            val opfContent = """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                    <dc:title>Test Novel Title</dc:title>
                    <dc:creator opf:role="aut">Famous Author</dc:creator>
                  </metadata>
                </package>
            """.trimIndent()
            zos.putNextEntry(ZipEntry("extracted/OEBPS/content.opf"))
            zos.write(opfContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // Add cover.jpg
            val dummyCover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
            zos.putNextEntry(ZipEntry("extracted/OEBPS/Images/cover.jpg"))
            zos.write(dummyCover)
            zos.closeEntry()
        }

        val metadata = zipFile.inputStream().use { BookExtractor.inspectZip(it) }
        assertEquals("Test Novel Title", metadata.title)
        assertEquals("Famous Author", metadata.author)
        assertNotNull(metadata.coverBytes)
        assertEquals(4, metadata.coverBytes!!.size)
    }

    @Test
    fun testKeyGenerationAndHtmlDecryption() {
        // Prepare 16+ byte UUID
        val uuid = "1234567890abcdef123456"
        val aesKeyBytes = uuid.substring(0, 16).toByteArray(Charsets.UTF_8)

        // Key to decrypt HTML (16 bytes)
        val htmlKeyString = "mysecretkey12345"
        val htmlKeyBytes = htmlKeyString.toByteArray(Charsets.UTF_8)

        // Structure of decrypted DAT payload:
        // str (uuid.length) + 32 bytes pad/unknown + 16 bytes key
        val datDecryptedBuffer = ByteArray(uuid.length + 32 + 16)
        System.arraycopy(uuid.toByteArray(Charsets.UTF_8), 0, datDecryptedBuffer, 0, uuid.length)
        System.arraycopy(htmlKeyBytes, 0, datDecryptedBuffer, uuid.length + 32, 16)

        // Encrypt DAT payload using AES/ECB/NoPadding
        // Must pad to block size (16)
        val paddedLen = ((datDecryptedBuffer.size + 15) / 16) * 16
        val datPadded = ByteArray(paddedLen)
        System.arraycopy(datDecryptedBuffer, 0, datPadded, 0, datDecryptedBuffer.size)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"))
        val encryptedDat = cipher.doFinal(datPadded)

        val datFile = tempFolder.newFile("test.dat")
        datFile.writeBytes(encryptedDat)

        // Verify key generation
        val generatedKey = RidiDecrypter.generateKey(uuid, datFile)
        assertNotNull(generatedKey)
        assertArrayEquals(htmlKeyBytes, generatedKey)

        // Test decrypting an encrypted HTML file
        val plainHtml = "<html><body>Hello World!</body></html>"
        val plainBytes = plainHtml.toByteArray(Charsets.UTF_8)
        val htmlBlockCount = ((plainBytes.size + 15) / 16)
        val htmlPadded = ByteArray((htmlBlockCount + 1) * 16) // plus PKCS-style or ECB padding
        System.arraycopy(plainBytes, 0, htmlPadded, 0, plainBytes.size)
        val padByte = (htmlPadded.size - plainBytes.size).toByte()
        for (i in plainBytes.size until htmlPadded.size) {
            htmlPadded[i] = padByte
        }

        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(htmlKeyBytes, "AES"))
        val encryptedHtml = cipher.doFinal(htmlPadded)

        val htmlFile = tempFolder.newFile("test.html")
        htmlFile.writeBytes(encryptedHtml)

        var decrypted = RidiDecrypter.decryptHtml(htmlFile, generatedKey)
        decrypted = Regex("([\\s\\S])\\1+$").replace(decrypted, "")
        assertEquals(plainHtml, decrypted)
    }

    @Test
    fun testDecryptAndPackageEndToEnd() {
        val uuid = "1234567890abcdef123456"
        val aesKeyBytes = uuid.substring(0, 16).toByteArray(Charsets.UTF_8)
        val htmlKeyBytes = "mysecretkey12345".toByteArray(Charsets.UTF_8)

        // Prepare DAT
        val datDecryptedBuffer = ByteArray(uuid.length + 32 + 16)
        System.arraycopy(uuid.toByteArray(Charsets.UTF_8), 0, datDecryptedBuffer, 0, uuid.length)
        System.arraycopy(htmlKeyBytes, 0, datDecryptedBuffer, uuid.length + 32, 16)
        val paddedLen = ((datDecryptedBuffer.size + 15) / 16) * 16
        val datPadded = ByteArray(paddedLen)
        System.arraycopy(datDecryptedBuffer, 0, datPadded, 0, datDecryptedBuffer.size)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKeyBytes, "AES"))
        val encryptedDat = cipher.doFinal(datPadded)

        val datFile = tempFolder.newFile("sample.dat")
        datFile.writeBytes(encryptedDat)

        // Encrypt test HTML
        val plainHtml = "<html><body>Chapter 1 content</body></html>"
        val plainBytes = plainHtml.toByteArray(Charsets.UTF_8)
        val htmlPadded = ByteArray(((plainBytes.size + 15) / 16 + 1) * 16)
        System.arraycopy(plainBytes, 0, htmlPadded, 0, plainBytes.size)
        val padByte = (htmlPadded.size - plainBytes.size).toByte()
        for (i in plainBytes.size until htmlPadded.size) {
            htmlPadded[i] = padByte
        }
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(htmlKeyBytes, "AES"))
        val encryptedHtml = cipher.doFinal(htmlPadded)

        // Build ZIP
        val zipFile = tempFolder.newFile("book.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("extracted/OEBPS/content.opf"))
            val opfContent = """
                <?xml version="1.0" encoding="utf-8"?>
                <package>
                  <dc:title>Sample Novel</dc:title>
                  <dc:creator>Sample Writer</dc:creator>
                </package>
            """.trimIndent()
            zos.write(opfContent.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("extracted/OEBPS/Text/ch01.xhtml"))
            zos.write(encryptedHtml)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("extracted/OEBPS/Images/cover.jpg"))
            zos.write(byteArrayOf(1, 2, 3, 4))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("extracted/OEBPS/Styles/style.css.ridibackup"))
            zos.write("backup content".toByteArray())
            zos.closeEntry()
        }

        val outEpub = tempFolder.newFile("output.epub")
        val workDir = tempFolder.newFolder("work")

        val resultMetadata = BookExtractor.decryptAndPackage(
            zipFile = zipFile,
            datFile = datFile,
            deviceId = uuid,
            outputEpubFile = outEpub,
            tempWorkDir = workDir
        )

        assertEquals("Sample Novel", resultMetadata.title)
        assertEquals("Sample Writer", resultMetadata.author)
        assertTrue(outEpub.exists())
        assertTrue(outEpub.length() > 0)

        // Unpack epub and check that decrypted text is present and backup is removed
        val unpackDir = tempFolder.newFolder("unpacked_epub")
        ZipUtil.unpack(outEpub, unpackDir)

        val ch01File = File(unpackDir, "OEBPS/Text/ch01.xhtml")
        assertTrue(ch01File.exists())
        assertEquals(plainHtml, ch01File.readText(Charsets.UTF_8))

        val backupFile = File(unpackDir, "OEBPS/Styles/style.css.ridibackup")
        assertFalse(backupFile.exists())
    }
}
