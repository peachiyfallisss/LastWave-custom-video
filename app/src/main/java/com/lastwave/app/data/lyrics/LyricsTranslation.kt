package com.lastwave.app.data.lyrics

/** Preserve row identities and keep requests below Google's recommended small-request size. */
fun lyricTranslationBatches(lines: List<String>): List<List<Int>> {
    val batches = mutableListOf<List<Int>>()
    var batch = mutableListOf<Int>()
    var characters = 0
    lines.forEachIndexed { index, line ->
        if (line.isBlank()) return@forEachIndexed
        val count = line.codePointCount(0, line.length)
        require(count <= 4500) { "Lyric line ${index + 1} is too long to translate. Split it into shorter lines." }
        if (batch.size >= 50 || characters + count > 4500) {
            batches += batch.toList(); batch = mutableListOf(); characters = 0
        }
        batch += index; characters += count
    }
    if (batch.isNotEmpty()) batches += batch.toList()
    return batches
}

fun decodeLyricTranslation(text: String): String = Regex("&(?:#x[0-9a-fA-F]+|#\\d+|amp|quot|apos|lt|gt|nbsp);").replace(text) { match ->
    val entity = match.value.removePrefix("&").removeSuffix(";")
    when (entity) {
        "amp" -> "&"; "quot" -> "\""; "apos" -> "'"; "lt" -> "<"; "gt" -> ">"; "nbsp" -> " "
        else -> runCatching {
            val code = if (entity.startsWith("#x")) entity.drop(2).toInt(16) else entity.drop(1).toInt()
            String(Character.toChars(code))
        }.getOrDefault(match.value)
    }
}
