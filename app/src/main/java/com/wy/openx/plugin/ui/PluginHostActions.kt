package com.wy.openx.plugin.ui

/**
 * 需要 Activity 参与的宿主能力（文件选择 / 保存 / 图片选择）。
 *
 * ## 为什么要单独抽一层
 * 这三个动作必须走 Android 的 `ActivityResultLauncher`：启动系统选择器、等用户操作、
 * 拿回 Uri。而 [PluginUiHostBridge] 是纯逻辑层（只依赖 Context），拿不到 Compose 的
 * `rememberLauncherForActivityResult`。
 *
 * 所以在 UI 层实现本接口注入给 bridge，bridge 只负责"阻塞等待结果 + 序列化成 JSON"。
 *
 * ## 线程
 * 实现方要保证这些方法**可以在非主线程调用**（JS 线程会阻塞进来），
 * 内部自己切主线程去 launch 选择器。取消/超时应返回 null，不要抛异常。
 */
interface PluginHostActions {

    /** 用户选中的文件内容。 */
    data class PickedFile(val name: String, val content: String, val size: Long)

    /**
     * 让用户选一个文件并读取其内容（文本）。
     * @param mimeTypes MIME 过滤，如 `["text/plain","application/json"]`；空表示任意
     * @return null = 用户取消或超时
     */
    suspend fun pickFile(mimeTypes: List<String>): PickedFile?

    /**
     * 让用户选保存位置并写入内容。
     * @return 保存成功返回显示名，取消返回 null
     */
    suspend fun saveFile(suggestedName: String, content: String): String?

    /**
     * 让用户选一张图片，返回 `data:image/...;base64,...` 形式。
     * 之所以回 data URI 而不是路径：路径一离开本机就失效，而 data URI
     * 可以直接喂给 `ctx.UI.Image({src})`，插件不用再关心怎么读。
     * @return null = 用户取消、超时或读取失败
     */
    suspend fun pickImage(): String?
}
