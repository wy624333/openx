package com.wy.openx.quickjs

import android.util.Log
import java.io.Closeable
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class QuickJsEngine(
    /**
     * 单次脚本执行的看门狗上限（毫秒）。
     *
     * 工具轨道用默认 60s：AI 调工具卡死不能拖太久。
     * UI 轨道给得更长——用户本来就在等进度条（比如插件在装依赖），那里用
     * [UI_EXECUTION_TIMEOUT_MS]。注意兼容层单条命令的上限必须**小于**本值，
     * 否则命令超时会命中看门狗、中断整个引擎并触发 VM 重建。
     */
    private val executionTimeoutMs: Long = DEFAULT_EXECUTION_TIMEOUT_MS
) : Closeable {

    companion object {
        /** 默认看门狗：工具轨道。 */
        const val DEFAULT_EXECUTION_TIMEOUT_MS = 60_000L

        /** UI 轨道看门狗：忠实地留出插件安装依赖的时间。 */
        const val UI_EXECUTION_TIMEOUT_MS = 300_000L
        /** 单次 JS 执行的看门狗上限：死循环脚本超时后被 interrupt 中断 */
        const val EXECUTION_TIMEOUT_MS = 60_000L
        const val TAG = "QuickJsEngine"
    }
    private data class Bootstrap(val script: String, val fileName: String)

    private val runtimeRef = AtomicReference<QuickJsNativeRuntime?>()
    private val nativeInterfaceRef = AtomicReference<Any?>()
    private val methodCache = ConcurrentHashMap<String, Method>()
    private val runtimeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "QuickJsRuntime").apply { isDaemon = true }
    }
    private val hostDispatcher = QuickJsNativeHostDispatcher(
        dispatchTimer = ::dispatchTimerOnRuntimeThread,
        forwardCall = ::dispatchNativeCall,
        // 用 ref 转一层：sink 往往在引擎建好之后才由调用方设上（比如 UI session）
        onConsole = { level, message -> consoleSink.get()?.invoke(level, message) }
    )

    private val consoleSink = AtomicReference<((level: String, message: String) -> Unit)?>(null)

    /**
     * 接管插件脚本里 `console.log/info/warn/error/debug` 的输出。
     *
     * 不设时输出直接丢弃（历史行为）。插件排错时几乎全靠这个，所以 UI 调试面板会接上。
     * 回调可能在 QuickJS 线程上执行，不要在里面做重活。
     */
    fun setConsoleSink(sink: ((level: String, message: String) -> Unit)?) {
        consoleSink.set(sink)
    }

    /** VM 生命周期内必须存在的脚本，重建后按登记顺序重放。 */
    private val bootstraps = CopyOnWriteArrayList<Bootstrap>()

    /**
     * 中断标志是否已污染当前 VM。
     *
     * 预编译的 libquickjsjni.so 里 `CallFunction`/`Evaluate` 入口没有 `ClearInterrupt()`，
     * 而 `interrupted_` 一旦置 true 就永不复位 —— 一次超时中断之后，这个 VM 里**每一个**
     * 脚本都会在第一个循环回跳点被立刻中断，插件静默失效且没有任何报错。
     * 源码已修，但构建走的是 jniLibs 里的旧 .so，改 cpp 不进包，所以只能重建 VM 恢复：
     * `interrupted_` 是 `QuickJsVm` 的成员，构造函数里初始化为 false。
     */
    @Volatile private var poisoned = false

    init {
        runOnRuntimeThread { it }
    }

    fun bindNativeInterface(instance: Any) {
        nativeInterfaceRef.set(instance)
        methodCache.clear()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> evaluate(script: String, fileName: String = "<eval>"): T? {
        return runOnRuntimeThread { runtime ->
            val result = runtime.eval(script, fileName)
            runtime.executePendingJobs()
            if (!result.success) error(result.describeFailure("QuickJS evaluation failed"))
            decodeJsonValue(result.valueJson) as T?
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> callFunction(functionName: String, argsJson: String, callSite: String = "<call:$functionName>"): T? {
        return runOnRuntimeThread { runtime ->
            val result = runtime.callFunction(functionName, argsJson, callSite)
            runtime.executePendingJobs()
            if (!result.success) error(result.describeFailure("QuickJS function call failed"))
            decodeJsonValue(result.valueJson) as T?
        }
    }

    /**
     * 登记一段"装一次、之后反复调用"的脚本（插件工具/钩子、MCP 客户端、服务插件）：
     * 只是登记，不执行；VM 重建时按登记顺序重放，让上层无需感知重建。
     *
     * 刻意不做回调式通知：重建发生在 QuickJS 线程的任务内部，上层若在那里再调
     * `evaluate`/`callFunction` 会把自己排到自己后面，直接死锁。
     */
    fun registerBootstrap(script: String, fileName: String) {
        bootstraps += Bootstrap(script, fileName)
    }

    /** 撤销登记（插件卸载时用，避免 VM 重建时已卸载的插件被重新装载）。 */
    fun unregisterBootstrap(fileName: String) {
        bootstraps.removeAll { it.fileName == fileName }
    }

    /** 登记并立即执行。 */
    fun installBootstrap(script: String, fileName: String = "<bootstrap>") {
        // 先执行再登记：若此时 VM 已被污染，evaluate 会先重建并重放**已有**脚本，
        // 然后才跑这一段。反过来写会让它先被重放一遍、再被 evaluate 一遍，执行两次。
        // 另外脚本报错时 registerBootstrap 不会被调用，不会留下一条永远重放失败的登记。
        evaluate<Any?>(script, fileName)
        registerBootstrap(script, fileName)
    }

    fun interrupt() {
        // 中断无法在原 VM 上撤销，只能标记出来，等下次执行前重建。
        poisoned = true
        runtimeRef.get()?.let { runCatching { it.interrupt() } }
    }

    override fun close() {
        runCatching { runOnRuntimeThread { runtime -> runtime.clearAllTimers() } }
        hostDispatcher.close()
        runtimeRef.getAndSet(null)?.let { runCatching { it.close() } }
        runtimeExecutor.shutdownNow()
        nativeInterfaceRef.set(null)
        methodCache.clear()
    }

    /** 在 QuickJS 线程上取当前 VM；被中断污染过就换一个新的。 */
    private fun currentRuntime(): QuickJsNativeRuntime {
        val existing = runtimeRef.get()
        if (existing != null && !poisoned) return existing

        if (existing != null) {
            runCatching { existing.close() }
            Log.w(TAG, "重建 QuickJS VM：上一个 VM 被中断污染，中断标志无法清除")
        }
        val fresh = QuickJsNativeRuntime.create(hostDispatcher)
        runtimeRef.set(fresh)
        fresh.installCompatLayerOrThrow()
        replayBootstraps(fresh)
        poisoned = false
        return fresh
    }

    private fun replayBootstraps(runtime: QuickJsNativeRuntime) {
        for (bootstrap in bootstraps) {
            val outcome = runCatching { runtime.eval(bootstrap.script, bootstrap.fileName) }
            val failure = outcome.exceptionOrNull()?.message
                ?: outcome.getOrNull()?.takeIf { !it.success }?.describeFailure("重放失败")
            if (failure != null) Log.w(TAG, "重建后重放 ${bootstrap.fileName} 失败：$failure")
        }
        runCatching { runtime.executePendingJobs() }
    }

    private fun dispatchTimerOnRuntimeThread(timerId: Int) {
        runtimeExecutor.execute {
            runCatching {
                val runtime = currentRuntime()
                val result = runtime.dispatchTimer(timerId)
                runtime.executePendingJobs()
                if (!result.success) error(result.describeFailure("QuickJS timer callback failed"))
            }.getOrElse { e -> System.err.println("QuickJS timer dispatch failed: ${e.message}") }
        }
    }

    private fun <T> runOnRuntimeThread(block: (QuickJsNativeRuntime) -> T): T {
        val future = runtimeExecutor.submit<T> { block(currentRuntime()) }
        try {
            return try {
                future.get(executionTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // 看门狗触发：中断 JS 执行，短暂等待其退出；仍卡死则放弃任务
                interrupt()
                try {
                    future.get(3, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                    future.cancel(true)
                    throw IllegalStateException("QuickJS 执行超时（${executionTimeoutMs / 1000}秒）且中断无效，已放弃该脚本")
                }
            }
        } catch (error: ExecutionException) { throw (error.cause ?: error) }
    }

    private fun dispatchNativeCall(methodName: String, argsJson: String?): String? {
        val target = nativeInterfaceRef.get() ?: error("NativeInterface is not bound")
        val args = decodeArgs(argsJson)
        val method = resolveMethod(target, methodName, args.size)
        val convertedArgs = method.parameterTypes.mapIndexed { index, type -> convertArg(args[index], type) }.toTypedArray()
        return method.invoke(target, *convertedArgs)?.toString()
    }

    private fun resolveMethod(target: Any, methodName: String, argCount: Int): Method {
        val cacheKey = "${target.javaClass.name}#$methodName/$argCount"
        return methodCache.getOrPut(cacheKey) {
            target.javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.size == argCount }
                ?: error("NativeInterface method not found: $methodName/$argCount")
        }
    }

    private fun decodeArgs(argsJson: String?): List<Any?> {
        if (argsJson.isNullOrBlank()) return emptyList()
        val parsed = JSONTokener(argsJson).nextValue()
        if (parsed !is JSONArray) return emptyList()
        return List(parsed.length()) { i -> normalizeJsonValue(parsed.opt(i)) }
    }

    private fun decodeJsonValue(valueJson: String?): Any? {
        if (valueJson.isNullOrBlank()) return null
        return normalizeJsonValue(JSONTokener(valueJson).nextValue())
    }

    private fun normalizeJsonValue(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONArray -> List(value.length()) { i -> normalizeJsonValue(value.opt(i)) }
        is JSONObject -> value.toString()
        else -> value
    }

    private fun convertArg(value: Any?, parameterType: Class<*>): Any? {
        return when (parameterType) {
            java.lang.String::class.java -> value?.toString() ?: ""
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> (value as? Number)?.toInt() ?: value?.toString()?.toIntOrNull() ?: 0
            java.lang.Long.TYPE, java.lang.Long::class.java -> (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull() ?: 0L
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> when (value) { is Boolean -> value; is Number -> value.toInt() != 0; else -> value?.toString()?.toBooleanStrictOrNull() ?: false }
            java.lang.Double.TYPE, java.lang.Double::class.java -> (value as? Number)?.toDouble() ?: value?.toString()?.toDoubleOrNull() ?: 0.0
            java.lang.Float.TYPE, java.lang.Float::class.java -> (value as? Number)?.toFloat() ?: value?.toString()?.toFloatOrNull() ?: 0f
            else -> value?.toString()
        }
    }
}
