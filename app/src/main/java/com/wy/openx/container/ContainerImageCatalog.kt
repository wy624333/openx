package com.wy.openx.container

import android.content.Context
import org.json.JSONObject

/** 镜像目录条目：下载地址 = [ContainerImageCatalog.urlFor] 按源与架构拼接。 */
data class ContainerImageEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val sizeBytes: Long = 0,
    /** 发行版标识（对应各源 distros 前缀）。 */
    val distro: String = "",
    /** 相对路径，含 {abi} 占位符（由 urlFor 按设备架构替换）。 */
    val path: String,
    val abiNames: Map<String, String> = emptyMap()
)

/** 镜像下载源：中文名 + 各发行版前缀（缺失/空表示该源不提供此发行版）。 */
data class ContainerImageSource(
    val nameZh: String,
    val distros: Map<String, String> = emptyMap()
)

/**
 * 解析 assets/container-images.json 的下载镜像目录。
 * 完整下载 URL = sources[源][entry.distro] 前缀 + entry.path（{abi} 替换为设备架构）。
 */
class ContainerImageCatalog(private val context: Context) {
    private var sources: LinkedHashMap<String, ContainerImageSource> = linkedMapOf()
    private var sourceOrder: List<String> = emptyList()
    private var images: List<ContainerImageEntry> = emptyList()

    /** 读取并解析目录；失败返回空列表（UI 显示空态）。 */
    fun load(): List<ContainerImageEntry> {
        val raw = runCatching {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()

        val srcMap = linkedMapOf<String, ContainerImageSource>()
        parsed.optJSONObject("sources")?.let { s ->
            s.keys().forEach { id ->
                val o = s.optJSONObject(id) ?: return@forEach
                val name = o.optJSONObject("name") ?: JSONObject()
                val distros = mutableMapOf<String, String>()
                o.optJSONObject("distros")?.let { d ->
                    d.keys().forEach { k -> distros[k] = d.optString(k) }
                }
                srcMap[id] = ContainerImageSource(
                    nameZh = name.optString("zh", name.optString("en", id)),
                    distros = distros
                )
            }
        }
        // org.json 的 keys() 不保证顺序，按固定偏好排序（官方最前），其余按解析顺序追加
        sourceOrder =
            PREFERRED_SOURCE_ORDER.filter { srcMap.containsKey(it) } +
                srcMap.keys.filterNot { it in PREFERRED_SOURCE_ORDER }
        sources = srcMap

        images = mutableListOf<ContainerImageEntry>().also { list ->
            parsed.optJSONArray("images")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val abi = mutableMapOf<String, String>()
                    o.optJSONObject("abiNames")?.let { a ->
                        a.keys().forEach { k -> abi[k] = a.optString(k) }
                    }
                    list.add(
                        ContainerImageEntry(
                            id = o.optString("id"),
                            name = o.optString("name"),
                            version = o.optString("version"),
                            description = o.optString("description"),
                            sizeBytes = o.optLong("sizeBytes", 0L),
                            distro = o.optString("distro"),
                            path = o.optString("path"),
                            abiNames = abi
                        )
                    )
                }
            }
        }
        return images
    }

    /** 源 id 列表（含顺序，供 UI 切换展示）。 */
    val sourceIds: List<String>
        get() = sourceOrder

    /** 源的展示名。 */
    fun sourceLabel(sourceId: String): String = sources[sourceId]?.nameZh ?: sourceId

    /** 当前设备架构下的下载 URL；源不提供该发行版/架构时返回 null。 */
    fun urlFor(entry: ContainerImageEntry, sourceId: String, abi: String): String? {
        val prefix = sources[sourceId]?.distros?.get(entry.distro)
            ?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val abiName = entry.abiNames[abi] ?: return null
        return "$prefix/${entry.path}".replace("{abi}", abiName)
    }

    companion object {
        private const val ASSET_FILE = "container-images.json"
        /** 源固定排序偏好（其余按 JSON 解析顺序）。 */
        private val PREFERRED_SOURCE_ORDER = listOf("official", "huawei", "aliyun", "tencent")

        /** 与安装器一致的架构判定：x86 设备用 x86_64，其余按 arm64。 */
        val CURRENT_ABI: String =
            if (android.os.Build.SUPPORTED_ABIS.any { it.contains("x86") }) "x86_64" else "arm64"
    }
}