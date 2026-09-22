package com.smartisan.music.ui.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放页返回键的**结构守护**：`ui/playback` 包内有且只有一个 `BackHandler` 注册，且它必须走统一仲裁。
 *
 * 这不是行为断言，而是补上行为断言够不到的那一段：[playbackBackTarget] 的 8 组真值表覆盖了仲裁逻辑本身，
 * 但「谁注册了返回键」在 JVM 层没有别的观测手段。原先 `PlaybackPage` 里的
 * `BackHandler(enabled = queueVisible)` 与 [PlaybackScreenSessionEffects] 里的处理器并列注册，
 * 后者注册更晚，而 `OnBackPressedDispatcher` 只回调最后注册且 enabled 的那一个，
 * 于是队列分支从未生效——队列可见时按返回直接把整个播放页收起。
 * 把内层处理器加回去既不报错、也不影响任何现有测试（编译通过、真值表照旧全绿），
 * 所以这条回归只能靠扫源码结构守住：数量不对、位置不对、带了 `enabled` 条件就失败。
 *
 * 覆盖边界：只做源码文本扫描（先剔除注释与字符串字面量，两种调用写法 `BackHandler { }` 与
 * `BackHandler(enabled = ...) { }` 都算一次注册），不覆盖返回键在设备上的实际派发，
 * 也不覆盖 `BackHandler` 体内四个分支到 host 动作的映射（那部分由 [playbackBackTarget] 的真值表覆盖）。
 *
 * 工作目录：Gradle 单测的 `user.dir` 是 app 模块目录（与 PlaybackStatsMigrationTest 读 `schemas/` 一致），
 * 这里仍从 `user.dir` 逐级向上找，从仓库根目录运行也能命中。
 */
class PlaybackBackHandlerStructureTest {

    @Test
    fun playbackPackageRegistersExactlyOneBackHandler() {
        val registrations = allBackHandlerRegistrations()

        assertEquals(
            "ui/playback 内只允许一个 BackHandler 注册（唯一仲裁入口），实际注册：${registrations.describe()}",
            listOf("PlaybackScreenEffects.kt"),
            registrations.map(BackHandlerRegistration::fileName),
        )
    }

    @Test
    fun playbackPageRegistersNoBackHandler() {
        val page = playbackSourceFile("PlaybackPage.kt")

        assertTrue(
            "结构守护的对象是 PlaybackPage.kt，文件不在说明扫描路径错了：${page.absolutePath}",
            page.isFile,
        )
        assertEquals(
            "PlaybackPage 里那个 `BackHandler(enabled = queueVisible)` 正是缺陷来源：" +
                "它与播放页处理器并列注册，注册更晚的后者永远赢下派发，队列分支从未生效——不能再加回来",
            emptyList<BackHandlerRegistration>(),
            backHandlerRegistrations(page),
        )
    }

    @Test
    fun theOnlyBackHandlerIsUnconditional() {
        val registration = onlyBackHandlerRegistration()

        assertFalse(
            "唯一的处理器不能带 enabled 条件：仲裁函数已保证每个状态都有目标，加条件只会让返回键在某些状态下失效",
            registration.arguments.contains("enabled"),
        )
    }

    @Test
    fun theOnlyBackHandlerConsultsTheSharedArbitration() {
        val effects = playbackSourceFile("PlaybackScreenEffects.kt")
        val code = effects.readText().stripCommentsAndStringLiterals()
        val registration = onlyBackHandlerRegistration()

        val arbitrationCalls =
            ArbitrationName.findAll(code)
                .filter { match -> callAfter(code, match.range.last + 1) != null }
                .toList()

        assertEquals(
            "返回键处理器里应恰好调用一次仲裁函数，实际：${arbitrationCalls.map { lineOf(code, it.range.first) }}",
            1,
            arbitrationCalls.size,
        )
        assertEquals(
            "唯一的处理器必须经过 playbackBackTarget 仲裁，不能自己再写一套 when",
            registration.enclosingFunction,
            enclosingFunctionOf(code, arbitrationCalls.single().range.first),
        )
    }

    // --------------------------------------------------------------------- 扫描

    private fun onlyBackHandlerRegistration(): BackHandlerRegistration {
        val registrations = allBackHandlerRegistrations()
        assertEquals(
            "ui/playback 内应有且只有一个 BackHandler 注册，实际：${registrations.describe()}",
            1,
            registrations.size,
        )
        return registrations.single()
    }

    private fun allBackHandlerRegistrations(): List<BackHandlerRegistration> =
        playbackSourceFiles().flatMap(::backHandlerRegistrations)

    private fun playbackSourceFiles(): List<File> =
        playbackSourceDirectory()
            .listFiles()
            .orEmpty()
            .filter(File::isFile)
            .sortedBy(File::getName)

    private fun playbackSourceFile(name: String): File = File(playbackSourceDirectory(), name)

    private fun playbackSourceDirectory(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            for (candidate in listOf(PlaybackSourcesRelativePath, "app/$PlaybackSourcesRelativePath")) {
                val source = File(directory, candidate)
                if (source.isDirectory) {
                    return source
                }
            }
            directory = directory.parentFile
        }
        error("找不到播放页源码目录，user.dir=${System.getProperty("user.dir")}")
    }
}

/** 一次 `BackHandler` 注册：文件名、行号、显式参数列表原文与所在函数。 */
private data class BackHandlerRegistration(
    val fileName: String,
    val line: Int,
    val arguments: String,
    val enclosingFunction: String?,
)

private fun List<BackHandlerRegistration>.describe(): String =
    if (isEmpty()) {
        "（一个都没有）"
    } else {
        joinToString { registration -> "${registration.fileName}:${registration.line}" }
    }

private const val PlaybackSourcesRelativePath = "src/main/java/com/smartisan/music/ui/playback"

private val BackHandlerName = Regex("\\bBackHandler\\b")
private val ArbitrationName = Regex("\\bplaybackBackTarget\\b")
private val FunctionDeclaration = Regex("\\bfun\\s+(\\w+)\\s*[<(]")

private fun backHandlerRegistrations(file: File): List<BackHandlerRegistration> {
    val code = file.readText().stripCommentsAndStringLiterals()
    return BackHandlerName.findAll(code)
        .mapNotNull { match ->
            val call = callAfter(code, match.range.last + 1) ?: return@mapNotNull null
            BackHandlerRegistration(
                fileName = file.name,
                line = lineOf(code, match.range.first),
                arguments = call,
                enclosingFunction = enclosingFunctionOf(code, match.range.first),
            )
        }
        .toList()
}

/**
 * 取名字之后的实参列表：只认紧跟（允许换行与空白）的 `(` 或 `{`——`import ...BackHandler` 这类
 * 光秃秃的引用不该算一次注册。返回 `(` 内的原文；尾随 lambda 写法没有实参，返回空串。
 */
private fun callAfter(code: String, start: Int): String? {
    var index = start
    while (index < code.length && code[index].isWhitespace()) {
        index++
    }
    if (index >= code.length) {
        return null
    }
    return when (code[index]) {
        '{' -> ""
        '(' -> {
            var depth = 0
            var end = index
            while (end < code.length) {
                when (code[end]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                end++
            }
            code.substring(index + 1, end.coerceAtMost(code.length))
        }
        else -> null
    }
}

private fun lineOf(code: String, index: Int): Int {
    var line = 1
    for (position in 0 until index) {
        if (code[position] == '\n') {
            line++
        }
    }
    return line
}

private fun enclosingFunctionOf(code: String, index: Int): String? =
    FunctionDeclaration.findAll(code).lastOrNull { match -> match.range.first < index }?.groupValues?.get(1)

/**
 * 把注释与字符串字面量抹成空格（保留换行，行号才准），只留下会被编译的代码：
 * 否则一句 `// 内层 BackHandler(enabled = queueVisible) 已删` 的注释会被当成真的注册。
 */
private fun String.stripCommentsAndStringLiterals(): String {
    val characters = toCharArray()

    fun blank(from: Int, until: Int) {
        for (position in from until until) {
            if (characters[position] != '\n') {
                characters[position] = ' '
            }
        }
    }

    var index = 0
    while (index < characters.size) {
        val char = characters[index]
        when {
            char == '/' && characters.getOrNull(index + 1) == '/' -> {
                var end = index
                while (end < characters.size && characters[end] != '\n') {
                    end++
                }
                blank(index, end)
                index = end
            }
            char == '/' && characters.getOrNull(index + 1) == '*' -> {
                var end = index + 2
                while (end + 1 < characters.size &&
                    !(characters[end] == '*' && characters[end + 1] == '/')
                ) {
                    end++
                }
                val stop = (end + 2).coerceAtMost(characters.size)
                blank(index, stop)
                index = stop
            }
            char == '"' && isTripleQuote(characters, index) -> {
                var end = index + 3
                while (end + 2 < characters.size && !isTripleQuote(characters, end)) {
                    end++
                }
                val stop = (end + 3).coerceAtMost(characters.size)
                blank(index, stop)
                index = stop
            }
            char == '"' -> {
                var end = index + 1
                while (end < characters.size && characters[end] != '"') {
                    if (characters[end] == '\\') {
                        end++
                    }
                    end++
                }
                val stop = (end + 1).coerceAtMost(characters.size)
                blank(index, stop)
                index = stop
            }
            else -> index++
        }
    }
    return String(characters)
}

private fun isTripleQuote(characters: CharArray, index: Int): Boolean =
    characters.getOrNull(index) == '"' &&
        characters.getOrNull(index + 1) == '"' &&
        characters.getOrNull(index + 2) == '"'
