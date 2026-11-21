package com.wy.openx.tools

/**
 * 工具基类。
 * 新工具：覆写 name / description / parameters（Map<String, ToolParameter>），
 *         definition 自动生成标准 OpenAI function-calling schema；
 * 旧工具：可直接覆写 definition（与声明式写法共存，便于逐批迁移）。
 */
interface AgentTool {
    val name: String
        get() = ""
    val description: String
        get() = ""
    val parameters: Map<String, ToolParameter>
        get() = emptyMap()

    val definition: ToolDef
        get() = ToolDef(
            function = FunctionDef(
                name = name,
                description = description,
                parameters = buildToolSchema(parameters)
            )
        )

    suspend fun execute(args: Map<String, Any>): String
}