package com.wy.openx.tools

/**
 * 工具参数声明体系：
 * - 用 [ParameterType] 枚举 + [ToolParameter] 数据类声明工具参数，
 *   由统一的 [buildToolProperties] 生成标准 JSON Schema，避免每个工具手写 mapOf 出错；
 * - 对既有手写 schema，在请求出口用 [normalizeToolSchema] 统一清洗（剥离 default 等非标准字段、
 *   统一 type 小写、补全缺失字段），保证任意严格校验的第三方网关 / 聚合 API 都能接受。
 *   说明：tools 的 schema 仅是给模型看的「说明书」，删除 default 不影响工具执行
 *   （各工具 execute 内部均有 `?: 默认值` 兜底）。
 */

/** 参数类型（转 JSON Schema 时 type 用 lowercase） */
enum class ParameterType {
    STRING, NUMBER, INTEGER, BOOLEAN, ARRAY, OBJECT
}

/** 声明式工具参数 */
data class ToolParameter(
    val name: String,
    val type: ParameterType,
    val description: String,
    val required: Boolean = true,
    /** 可选值枚举（enum 是 Kotlin 软关键字，入参命名用 [enumValues]） */
    val enumValues: List<String>? = null,
    /**
     * 当 [type] 为 [ParameterType.ARRAY] 时，描述数组元素的标准 JSON Schema
     * （原样并入 function-calling 的 items 字段，如 `{"type":"object","properties":{...}}`）。
     */
    val itemsSchema: Map<String, Any>? = null
)

/** 用声明式参数生成标准 JSON Schema */
fun buildToolSchema(decls: Map<String, ToolParameter>): Map<String, Any> {
    val properties = LinkedHashMap<String, Any>()
    val required = mutableListOf<String>()
    decls.forEach { (key, p) ->
        val prop = LinkedHashMap<String, Any>()
        prop["type"] = p.type.name.lowercase()
        prop["description"] = p.description
        p.enumValues?.let { prop["enum"] = it }
        if (p.type == ParameterType.ARRAY) p.itemsSchema?.let { prop["items"] = it }
        properties[key] = prop
        if (p.required) required.add(key)
    }
    return mapOf(
        "type" to "object",
        "properties" to properties,
        "required" to required
    )
}

/** 用声明式参数生成声明式参数映射 */
fun buildToolProperties(vararg params: ToolParameter): Map<String, ToolParameter> =
    params.associateBy { it.name }

/** 别名：与 buildToolProperties 等价，便于批量迁移时按惯例命名调用 */
fun buildToolParameters(vararg params: ToolParameter): Map<String, ToolParameter> =
    buildToolProperties(*params)

/**
 * 发送给模型前的统一规范化：
 * - 根级只保留 type/properties/required/additionalProperties，物理删除 default 等非标准键；
 * - 每个属性的 type 统一小写、description 缺失时用属性名兜底；
 * - 属性级标准字段白名单：type/description/enum/items/format/pattern/minimum/maximum/
 *   exclusiveMinimum/exclusiveMaximum/multipleOf/minLength/maxLength/minItems/maxItems/
 *   uniqueItems/additionalProperties/properties/anyOf/oneOf/allOf，其余（如 default）丢弃。
 */
fun normalizeToolSchema(parameters: Map<String, Any>): Map<String, Any> {
    val out = LinkedHashMap<String, Any>()
    parameters.forEach { (k, v) ->
        when (k) {
            "type" -> out[k] = (v as? String)?.lowercase() ?: "object"
            "properties" -> {
                val props = v as? Map<*, *> ?: return@forEach
                out[k] = props.mapValues { (name, pv) -> normalizeProperty(name as? String, pv) }
            }
            "required", "additionalProperties" -> out[k] = v
            // 其余根级非标准键（如 default）丢弃
        }
    }
    if (!out.containsKey("type")) out["type"] = "object"
    if (!out.containsKey("properties")) out["properties"] = emptyMap<String, Any>()
    if (!out.containsKey("required")) out["required"] = emptyList<String>()
    return out
}

private val PROPERTY_WHITELIST = setOf(
    "type", "description", "enum", "items", "format", "pattern",
    "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
    "minLength", "maxLength", "minItems", "maxItems", "uniqueItems",
    "additionalProperties", "properties", "anyOf", "oneOf", "allOf"
)

private fun normalizeProperty(name: String?, raw: Any?): Map<String, Any> {
    if (raw !is Map<*, *>) return mapOf("type" to "string", "description" to (name ?: ""))
    val clean = LinkedHashMap<String, Any>()
    raw.forEach { (k, v) ->
        val key = k as? String ?: return@forEach
        if (key in PROPERTY_WHITELIST && v != null) {
            clean[key] = if (key == "type") (v as? String)?.lowercase() ?: "string" else v
        }
    }
    if (!clean.containsKey("type")) clean["type"] = "string"
    if (!clean.containsKey("description")) clean["description"] = name ?: ""
    return clean
}