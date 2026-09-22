package android.util

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 单测跑在 mockable android.jar 上，真 `android.util.Log` 的所有方法体都会抛
 * `RuntimeException: Method ... not mocked`，于是任何走到日志分支的落盘路径在测试里都会炸
 * （例：队列快照写盘失败 → 记日志 → 抛异常，失败重试行为就没法测）。
 *
 * 测试源码集先于 android.jar 出现在单测 classpath 上，所以这里同名顶掉它，只记录不抛。
 * 断言用不到这些记录，留着只为排查方便；生产代码不包含本文件。
 */
object Log {

    val messages: MutableList<String> = CopyOnWriteArrayList()

    @JvmStatic
    fun v(tag: String?, msg: String?): Int = record(tag, msg)

    @JvmStatic
    fun v(tag: String?, msg: String?, tr: Throwable?): Int = record(tag, msg)

    @JvmStatic
    fun d(tag: String?, msg: String?): Int = record(tag, msg)

    @JvmStatic
    fun d(tag: String?, msg: String?, tr: Throwable?): Int = record(tag, msg)

    @JvmStatic
    fun i(tag: String?, msg: String?): Int = record(tag, msg)

    @JvmStatic
    fun i(tag: String?, msg: String?, tr: Throwable?): Int = record(tag, msg)

    @JvmStatic
    fun w(tag: String?, msg: String?): Int = record(tag, msg)

    @JvmStatic
    fun w(tag: String?, msg: String?, tr: Throwable?): Int = record(tag, msg)

    @JvmStatic
    fun w(tag: String?, tr: Throwable?): Int = record(tag, tr?.message)

    @JvmStatic
    fun e(tag: String?, msg: String?): Int = record(tag, msg)

    @JvmStatic
    fun e(tag: String?, msg: String?, tr: Throwable?): Int = record(tag, msg)

    @JvmStatic
    fun e(tag: String?, tr: Throwable?): Int = record(tag, tr?.message)

    @JvmStatic
    fun wtf(tag: String?, msg: String?): Int = record(tag, msg)

    @JvmStatic
    fun wtf(tag: String?, msg: String?, tr: Throwable?): Int = record(tag, msg)

    @JvmStatic
    fun wtf(tag: String?, tr: Throwable?): Int = record(tag, tr?.message)

    @JvmStatic
    fun isLoggable(tag: String?, level: Int): Boolean = false

    @JvmStatic
    fun getStackTraceString(tr: Throwable?): String = tr?.toString().orEmpty()

    @JvmStatic
    fun println(priority: Int, tag: String?, msg: String?): Int = record(tag, msg)

    private fun record(tag: String?, message: String?): Int {
        messages += "${tag.orEmpty()}: ${message.orEmpty()}"
        return 0
    }
}
