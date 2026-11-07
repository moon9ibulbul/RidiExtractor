package com.example.ridiextractor

import org.zeroturnaround.zip.ZipUtil
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class BookMetadata(
    val title: String,
    val author: String,
    val coverBytes: ByteArray?
)

object BookExtractor {

    fun extractUuidFromPreferences(xmlText: String): String? {
        if (!xmlText.contains("<string name=\"uuid\">")) {
            return null
        }
        val uuid = xmlText.substringAfter("<string name=\"uuid\">").substringBefore("</string>").trim()
        return if (uuid.isNotEmpty()) uuid else null
    }

    fun inspectZip(inputStream: InputStream): BookMetadata {
        var title: String? = null
        var author: String? = null
        var coverBytes: ByteArray? = null

        val zis = ZipInputStream(inputStream)
        var entry: ZipEntry? = zis.nextEntry
        val buffer = ByteArray(8192)

        while (entry != null) {
            val name = entry.name.replace('\\', '/')
            if (!entry.isDirectory) {
                if (name.endsWith("content.opf", ignoreCase = true)) {
                    val baos = ByteArrayOutputStream()
                    var len: Int
                    while (zis.read(buffer).also { len = it } > 0) {
                        baos.write(buffer, 0, len)
                    }
                    val opfText = baos.toString("UTF-8")
                    title = parseTagValue(opfText, "dc:title")
                    author = parseCreator(opfText)
                } else if (name.endsWith("cover.jpg", ignoreCase = true) ||
                    name.endsWith("cover.png", ignoreCase = true) ||
                    name.endsWith("cover.jpeg", ignoreCase = true)) {
                    val baos = ByteArrayOutputStream()
                    var len: Int
                    while (zis.read(buffer).also { len = it } > 0) {
                        baos.write(buffer, 0, len)
                    }
                    coverBytes = baos.toByteArray()
                }
            }
            zis.closeEntry()
            if (title != null && coverBytes != null) {
                break
            }
            entry = zis.nextEntry
        }

        return BookMetadata(
            title = title ?: "Unknown Title",
            author = author ?: "Unknown Author",
            coverBytes = coverBytes
        )
    }

    private fun parseTagValue(xml: String, tagName: String): String? {
        if (!xml.contains("<$tagName")) return null
        val afterTag = xml.substringAfter("<$tagName")
        val content = afterTag.substringAfter(">").substringBefore("</$tagName>").trim()
        return if (content.isNotEmpty()) content else null
    }

    private fun parseCreator(xml: String): String? {
        if (xml.contains("<dc:creator opf:role=\"aut\">")) {
            val author = xml.substringAfter("<dc:creator opf:role=\"aut\">").substringBefore("</dc:creator>").trim()
            if (author.isNotEmpty()) return author
        }
        return parseTagValue(xml, "dc:creator")
    }

    fun findRootContentDir(rootDir: File): File {
        val opfFiles = rootDir.walkTopDown().filter { it.isFile && it.name.equals("content.opf", ignoreCase = true) }.toList()
        if (opfFiles.isNotEmpty()) {
            val opf = opfFiles.first()
            val parent = opf.parentFile
            if (parent != null && parent.name.equals("OEBPS", ignoreCase = true)) {
                return parent.parentFile ?: rootDir
            }
            return parent ?: rootDir
        }
        val oebpsDirs = rootDir.walkTopDown().filter { it.isDirectory && it.name.equals("OEBPS", ignoreCase = true) }.toList()
        if (oebpsDirs.isNotEmpty()) {
            return oebpsDirs.first().parentFile ?: rootDir
        }
        return rootDir
    }

    fun decryptAndPackage(
        zipFile: File,
        datFile: File,
        deviceId: String,
        outputEpubFile: File,
        tempWorkDir: File
    ): BookMetadata {
        if (tempWorkDir.exists()) {
            tempWorkDir.deleteRecursively()
        }
        tempWorkDir.mkdirs()

        try {
            // Unpack zip
            ZipUtil.unpack(zipFile, tempWorkDir)

            // Locate book root directory
            val bookRootDir = findRootContentDir(tempWorkDir)

            // Parse metadata
            val opfFile = bookRootDir.walkTopDown().firstOrNull { it.isFile && it.name.equals("content.opf", ignoreCase = true) }
            var title = "Decrypted_Book"
            var author = "Unknown"
            if (opfFile != null && opfFile.exists()) {
                val opfText = opfFile.readText(Charsets.UTF_8)
                title = parseTagValue(opfText, "dc:title") ?: title
                author = parseCreator(opfText) ?: author
            }

            // Generate decryption key
            val key = RidiDecrypter.generateKey(deviceId, datFile)
                ?: throw IllegalStateException("Failed to generate decryption key. Please verify UUID and .dat file.")

            // Decrypt HTML/XHTML files
            val textDir = File(bookRootDir, "OEBPS/Text")
            val htmlFiles = if (textDir.exists()) {
                textDir.walkTopDown().filter {
                    it.isFile && (it.extension == "xhtml" || it.extension == "html" || it.extension == "htm")
                }.toList()
            } else {
                bookRootDir.walkTopDown().filter {
                    it.isFile && (it.extension == "xhtml" || it.extension == "html" || it.extension == "htm")
                }.toList()
            }

            for (file in htmlFiles) {
                var text = RidiDecrypter.decryptHtml(file, key)
                text = Regex("([\\s\\S])\\1+$").replace(text, "")
                file.writeText(text, Charsets.UTF_8)
            }

            // Remove backup/redundant files
            val backupFiles = bookRootDir.walkTopDown().filter {
                it.isFile && (it.name.endsWith(".ridibackup", ignoreCase = true) || it.name.endsWith(".ridi", ignoreCase = true))
            }.toList()
            for (file in backupFiles) {
                file.delete()
            }

            // Pack into EPUB
            if (outputEpubFile.exists()) {
                outputEpubFile.delete()
            }
            outputEpubFile.parentFile?.mkdirs()
            ZipUtil.pack(bookRootDir, outputEpubFile)

            // Find cover if present
            var coverBytes: ByteArray? = null
            val coverFile = bookRootDir.walkTopDown().firstOrNull {
                it.isFile && (it.name.equals("cover.jpg", ignoreCase = true) || it.name.equals("cover.png", ignoreCase = true))
            }
            if (coverFile != null && coverFile.exists()) {
                coverBytes = coverFile.readBytes()
            }

            return BookMetadata(title = title, author = author, coverBytes = coverBytes)
        } finally {
            tempWorkDir.deleteRecursively()
        }
    }
}
