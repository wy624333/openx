package com.wy.openx.tools

import com.google.gson.annotations.SerializedName

data class ToolDef(
    @SerializedName("type") val type: String = "function",
    @SerializedName("function") val function: FunctionDef
)

data class FunctionDef(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String,
    @SerializedName("parameters") val parameters: Map<String, Any>
)

data class ToolCall(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String = "function",
    @SerializedName("function") val function: ToolCallFunction
)

data class ToolCallFunction(
    @SerializedName("name") val name: String,
    @SerializedName("arguments") val arguments: String
)

data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val function: ToolCallDeltaFunction? = null
)

data class ToolCallDeltaFunction(
    val name: String? = null,
    val arguments: String? = null
)
