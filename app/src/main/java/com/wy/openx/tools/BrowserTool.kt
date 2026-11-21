package com.wy.openx.tools

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.wy.openx.browser.BrowserManager
import com.wy.openx.container.FileLogger
import com.wy.openx.container.Workspace
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.coroutineContext
import java.io.File

/**
 * AI 可控的内置浏览器工具。
 *
 * 
 * - openx 工具契约为 `execute(Map<String, Any>): String`（AiCode 为 `ToolResult` + `JsonElement`）；
 * - JSON 由 kotlinx.serialization 改为 Gson；
 * - 截图落盘路径由 FileAccessProvider 改为直接写宿主工作区（`filesDir/workspace/browser-screenshots`），
 *   与 openx 文件工具容器路径 `/root/workspace/...` 对应。
 */
class BrowserTool : AgentTool {

    private companion object {
        const val TAG = "BrowserTool"
        const val DEFAULT_WAIT_TIMEOUT_MS = 10_000L
        const val DEFAULT_BACKBONE_DEPTH = 15
        const val SCREENSHOT_SUBDIR = "workspace/browser-screenshots"
        val READ_ONLY_ACTIONS = setOf("getText", "getHtml", "getBackbone", "screenshot", "console", "wait", "listTabs")

        /** 截图保留天数：超过即清理（AI 观察页面是临时动作，看完全程就没用了）。 */
        const val SCREENSHOT_RETENTION_DAYS = 7L

        /** 截图最多保留张数：超过则删最旧的，防止单次任务密集截图把工作区塞爆。 */
        const val SCREENSHOT_MAX_COUNT = 50
    }

    override val name = "browser"
    override val description = "控制内置浏览器执行自动化操作。支持多标签页（Multi-tab）与后台运行。" +
        "所有操作支持可选参数 tabId（缺省时作用于当前激活的标签页）。" +
        "常规操作不会自动截图，需查看页面视觉内容时请显式调用 action=\"screenshot\"，截图将自动保存到工作区 browser-screenshots 目录。" +
        "newTab 新建标签页，closeTab 关闭标签页，selectTab 切换标签页，listTabs 列出所有标签页。" +
        "evaluate 支持 async/Promise，返回原生 JSON（保留 number/boolean/null 类型）。" +
        "click 使用完整事件链，兼容 React/Vue。fill 使用 native setter + React valueTracker hack。" +
        "selector 支持 ref= / text= / text*= / role=button[name=xxx] / xpath= / CSS。每次返回 tabId+url+title。" +
        "getBackbone 返回无障碍树（role/name/ref，已过滤 script/style 与不可见元素），ref 可传给 selector 直接操作元素。" +
            "select 选原生下拉，dialog 处理 confirm/prompt 对话框，console 取页面控制台日志。" +
        "\nUA 模式：desktopMode=切换为桌面端 UA 并重载当前页（站点返回桌面版式），" +
        "mobileMode=切回移动端，getMode=查询当前模式。桌面端/移动端页面结构差异很大，" +
        "若抓取的元素在移动版不存在，可先 desktopMode 再重试。"

    override val parameters = buildToolParameters(
        ToolParameter("action", ParameterType.STRING,
            "navigate=导航URL; evaluate=执行JS(支持Promise,返回原生JSON); click=点击(完整事件链); fill=填充表单(React兼容); select=下拉选择; hover=悬停; press=按键; getText=提取文本; getHtml=提取HTML; getBackbone=无障碍树(role/name/ref); screenshot=截图; console=控制台日志; wait=等待条件; scroll=滚动; dialog=处理confirm/prompt对话框; back=后退; forward=前进; reload=刷新; newTab=新建标签页; closeTab=关闭标签页; selectTab=切换激活标签页; listTabs=列出所有标签页",
            enumValues = listOf(
                "navigate", "evaluate", "click", "fill", "select", "hover", "press",
                "getText", "getHtml", "getBackbone", "screenshot", "console",
                "wait", "scroll", "dialog", "back", "forward", "reload",
                "newTab", "closeTab", "selectTab", "listTabs",
                "desktopMode", "mobileMode", "getMode"
            )),
        ToolParameter("tabId", ParameterType.STRING, "目标标签页 ID（如 tab-1），缺省时作用于当前激活的标签页", required = false),
        ToolParameter("path", ParameterType.STRING, "screenshot: 截图保存文件名（可选，缺省默认保存在工作区 browser-screenshots/）", required = false),
        ToolParameter("url", ParameterType.STRING, "navigate/newTab: URL（无协议头时优先 https，失败回退 http）", required = false),
        ToolParameter("script", ParameterType.STRING, "evaluate: JS 代码（支持 Promise/async）", required = false),
        ToolParameter("selector", ParameterType.STRING,
            "click/fill/hover/getText/getHtml/scroll/wait: 选择器。支持 ref=e22（getBackbone 返回的引用）/ text=登录 / text*=登录 / role=button[name=\"登录\"] / xpath=//a / CSS", required = false),
        ToolParameter("value", ParameterType.STRING, "fill/select: 要填入的值或要选中的 option（value 或可见文本）", required = false),
        ToolParameter("accept", ParameterType.BOOLEAN, "dialog: 是否接受对话框（true=确定，false=取消），默认 true", required = false),
        ToolParameter("text", ParameterType.STRING, "dialog: prompt 输入内容（accept=true 时生效）", required = false),
        ToolParameter("level", ParameterType.STRING, "console: 日志级别过滤（log/warning/error/debug）", required = false),
        ToolParameter("clear", ParameterType.BOOLEAN, "console: 取完后是否清空日志，默认 false", required = false),
        ToolParameter("key", ParameterType.STRING,
            "press: 键名（Enter/Escape/Tab/ArrowUp/ArrowDown/ArrowLeft/ArrowRight/Backspace/Delete/空格/普通字符）", required = false),
        ToolParameter("condition", ParameterType.STRING,
            "wait: 等待条件。text=登录(精确匹配) / text*=登录(包含匹配) / selector=#result(CSS存在) / domStable(DOM稳定)", required = false),
        ToolParameter("timeout", ParameterType.INTEGER, "wait: 超时毫秒，默认 10000", required = false),
        ToolParameter("maxDepth", ParameterType.INTEGER, "getBackbone: 无障碍树最大深度，默认 15", required = false),
        ToolParameter("x", ParameterType.INTEGER, "scroll: 横向滚动像素", required = false),
        ToolParameter("y", ParameterType.INTEGER, "scroll: 纵向滚动像素", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val action = (args["action"] as? String)?.trim()
            ?: return errorResult("缺少 action 参数", "MISSING_ACTION")
        val tabId = (args["tabId"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        // 允许 AI 在未打开浏览器面板时也直接用（惰性创建进程级单例）；离屏标签仍可正常渲染。
        val ctx = AppContextHolder.context
            ?: return errorResult("应用上下文未初始化", "BROWSER_NOT_READY")
        val manager = BrowserManager.getOrCreate(ctx)
        // 本轮所属 AI 会话（优先协程上下文，发起时冻结，并发可靠；退回全局前台）。
        // 多会话并行时用它隔离标签：省略 tabId 只落到**本会话**的活动标签，别人的标签直接拒绝。
        val cid = coroutineContext[AppContextHolder.ConversationId]?.id
            ?: AppContextHolder.currentConversationId ?: 0L

        return try {
            executeAction(manager, action, tabId, args, cid)
        } catch (e: TimeoutCancellationException) {
            errorResult("操作超时: $action", "TIMEOUT")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消（用户点停止 / 上层超时）必须**重新抛出**，绝不能吞——
            // 否则停止按钮失效、整个调用链的取消被破坏，后续消息还会继续发。
            // 注意：此分支必须排在 IllegalStateException 之前，因为 CancellationException
            // 继承自 IllegalStateException（见下），顺序反了会永远进不到这里。
            throw e
        } catch (e: IllegalStateException) {
            FileLogger.e(TAG, "浏览器操作失败: $action", e)
            errorResult(e.message ?: "浏览器未初始化", "BROWSER_NOT_READY")
        } catch (e: IllegalArgumentException) {
            errorResult(e.message ?: "参数错误", "INVALID_ARGUMENT")
        } catch (e: Exception) {
            FileLogger.e(TAG, "浏览器操作异常: $action", e)
            errorResult("操作失败: ${e.message}", "BROWSER_ERROR")
        }
    }

    private suspend fun executeAction(
        m: BrowserManager,
        action: String,
        tabId: String?,
        args: Map<String, Any>,
        cid: Long
    ): String {
        // 把「可能省略的 tabId」按会话解析成确定目标：
        // - 优先本会话自己的标签；没有则用无主标签（用户打开的），**用后即认领**，
        //   避免两个会话抢同一个页面互相导航；
        // - 显式指定别的会话的 tabId 会被拒绝（抛 IllegalArgumentException）。
        // 注意 newTab / listTabs 不需要解析（前者建新页、后者列可见页），故放在各自分支里处理。
        suspend fun target(): String = m.resolveTabIdFor(tabId, cid)
            ?: throw IllegalArgumentException("当前没有可用的标签页，请先 newTab 打开一个页面")

        return when (action) {
        "newTab" -> {
            val url = args["url"] as? String
            FileLogger.i(TAG, "newTab: url=$url cid=$cid")
            // 新建的标签归属本会话，后续「省略 tabId」的操作才会落到它上面。
            val newId = m.newTab(url, cid)
            ok(action, linkedMapOf(
                "tabId" to JsonPrimitive(newId),
                "url" to JsonPrimitive(m.getUrl(newId)),
                "title" to JsonPrimitive(m.getTitle(newId))
            ), tabId = newId)
        }

        "closeTab" -> {
            val targetId = target()
            FileLogger.i(TAG, "closeTab: targetTabId=$targetId cid=$cid")
            val closed = m.closeTab(targetId)
            val remaining = m.listTabsFor(cid).map { JsonPrimitive(it.id) }
            ok(action, linkedMapOf(
                "closedTabId" to JsonPrimitive(targetId),
                "success" to JsonPrimitive(closed),
                "activeTabId" to JsonPrimitive(m.activeTabIdFor(cid) ?: ""),
                "tabs" to JsonArray().apply { remaining.forEach { add(it) } }
            ))
        }

        "selectTab" -> {
            if (tabId.isNullOrBlank()) return errorResult("selectTab 需要 tabId 参数", "MISSING_TAB_ID")
            val targetId = target()
            FileLogger.i(TAG, "selectTab: targetTabId=$targetId cid=$cid")
            val selected = m.selectTab(targetId)
            ok(action, linkedMapOf(
                "activeTabId" to JsonPrimitive(targetId),
                "success" to JsonPrimitive(selected),
                "url" to JsonPrimitive(m.getUrl(targetId)),
                "title" to JsonPrimitive(m.getTitle(targetId))
            ), tabId = targetId)
        }

        "listTabs" -> {
            // 只列本会话可见的标签（自己的 + 无主的），别的会话的标签不暴露。
            val mine = m.listTabsFor(cid)
            val mineActive = m.activeTabIdFor(cid) ?: ""
            val tabs = mine.map { tab ->
                JsonObject().apply {
                    add("id", JsonPrimitive(tab.id))
                    add("url", JsonPrimitive(tab.url))
                    add("title", JsonPrimitive(tab.title))
                    add("loading", JsonPrimitive(tab.loading))
                    add("active", JsonPrimitive(tab.id == mineActive))
                }
            }
            ok(action, linkedMapOf(
                "tabs" to JsonArray().apply { tabs.forEach { add(it) } },
                "activeTabId" to JsonPrimitive(mineActive)
            ))
        }

        "navigate" -> {
            val url = args["url"] as? String
                ?: return errorResult("navigate 需要 url 参数", "MISSING_URL")
            val targetId = target()
            FileLogger.i(TAG, "navigate: $url (tabId=$targetId cid=$cid)")
            val finalUrl = m.navigate(url, targetId)
            // finalUrl 为空 = 本次加载失败（loadInto 失败只能返回空串，见 BrowserManager 注释）。
            // 单看空串无法区分「失败」与「空 URL」，故附上 tab.error 作为人类可读原因。
            val loadFailed = finalUrl.isBlank()
            val errMsg = if (loadFailed) (m.getError(targetId) ?: "未知原因（超时或加载被中断）") else ""
            if (loadFailed) {
                FileLogger.w(TAG, "navigate 失败: $url -> $errMsg (tabId=$targetId cid=$cid)")
            }
            ok(action, linkedMapOf(
                "requestedUrl" to JsonPrimitive(url),
                "finalUrl" to JsonPrimitive(finalUrl),
                "title" to JsonPrimitive(m.getTitle(targetId)),
                "loadFailed" to JsonPrimitive(loadFailed),
                "message" to JsonPrimitive(if (loadFailed) "页面加载失败: $errMsg（请勿继续对页面 DOM 操作）" else "")
            ), tabId = targetId, forceOk = !loadFailed)
        }

        "evaluate" -> {
            val script = args["script"] as? String
                ?: return errorResult("evaluate 需要 script 参数", "MISSING_SCRIPT")
            val targetId = target()
            FileLogger.i(TAG, "evaluate: ${script.take(100)} (tabId=$targetId cid=$cid)")
            val result = m.evaluateJavaScript(script, targetId)
            ok(action, linkedMapOf("value" to m.parseEvalResult(result)), tabId = targetId)
        }

        "click" -> {
            val selector = args["selector"] as? String
                ?: return errorResult("click 需要 selector 参数", "MISSING_SELECTOR")
            val targetId = target()
            FileLogger.i(TAG, "click: $selector (tabId=$targetId cid=$cid)")
            fromJson(m, action, m.clickElement(selector, targetId), targetId)
        }

        "fill" -> {
            val selector = args["selector"] as? String
                ?: return errorResult("fill 需要 selector 参数", "MISSING_SELECTOR")
            val value = args["value"] as? String
                ?: return errorResult("fill 需要 value 参数", "MISSING_VALUE")
            val targetId = target()
            fromJson(m, action, m.fillElement(selector, value, targetId), targetId)
        }

        "hover" -> {
            val selector = args["selector"] as? String
                ?: return errorResult("hover 需要 selector 参数", "MISSING_SELECTOR")
            val targetId = target()
            fromJson(m, action, m.hoverElement(selector, targetId), targetId)
        }

        "press" -> {
            val key = args["key"] as? String
                ?: return errorResult("press 需要 key 参数", "MISSING_KEY")
            val targetId = target()
            m.pressKey(key, targetId)
            ok(action, linkedMapOf("key" to JsonPrimitive(key)), tabId = targetId)
        }

        "getText" -> {
            val selector = args["selector"] as? String
            val targetId = target()
            val text = m.getText(selector, targetId)
            val truncated = text.take(50_000)
            val resultText = if (text.length > 50_000) "$truncated\n\n[超长截断...]" else truncated
            ok(action, linkedMapOf(
                "text" to JsonPrimitive(resultText),
                "selector" to JsonPrimitive(selector ?: "body")
            ), tabId = targetId)
        }

        "getHtml" -> {
            val selector = args["selector"] as? String
            val targetId = target()
            val html = m.getHtml(selector, targetId)
            ok(action, linkedMapOf(
                "html" to JsonPrimitive(html),
                "selector" to JsonPrimitive(selector ?: "document")
            ), tabId = targetId)
        }

        "getBackbone" -> {
            val maxDepth = (args["maxDepth"] as? Double)?.toInt() ?: DEFAULT_BACKBONE_DEPTH
            val targetId = target()
            FileLogger.i(TAG, "getBackbone: depth=$maxDepth (tabId=$targetId cid=$cid)")
            val tree = m.getBackbone(maxDepth, targetId)
            ok(action, linkedMapOf(
                "tree" to m.parseEvalResult(tree),
                "maxDepth" to JsonPrimitive(maxDepth)
            ), tabId = targetId)
        }

        "screenshot" -> {
            val resolvedTabId = target()
            FileLogger.i(TAG, "screenshot (tabId=$resolvedTabId cid=$cid)")
            val image = m.screenshot(resolvedTabId)
            if (image != null) {
                val timestamp = System.currentTimeMillis()
                val customName = (args["path"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { File(it).name }
                val fileName = customName ?: "screenshot_${timestamp}_$resolvedTabId.jpg"
                val dir = File(Workspace.root(mAppContext()), SCREENSHOT_SUBDIR.removePrefix("workspace/"))
                dir.mkdirs()
                migrateLegacyScreenshots(dir)
                val outFile = File(dir, fileName)
                var saveNote = ""
                try {
                    outFile.writeBytes(Base64.decode(image.base64Data, Base64.DEFAULT))
                } catch (e: Exception) {
                    FileLogger.e(TAG, "保存截图到文件失败: ${outFile.absolutePath}", e)
                    saveNote = "（截图落盘失败：${e.message}）"
                }
                val containerPath = "/root/workspace/browser-screenshots/$fileName"
                val viewport = m.getViewportInfo(resolvedTabId)
                cleanupOldScreenshots(dir)
                ok(action, linkedMapOf(
                    "status" to JsonPrimitive("captured"),
                    "filePath" to JsonPrimitive(containerPath),
                    "hostPath" to JsonPrimitive(outFile.absolutePath),
                    "viewport" to m.parseEvalResult(viewport),
                    "note" to JsonPrimitive("截图已保存，可用 file_manager 读取 $containerPath $saveNote")
                ), tabId = resolvedTabId)
            } else {
                errorResult("截图失败：浏览器尺寸为 0 或渲染异常。", "SCREENSHOT_FAILED", resolvedTabId)
            }
        }

        "wait" -> {
            val condition = args["condition"] as? String
                ?: return errorResult("wait 需要 condition 参数", "MISSING_CONDITION")
            val timeout = (args["timeout"] as? Double)?.toLong() ?: DEFAULT_WAIT_TIMEOUT_MS
            val targetId = target()
            FileLogger.i(TAG, "wait: $condition, timeout=$timeout (tabId=$targetId cid=$cid)")
            val met = try {
                m.wait(condition, timeout, targetId)
            } catch (e: TimeoutCancellationException) { false }
            ok(action, linkedMapOf(
                "condition" to JsonPrimitive(condition),
                "met" to JsonPrimitive(met),
                "message" to JsonPrimitive(if (met) "条件已满足" else "超时未满足")
            ), tabId = targetId)
        }

        "select" -> {
            val selector = args["selector"] as? String
                ?: return errorResult("select 需要 selector 参数", "MISSING_SELECTOR")
            val value = args["value"] as? String
                ?: return errorResult("select 需要 value 参数", "MISSING_VALUE")
            val targetId = target()
            fromJson(m, action, m.selectOption(selector, value, targetId), targetId)
        }

        "dialog" -> {
            val accept = (args["accept"] as? Boolean) ?: true
            val text = args["text"] as? String
            val targetId = target()
            val detail = m.parseEvalResult(m.resolveDialog(accept, text, targetId))
            ok(action, linkedMapOf("result" to detail), tabId = targetId)
        }

        "console" -> {
            val level = args["level"] as? String
            val clear = (args["clear"] as? Boolean) ?: false
            val targetId = target()
            val json = m.getConsoleLogs(level, clear, targetId)
            ok(action, linkedMapOf("logs" to m.parseEvalResult(json)), tabId = targetId)
        }

        "scroll" -> {
            val selector = args["selector"] as? String
            val x = (args["x"] as? Double)?.toInt()
            val y = (args["y"] as? Double)?.toInt()
            val targetId = target()
            fromJson(m, action, m.scroll(selector, x, y, targetId), targetId)
        }

        "back" -> {
            val targetId = target()
            val success = m.goBack(targetId)
            ok(action, linkedMapOf(
                "success" to JsonPrimitive(success),
                "message" to JsonPrimitive(if (success) "已后退" else "无法后退：无历史记录或导航未生效")
            ), tabId = targetId, forceOk = success)
        }

        "forward" -> {
            val targetId = target()
            val success = m.goForward(targetId)
            ok(action, linkedMapOf(
                "success" to JsonPrimitive(success),
                "message" to JsonPrimitive(if (success) "已前进" else "无法前进：无历史记录或导航未生效")
            ), tabId = targetId, forceOk = success)
        }

        "reload" -> {
            val targetId = target()
            val url = m.reload(targetId)
            ok(action, linkedMapOf(
                "url" to JsonPrimitive(url),
                "title" to JsonPrimitive(m.getTitle(targetId))
            ), tabId = targetId)
        }

        // ---- UA 模式（桌面/移动）：影响站点返回的版式 ----
        // 这两个动作作用于**全局浏览器**，不针对某个标签，故不解析 tabId。
        "desktopMode" -> {
            m.setDesktopMode(true)
            FileLogger.i(TAG, "UA 切换为桌面端 (cid=$cid)")
            ok(action, linkedMapOf(
                "desktopMode" to JsonPrimitive(true),
                "message" to JsonPrimitive("已切换为桌面端 UA，所有页面已重载")
            ))
        }

        "mobileMode" -> {
            m.setDesktopMode(false)
            FileLogger.i(TAG, "UA 切换为移动端 (cid=$cid)")
            ok(action, linkedMapOf(
                "desktopMode" to JsonPrimitive(false),
                "message" to JsonPrimitive("已切换为移动端 UA，所有页面已重载")
            ))
        }

        "getMode" -> ok(action, linkedMapOf(
            "desktopMode" to JsonPrimitive(m.desktopMode),
            "message" to JsonPrimitive(if (m.desktopMode) "当前为桌面端 UA" else "当前为移动端 UA")
        ))

        else -> errorResult("未知 action: $action", "UNKNOWN_ACTION")
        }
    }

    private fun mAppContext(): android.content.Context =
        AppContextHolder.context ?: throw IllegalStateException("应用上下文未初始化")

    /** 统一响应封装；存在挂起的 confirm/prompt 时附加 pendingDialog 提示 AI 处理。 */
    private fun envelope(
        ok: Boolean,
        action: String,
        detail: JsonElement,
        tabId: String? = null,
        m: BrowserManager? = null
    ): JsonObject {
        val manager = m ?: BrowserManager.getShared()
        // ⚠ 用 peekActiveTabId（只读、不建标签）：本函数在 IO 线程执行，
        // 而 getActiveTabId 会在无标签时隐式新建 WebView → 非 Looper 线程崩溃。
        val resolvedTabId = tabId ?: manager?.peekActiveTabId() ?: ""
        val obj = JsonObject()
        obj.add("ok", JsonPrimitive(ok))
        obj.add("action", JsonPrimitive(action))
        obj.add("tabId", JsonPrimitive(resolvedTabId))
        obj.add("url", JsonPrimitive(manager?.getUrl(resolvedTabId) ?: ""))
        obj.add("title", JsonPrimitive(manager?.getTitle(resolvedTabId) ?: ""))
        obj.add("detail", detail)
        obj.add("error", JsonNull.INSTANCE)
        manager?.pendingDialogInfo(resolvedTabId)?.let { d ->
            obj.add("pendingDialog", JsonObject().apply {
                add("type", JsonPrimitive(d.type))
                add("message", JsonPrimitive(d.message))
            })
        }
        return obj
    }

    private fun ok(
        action: String,
        detail: Map<String, JsonElement>,
        tabId: String? = null,
        forceOk: Boolean = true
    ): String {
        val obj = buildDetail(action, detail, tabId, forceOk)
        return obj.toString()
    }

    private fun buildDetail(
        action: String,
        detail: Map<String, JsonElement>,
        tabId: String?,
        forceOk: Boolean
    ): JsonObject {
        val d = JsonObject()
        detail.forEach { (k, v) -> d.add(k, v) }
        return envelope(forceOk, action, d, tabId)
    }

    private fun ok(action: String, detail: JsonObject, tabId: String? = null, forceOk: Boolean = true): String =
        envelope(forceOk, action, detail, tabId).toString()

    private fun fromJson(m: BrowserManager, action: String, jsonStr: String, tabId: String? = null): String {
        val detail = m.parseEvalResult(jsonStr)
        val okFlag = (detail as? JsonObject)?.get("matched")?.asString != "false"
        return envelope(okFlag, action, detail, tabId, m).toString()
    }

    private fun errorResult(message: String, code: String, tabId: String? = null): String {
        val manager = BrowserManager.getShared()
        // 同 envelope：只读取 id，绝不在 IO 线程隐式建 WebView。
        val resolvedTabId = tabId ?: manager?.peekActiveTabId() ?: ""
        val obj = JsonObject()
        obj.add("ok", JsonPrimitive(false))
        obj.add("action", JsonPrimitive(""))
        obj.add("tabId", JsonPrimitive(resolvedTabId))
        obj.add("url", JsonPrimitive(manager?.getUrl(resolvedTabId) ?: ""))
        obj.add("title", JsonPrimitive(manager?.getTitle(resolvedTabId) ?: ""))
        obj.add("detail", JsonObject())
        obj.add("error", JsonObject().apply {
            add("code", JsonPrimitive(code))
            add("message", JsonPrimitive(message))
        })
        return obj.toString()
    }

    /**
     * 一次性迁移：旧版本截图目录 `workspace/.openx/browser-screenshots` → 新目录
     * `workspace/browser-screenshots`（[SCREENSHOT_SUBDIR]）。
     *
     * 旧目录里的截图是历史遗留，改路径后若不动它们，会变成孤儿——既不进自动清理、
     * 也不进存储页统计。故在新截图落盘前顺带搬一次：文件逐个移动，重名时保留新文件、删旧文件；
     * 搬完删除旧目录。幂等，迁移过一次后旧目录不存在，直接跳过。
     */
    private fun migrateLegacyScreenshots(newDir: File) {
        runCatching {
            val legacy = File(Workspace.root(mAppContext()), ".openx/browser-screenshots")
            if (!legacy.isDirectory) return@runCatching
            val files = legacy.listFiles()
                ?.filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                ?: return@runCatching
            if (files.isEmpty()) {
                legacy.delete()
                return@runCatching
            }
            newDir.mkdirs()
            var moved = 0
            files.forEach { f ->
                val target = File(newDir, f.name)
                if (target.exists()) {
                    // 重名：新目录已有同名文件，旧的直接删（保留新路径的）
                    f.delete()
                } else if (f.renameTo(target)) {
                    moved++
                }
            }
            // 剩下的空目录清掉
            legacy.delete()
            if (moved > 0) FileLogger.i(TAG, "迁移旧截图 $moved 张到新目录")
        }
    }

    /**
     * 清理过期/超量的浏览器截图。
     * AI 的 screenshot 是**临时观察**动作——看完页面视觉内容后，这次截图就完成使命了，
     * 不会像用户手动保存的文件那样需要长期保留。若不清理，每次 AI 看网页都会永久落一张图，
     * 工作区会越堆越多。
     *
     * 策略（双上限，取更严格的那个）：
     * 1. 超过 [SCREENSHOT_RETENTION_DAYS] 天的删除；
     * 2. 数量超过 [SCREENSHOT_MAX_COUNT] 张时，删最旧的。
     *
     * 在**每次截图落盘之后**调用，属懒清理：只在真正产生新截图时打扫，不在后台轮询。
     * 只删目录下的 `.jpg/.png` 文件，不删目录本身（目录被容器绑定，删掉会断挂载点）。
     *
     * 不跟随符号链接：与备份/存储模块同样的坑——跟随会把链接指向的外部目录拖进来误删。
     */
    private fun cleanupOldScreenshots(dir: File) {
        runCatching {
            if (!dir.exists()) return@runCatching
            val now = System.currentTimeMillis()
            val maxAgeMs = SCREENSHOT_RETENTION_DAYS * 24L * 3600_000L

            // 只收集普通图片文件（不跟随符号链接），按最后修改时间升序
            val files = dir.listFiles()
                ?.filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                ?.filter { it.name.endsWith(".jpg", ignoreCase = true) || it.name.endsWith(".png", ignoreCase = true) }
                ?.sortedBy { it.lastModified() }
                ?: return@runCatching

            // 超过时间上限的
            val expired = files.filter { now - it.lastModified() > maxAgeMs }
            // 超过数量上限的（在未过期的那批里取最旧的）
            val overCount = if (files.size > SCREENSHOT_MAX_COUNT)
                files.take(files.size - SCREENSHOT_MAX_COUNT) else emptyList()

            val toDelete = (expired + overCount).toSet()
            var deleted = 0
            toDelete.forEach { f ->
                if (f.delete()) deleted++
            }
            if (deleted > 0) FileLogger.i(TAG, "清理过期截图 $deleted 张（共 ${files.size} 张）")
        }
    }
}
