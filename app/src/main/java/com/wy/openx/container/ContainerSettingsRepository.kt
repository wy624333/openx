package com.wy.openx.container

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/** Alpine apk 镜像源配置。baseUrl 用于拼 `{base}/alpine/{branch}/main|community`。 */
data class ApkMirror(
    val id: String,
    val label: String,
    val baseUrl: String
)

/** 已下载镜像记录：entryId→文件。installed 标记已导入为自定义容器。 */
data class DownloadedImageRecord(
    val entryId: String,
    val fileUri: String,
    val installed: Boolean
)

/** npm 源：pnpm（corepack）与 opencode（npm -g）安装走这里，与 apk 源无关。 */
data class NpmRegistry(val id: String, val label: String, val url: String)

object NpmRegistries {
    const val NPMMIRROR_ID = "npmmirror"
    const val OFFICIAL_ID = "official"
    val ALL = listOf(
        NpmRegistry(NPMMIRROR_ID, "npmmirror（国内）", "https://registry.npmmirror.com"),
        NpmRegistry(OFFICIAL_ID, "npm 官方源", "https://registry.npmjs.org")
    )

    fun byId(id: String): NpmRegistry = ALL.firstOrNull { it.id == id } ?: ALL.first()
}

object ApkMirrors {
    const val OFFICIAL_ID = "official"
    const val TUNA_ID = "tuna"
    const val ALIYUN_ID = "aliyun"

    val ALL = listOf(
        ApkMirror(OFFICIAL_ID, "官方（Alpine）", "https://dl-cdn.alpinelinux.org"),
        ApkMirror(TUNA_ID, "清华 TUNA", "https://mirrors.tuna.tsinghua.edu.cn"),
        ApkMirror(ALIYUN_ID, "阿里云", "https://mirrors.aliyun.com")
    )

    fun find(id: String): ApkMirror = ALL.firstOrNull { it.id == id } ?: ALL.first()
}

/**
 * 容器配置的**可备份快照**。
 *
 * 只含三项「选择与定义」，不含 [KEY_DOWNLOADED_IMAGES]：那是**本机文件 URI**，
 * 换机后那些文件并不存在，照搬只会留下一堆指向死文件的记录——
 * 与「不备份 rootfs / 镜像本体」是同一理由：能重新下载的东西不值得搬，占地方的才搬。
 *
 * [profilesJson] 是 prefs 里的**原始字符串**，导出/导入都原样透传，
 * 不做 decode→encode 往返，避免漏掉 [ContainerProfile] 以后新增的字段。
 */
data class ContainerSettingsSnapshot(
    val activeProfileId: String? = null,
    val apkMirrorId: String? = null,
    val profilesJson: String? = null
) {
    val isEmpty: Boolean get() = activeProfileId == null && apkMirrorId == null && profilesJson == null
}

/**
 * 容器配置仓库：当前激活 profile、全部容器 profile 列表（含内置）与 apk 镜像源选择。
 * 持久化实现（[DataContainerSettingsRepository]）存于 SharedPreferences。
 */
interface ContainerSettingsRepository {
    /** 当前激活的 profile id。引擎据此决定用哪个 rootfs/profile。 */
    val activeProfileIdFlow: Flow<String>

    /** 全部 profile（含内置 Alpine 与自定义）。 */
    val customProfilesFlow: Flow<List<ContainerProfile>>

    /** 当前选择的 apk 镜像源 id（见 [ApkMirrors]。） */
    val apkMirrorIdFlow: Flow<String>

    /** 持久化 apk 镜像源选择；已安装的 rootfs 下次启动/重置容器时应用。 */
    suspend fun setApkMirrorId(id: String)

    /** 当前 npm 源 id（见 [NpmRegistries]），pnpm/opencode 安装走这里。 */
    val npmRegistryIdFlow: Flow<String>

    /** 持久化 npm 源选择。 */
    suspend fun setNpmRegistryId(id: String)

    /** 持久化当前激活的 profile id。 */
    suspend fun setActiveProfile(id: String)

    /** 新增或覆盖同名 id 的 profile（编辑保留 createdAt 排序位置）。 */
    suspend fun upsertCustomProfile(profile: ContainerProfile)

    /** 删除 profile（含内置；删光后由空态手动恢复）。 */
    suspend fun deleteCustomProfile(id: String)

    /** 已下载的镜像记录（按 entryId 索引）。 */
    val downloadedImagesFlow: Flow<Map<String, DownloadedImageRecord>>

    /** 新增或覆盖下载记录（installed 更新用）。 */
    suspend fun upsertDownloadedImage(record: DownloadedImageRecord)

    /** 删除下载记录（文件由调用方负责清理）。 */
    suspend fun removeDownloadedImage(entryId: String)

    /** 导出用：取可备份的三项（见 [ContainerSettingsSnapshot]）。 */
    fun exportSnapshot(): ContainerSettingsSnapshot

    /**
     * 恢复用：写回三项**并刷新 flow**。
     *
     * 刷新 flow 是必须的：三个 flow 在构造时从 prefs 播种一次就再也不看 prefs 了
     * （只由本仓库自己的 setter 改）。备份恢复若绕过仓库直接写 prefs，
     * 界面会一直显示旧值直到进程重启——正是「看着恢复了其实没生效」那类问题。
     * null 字段表示「备份里没有这一项」，保留现值不覆盖。
     */
    suspend fun importSnapshot(snap: ContainerSettingsSnapshot)
}

/** 线程安全的 SharedPreferences 持久化实现。 */
class DataContainerSettingsRepository(context: Context) : ContainerSettingsRepository {
    private val prefs =
        context.getSharedPreferences("container_settings", Context.MODE_PRIVATE)

    private val _activeProfileId = MutableStateFlow(
        prefs.getString(KEY_ACTIVE_PROFILE, ContainerProfile.BUILTIN_ID) ?: ContainerProfile.BUILTIN_ID
    )
    override val activeProfileIdFlow: Flow<String> = _activeProfileId.asStateFlow()

    private val _apkMirrorId = MutableStateFlow(
        prefs.getString(KEY_APK_MIRROR, ApkMirrors.OFFICIAL_ID) ?: ApkMirrors.OFFICIAL_ID
    )
    override val apkMirrorIdFlow: Flow<String> = _apkMirrorId.asStateFlow()

    private val _npmRegistryId = MutableStateFlow(
        prefs.getString(KEY_NPM_REGISTRY, NpmRegistries.NPMMIRROR_ID) ?: NpmRegistries.NPMMIRROR_ID
    )
    override val npmRegistryIdFlow: Flow<String> = _npmRegistryId.asStateFlow()

    @Volatile
    private var profilesCache: List<ContainerProfile>? = null

    private val _customProfiles = MutableStateFlow(loadProfiles())
    override val customProfilesFlow: Flow<List<ContainerProfile>> = _customProfiles.asStateFlow()

    private val _downloadedImages = MutableStateFlow(loadDownloadedImages())
    override val downloadedImagesFlow: Flow<Map<String, DownloadedImageRecord>> = _downloadedImages.asStateFlow()

    override suspend fun setApkMirrorId(id: String) {
        val safe = if (ApkMirrors.ALL.any { it.id == id }) id else ApkMirrors.OFFICIAL_ID
        prefs.edit().putString(KEY_APK_MIRROR, safe).apply()
        _apkMirrorId.value = safe
    }

    override suspend fun setNpmRegistryId(id: String) {
        val safe = if (NpmRegistries.ALL.any { it.id == id }) id else NpmRegistries.NPMMIRROR_ID
        prefs.edit().putString(KEY_NPM_REGISTRY, safe).apply()
        _npmRegistryId.value = safe
    }

    override suspend fun setActiveProfile(id: String) {
        prefs.edit().putString(KEY_ACTIVE_PROFILE, id).apply()
        _activeProfileId.value = id
    }

    override suspend fun upsertCustomProfile(profile: ContainerProfile) {
        val current = loadProfiles().toMutableList()
        val existing = current.firstOrNull { it.id == profile.id }
        val final = when {
            profile.createdAt != 0L -> profile
            existing != null && existing.createdAt != 0L -> profile.copy(createdAt = existing.createdAt)
            else -> profile.copy(createdAt = System.currentTimeMillis())
        }
        current.removeAll { it.id == final.id }
        current.add(final)
        saveProfiles(current)
    }

    override suspend fun deleteCustomProfile(id: String) {
        saveProfiles(loadProfiles().filterNot { it.id == id })
    }

    override suspend fun upsertDownloadedImage(record: DownloadedImageRecord) {
        val merged = loadDownloadedImages().toMutableMap()
        merged[record.entryId] = record
        prefs.edit().putString(KEY_DOWNLOADED_IMAGES, encodeDownloadedImages(merged)).apply()
        _downloadedImages.value = merged
    }

    override suspend fun removeDownloadedImage(entryId: String) {
        val remaining = loadDownloadedImages().filterNot { it.key == entryId }
        prefs.edit().putString(KEY_DOWNLOADED_IMAGES, encodeDownloadedImages(remaining)).apply()
        _downloadedImages.value = remaining
    }

    override fun exportSnapshot(): ContainerSettingsSnapshot = ContainerSettingsSnapshot(
        activeProfileId = prefs.getString(KEY_ACTIVE_PROFILE, null),
        apkMirrorId = prefs.getString(KEY_APK_MIRROR, null),
        profilesJson = prefs.getString(KEY_PROFILES, null)
    )

    override suspend fun importSnapshot(snap: ContainerSettingsSnapshot) {
        val e = prefs.edit()
        snap.activeProfileId?.let { e.putString(KEY_ACTIVE_PROFILE, it) }
        if (snap.apkMirrorId != null) {
            // 镜像源 id 白名单校验：备份可能被改过，未知 id 一律落回官方源而不是写进去
            val id = if (ApkMirrors.ALL.any { it.id == snap.apkMirrorId }) snap.apkMirrorId else ApkMirrors.OFFICIAL_ID
            e.putString(KEY_APK_MIRROR, id)
            _apkMirrorId.value = id
        }
        if (snap.profilesJson != null) {
            e.putString(KEY_PROFILES, snap.profilesJson)
            // 缓存必须先作废，否则 loadProfiles() 直接返回旧列表，flow 刷了也没用
            profilesCache = null
            _customProfiles.value = loadProfiles()
        }
        snap.activeProfileId?.let { _activeProfileId.value = it }
        e.apply()
    }

    /** 首次启动（或旧版本升级）兜底：把内置 Alpine 写入列表。 */
    fun ensureBuiltinDefault() {
        if (prefs.getBoolean(KEY_INITIALIZED, false)) return
        prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
        if (loadProfiles().none { it.id == ContainerProfile.BUILTIN_ID }) {
            saveProfiles(listOf(ContainerProfile.BUILTIN_ALPINE) + loadProfiles())
        }
    }

    private fun loadProfiles(): List<ContainerProfile> {
        profilesCache?.let { return it }
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        val list = runCatching { decodeProfiles(raw) }.getOrDefault(emptyList())
        profilesCache = list
        return list
    }

    private fun loadDownloadedImages(): Map<String, DownloadedImageRecord> {
        val raw = prefs.getString(KEY_DOWNLOADED_IMAGES, null) ?: return emptyMap()
        return runCatching {
            val arr = JSONArray(raw)
            val map = mutableMapOf<String, DownloadedImageRecord>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("entryId")
                if (id.isNotBlank()) {
                    map[id] = DownloadedImageRecord(
                        entryId = id,
                        fileUri = o.optString("fileUri"),
                        installed = o.optBoolean("installed", false)
                    )
                }
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun encodeDownloadedImages(map: Map<String, DownloadedImageRecord>): String {
        val arr = JSONArray()
        map.values.forEach { r ->
            arr.put(JSONObject().apply {
                put("entryId", r.entryId)
                put("fileUri", r.fileUri)
                put("installed", r.installed)
            })
        }
        return arr.toString()
    }

    private fun saveProfiles(profiles: List<ContainerProfile>) {
        profilesCache = profiles
        prefs.edit().putString(KEY_PROFILES, encodeProfiles(profiles)).apply()
        _customProfiles.value = profiles
    }

    private fun encodeProfiles(profiles: List<ContainerProfile>): String {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("shell", p.shellPath ?: JSONObject.NULL)
                put("bindings", JSONArray().apply { p.extraBindings.forEach { put(it) } })
                put("args", JSONArray().apply { p.extraArgs.forEach { put(it) } })
                put("env", JSONObject().apply { p.env.forEach { (k, v) -> put(k, v) } })
                put("builtin", p.isBuiltin)
                put("createdAt", p.createdAt)
                when (val src = p.rootfsSource) {
                    is RootfsSource.Asset -> {
                        put("rootfs", JSONObject().apply {
                            put("type", "asset")
                            put("path", src.path)
                        })
                    }
                    is RootfsSource.LocalFile -> {
                        put("rootfs", JSONObject().apply {
                            put("type", "file")
                            put("uri", src.uri)
                        })
                    }
                }
            })
        }
        return arr.toString()
    }

    private fun decodeProfiles(raw: String): List<ContainerProfile> {
        val arr = JSONArray(raw)
        val list = mutableListOf<ContainerProfile>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val bindings = mutableListOf<String>()
            o.optJSONArray("bindings")?.let { b -> for (j in 0 until b.length()) bindings.add(b.getString(j)) }
            val args = mutableListOf<String>()
            o.optJSONArray("args")?.let { b -> for (j in 0 until b.length()) args.add(b.getString(j)) }
            val env = mutableMapOf<String, String>()
            o.optJSONObject("env")?.let { e -> e.keys().forEach { k -> env[k] = e.optString(k) } }
            val rootfs = runCatching {
                val r = o.getJSONObject("rootfs")
                when (r.optString("type")) {
                    "file" -> RootfsSource.LocalFile(r.optString("uri"))
                    else -> RootfsSource.Asset(r.optString("path"))
                }
            }.getOrNull() ?: continue
            // 旧版内置编辑 bug 会产生「builtin=true 但 id≠内置」的重复项（asset rootfs 恒为内置），
            // 加载时静默清理，避免列表出现多个内置 Alpine。
            if (o.optBoolean("builtin") && o.optString("id") != ContainerProfile.BUILTIN_ID) continue
            list.add(
                ContainerProfile(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    rootfsSource = rootfs,
                    shellPath = o.optString("shell").takeIf { it.isNotBlank() && !o.isNull("shell") },
                    extraBindings = bindings,
                    extraArgs = args,
                    env = env,
                    isBuiltin = o.optBoolean("builtin", false),
                    createdAt = o.optLong("createdAt", 0L)
                )
            )
        }
        return list
    }

    private companion object {
        const val KEY_ACTIVE_PROFILE = "active_profile"
        const val KEY_APK_MIRROR = "apk_mirror"
        private const val KEY_NPM_REGISTRY = "npm_registry"
        const val KEY_PROFILES = "profiles_json"
        const val KEY_INITIALIZED = "initialized"
        const val KEY_DOWNLOADED_IMAGES = "downloaded_images_json"
    }
}