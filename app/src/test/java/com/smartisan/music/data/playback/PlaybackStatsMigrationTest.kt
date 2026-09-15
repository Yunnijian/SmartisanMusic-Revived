package com.smartisan.music.data.playback

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TableName = "playback_stats"
private const val SchemaDirectory =
    "schemas/com.smartisan.music.data.playback.PlaybackStatsDatabase"
private val AddColumnSql =
    Regex("^ALTER TABLE (\\w+) ADD COLUMN (\\w+) ([A-Z]+) NOT NULL DEFAULT (.+)$")

/**
 * 1→2 迁移与导出的 schema 快照必须一致。
 *
 * 这里不用 `MigrationTestHelper`（需要 instrumentation），而是把迁移执行的 SQL 与
 * `1.json` / `2.json` 的列定义对齐校验：迁移是「v1 的列 + 恰好新增 score」的桥，
 * 列名、类型、NOT NULL 或 DEFAULT 任一处写错都会在这里失败。
 */
class PlaybackStatsMigrationTest {

    @Test
    fun migrationCoversExportedSchemaDifferenceBetweenVersion1And2() {
        val v1Columns = exportedColumns(version = 1)
        val v2Columns = exportedColumns(version = 2)

        val statements = mutableListOf<String>()
        PlaybackStatsDatabase.Migration1To2.migrate(recordingDatabase(statements))

        assertEquals(1, PlaybackStatsDatabase.Migration1To2.startVersion)
        assertEquals(2, PlaybackStatsDatabase.Migration1To2.endVersion)
        assertTrue(
            "迁移应且只应执行一条 ALTER TABLE，实际：$statements",
            statements.size == 1,
        )

        val match = AddColumnSql.matchEntire(statements.single())
            ?: error("迁移 SQL 不是带 DEFAULT 的 ADD COLUMN：${statements.single()}")
        val (table, column, type) = match.destructured

        assertEquals(TableName, table)
        // 新增列必须正好是 2.json 相对 1.json 多出来的那一列。
        val addedColumn = "$column $type NOT NULL"
        assertEquals(setOf(addedColumn), v2Columns - v1Columns)
        assertEquals(v1Columns, v2Columns - setOf(addedColumn))
    }

    private fun exportedColumns(version: Int): Set<String> {
        val schema = File("$SchemaDirectory/$version.json")
        check(schema.isFile) { "缺少 Room schema 快照：${schema.absolutePath}" }
        val database = JSONObject(schema.readText()).getJSONObject("database")
        val entities = database.getJSONArray("entities")
        val table = (0 until entities.length())
            .map { index -> entities.getJSONObject(index) }
            .first { entity -> entity.getString("tableName") == TableName }
        val fields = table.getJSONArray("fields")
        return (0 until fields.length())
            .map { index -> fields.getJSONObject(index) }
            .map { field ->
                buildString {
                    append(field.getString("columnName"))
                    append(' ')
                    append(field.getString("affinity"))
                    if (field.getBoolean("notNull")) {
                        append(" NOT NULL")
                    }
                }
            }
            .toSet()
    }

    /**
     * 只接受 `execSQL` 的动态代理：迁移期间调用其它任何数据库方法都直接失败，
     * 保证迁移是一段纯 DDL。
     */
    private fun recordingDatabase(statements: MutableList<String>): SupportSQLiteDatabase {
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "execSQL" -> {
                    statements += args!![0] as String
                    null
                }
                "toString" -> "RecordingSupportSQLiteDatabase"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("迁移不应调用 ${method.name}")
            }
        } as SupportSQLiteDatabase
    }
}
