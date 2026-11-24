package dev.slopbot.element.rest

/**
 * A minimal, binary-safe `multipart/form-data` parser for the asset upload
 * endpoints. Self-contained (no Jersey multipart module dependency): the
 * upload shape is small (one file part + a few text fields), and the parser
 * is unit-tested against binary payloads that embed `--` sequences.
 */
object SlopMultipart {

    /** One parsed part: the field name, the uploaded filename (file parts), the part's content type, and the bytes. */
    class Part(
        val name: String?,
        val filename: String?,
        val mimeType: String?,
        val bytes: ByteArray,
    )

    private val CRLFCRLF = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A)

    /**
     * Parses a multipart body into its parts. Throws [IllegalArgumentException]
     * on a missing boundary or an unterminated body.
     */
    fun parse(contentType: String?, body: ByteArray): List<Part> {
        val boundary = boundaryOf(contentType)
            ?: throw IllegalArgumentException("multipart content-type carries no boundary")
        val delimiter = "--$boundary".toByteArray(Charsets.US_ASCII)
        val parts = mutableListOf<Part>()

        var i = indexOf(body, delimiter, 0)
        if (i < 0) throw IllegalArgumentException("multipart body carries no boundary marker")

        while (true) {
            val lineEnd = i + delimiter.size
            // A "--" suffix on the delimiter line is the closing marker.
            if (lineEnd + 1 < body.size &&
                body[lineEnd] == '-'.code.toByte() &&
                body[lineEnd + 1] == '-'.code.toByte()
            ) break
            // The part's headers follow the delimiter line's CRLF.
            var start = lineEnd
            if (start + 1 < body.size &&
                body[start] == '\r'.code.toByte() &&
                body[start + 1] == '\n'.code.toByte()
            ) start += 2
            val next = indexOf(body, delimiter, start)
            if (next < 0) throw IllegalArgumentException("multipart body is unterminated")
            // The part ends just before the CRLF that precedes the next delimiter.
            if (next >= start + 2) parts.add(partOf(body, start, next - 2))
            i = next
        }
        return parts
    }

    /** The `boundary=` value of a multipart content-type header (quoted or bare). */
    fun boundaryOf(contentType: String?): String? {
        if (contentType == null) return null
        val match = Regex("boundary=(?:\"([^\"]+)\"|([^;,\\s]+))").find(contentType) ?: return null
        return match.groupValues[1].ifEmpty { match.groupValues[2] }
    }

    private fun partOf(body: ByteArray, from: Int, to: Int): Part {
        val headerEnd = indexOf(body, CRLFCRLF, from)
        val hasHeaderEnd = headerEnd in from..(to - CRLFCRLF.size)
        val headerBlock = if (hasHeaderEnd) {
            String(body, from, headerEnd - from, Charsets.UTF_8)
        } else {
            String(body, from, to - from, Charsets.UTF_8)
        }
        val content = if (hasHeaderEnd) {
            body.copyOfRange(headerEnd + CRLFCRLF.size, to)
        } else {
            ByteArray(0)
        }
        var name: String? = null
        var filename: String? = null
        var mimeType: String? = null
        for (line in headerBlock.split("\r\n")) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val header = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            when (header) {
                "content-disposition" -> {
                    name = param(value, "name")
                    filename = param(value, "filename")
                }
                "content-type" -> mimeType = value
            }
        }
        return Part(name, filename, mimeType, content)
    }

    private fun param(value: String, key: String): String? {
        val match = Regex("""$key=(?:"([^"]*)"|([^;\s]+))""").find(value) ?: return null
        return match.groupValues[1].ifEmpty { match.groupValues[2] }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
