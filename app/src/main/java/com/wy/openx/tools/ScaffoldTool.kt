package com.wy.openx.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ScaffoldTool : AgentTool {
    override val name = "scaffold"
    override val description = "创建项目脚手架。在指定目录下生成项目模板文件，支持多种项目类型。"
    override val parameters = buildToolParameters(
        ToolParameter("type", ParameterType.STRING, "项目类型：python(脚本) web(HTML+CSS+JS) react(Vite+React) node(Node.js) go(Go模块) rust(Rust项目) kotlin(Kotlin) empty(空目录)", enumValues = listOf("python", "web", "react", "node", "go", "rust", "kotlin", "empty")),
        ToolParameter("name", ParameterType.STRING, "项目名称，同时也是目录名"),
        ToolParameter("dir", ParameterType.STRING, "父目录路径。不传则使用应用根目录", required = false)
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val type = (args["type"] as? String)?.trim() ?: return "错误：未指定项目类型"
        val name = (args["name"] as? String)?.trim() ?: return "错误：未指定项目名称"
        // 项目名即目录名：拒绝路径分隔符与穿越
        if (name.contains('/') || name.contains('\\') || name.contains("..") ||
            name.startsWith(".") || name.isBlank()
        ) {
            return "错误：项目名称只能包含常规字符，不能含路径分隔符或以 . 开头「$name」"
        }
        val dirStr = (args["dir"] as? String)?.trim()
        val parentDir = if (!dirStr.isNullOrBlank()) {
            ToolPaths.sanitize(dirStr)?.takeIf { it.isDirectory || it.mkdirs() != false }
                ?: return "错误：父目录不可用或被安全策略拒绝「$dirStr」"
        } else {
            val ctx = AppContextHolder.context ?: return "错误：应用上下文未初始化"
            ctx.getExternalFilesDir(null) ?: return "错误：无法获取文件目录"
        }
        if (!parentDir.exists()) parentDir.mkdirs()

        val projectDirRaw = File(parentDir, name)
        // 规范化后必须仍位于父目录之内（防穿越）
        val projectDir = ToolPaths.sanitize(projectDirRaw.absolutePath)
            ?.takeIf { it.absolutePath.startsWith(parentDir.canonicalPath) }
            ?: return "错误：项目目录越界「${projectDirRaw.absolutePath}」"
        if (projectDir.exists()) return "错误：目录「${projectDir.absolutePath}」已存在"
        projectDir.mkdirs()

        return try {
            withContext(Dispatchers.IO) {
                val files = when (type) {
                    "python" -> scaffoldPython(projectDir)
                    "web" -> scaffoldWeb(projectDir)
                    "react" -> scaffoldReact(projectDir)
                    "node" -> scaffoldNode(projectDir)
                    "go" -> scaffoldGo(projectDir)
                    "rust" -> scaffoldRust(projectDir)
                    "kotlin" -> scaffoldKotlin(projectDir)
                    "empty" -> emptyList<String>()
                    else -> return@withContext "错误：不支持的项目类型「$type」"
                }
                "✅ 已创建 $type 项目「$name」\n目录: ${projectDir.absolutePath}\n文件: ${files.size}\n" + files.joinToString("\n") { "  📄 $it" }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { "创建失败: ${e.message ?: "未知错误"}" }
    }

    private fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content.trimIndent() + "\n")
    }

    private fun scaffoldPython(dir: File): List<String> {
        write(File(dir, "main.py"), """
            def main():
                print("Hello from ${dir.name}!")

            if __name__ == "__main__":
                main()
        """)
        write(File(dir, "requirements.txt"), "# dependencies\n")
        write(File(dir, ".gitignore"), """
            __pycache__/
            *.pyc
            .venv/
            .env
        """)
        write(File(dir, "README.md"), """
            # ${dir.name}

            ## 安装

            ```bash
            pip install -r requirements.txt
            ```

            ## 运行

            ```bash
            python main.py
            ```
        """)
        return listOf("main.py", "requirements.txt", ".gitignore", "README.md")
    }

    private fun scaffoldWeb(dir: File): List<String> {
        write(File(dir, "index.html"), """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>${dir.name}</title>
                <link rel="stylesheet" href="style.css">
            </head>
            <body>
                <div id="app">
                    <h1>${dir.name}</h1>
                    <p>欢迎</p>
                </div>
                <script src="app.js"></script>
            </body>
            </html>
        """)
        write(File(dir, "style.css"), """
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { font-family: system-ui, sans-serif; background: #f5f5f5; color: #333; }
            #app { max-width: 800px; margin: 0 auto; padding: 2rem; text-align: center; }
        """)
        write(File(dir, "app.js"), """
            document.addEventListener('DOMContentLoaded', () => {
                console.log('${dir.name} loaded');
            });
        """)
        write(File(dir, ".gitignore"), """
            node_modules/
            .env
        """)
        return listOf("index.html", "style.css", "app.js", ".gitignore")
    }

    private fun scaffoldReact(dir: File): List<String> {
        write(File(dir, "package.json"), """
            {
                "name": "${dir.name}",
                "private": true,
                "version": "0.0.1",
                "type": "module",
                "scripts": {
                    "dev": "vite",
                    "build": "vite build",
                    "preview": "vite preview"
                },
                "dependencies": {
                    "react": "^18.2.0",
                    "react-dom": "^18.2.0"
                },
                "devDependencies": {
                    "@vitejs/plugin-react": "^4.2.0",
                    "vite": "^5.1.0"
                }
            }
        """)
        write(File(dir, "vite.config.js"), """
            import { defineConfig } from 'vite'
            import react from '@vitejs/plugin-react'
            export default defineConfig({ plugins: [react()] })
        """)
        write(File(dir, "index.html"), """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0"><title>${dir.name}</title></head>
            <body><div id="root"></div><script type="module" src="/src/main.jsx"></script></body>
            </html>
        """)
        write(File(dir, "src/main.jsx"), """
            import React from 'react'
            import ReactDOM from 'react-dom/client'
            import App from './App'
            ReactDOM.createRoot(document.getElementById('root')).render(<React.StrictMode><App /></React.StrictMode>)
        """)
        write(File(dir, "src/App.jsx"), """
            import React from 'react'
            export default function App() { return <div><h1>${dir.name}</h1></div> }
        """)
        write(File(dir, ".gitignore"), "node_modules\ndist\n.env\n")
        return listOf("package.json", "vite.config.js", "index.html", "src/main.jsx", "src/App.jsx", ".gitignore")
    }

    private fun scaffoldNode(dir: File): List<String> {
        write(File(dir, "package.json"), """
            {
                "name": "${dir.name}",
                "version": "1.0.0",
                "type": "module",
                "scripts": {
                    "start": "node src/index.js",
                    "dev": "node --watch src/index.js"
                },
                "dependencies": {}
            }
        """)
        write(File(dir, "src/index.js"), """
            function main() {
                console.log('Hello from ${dir.name}!');
            }
            main();
        """)
        write(File(dir, ".gitignore"), "node_modules\n.env\n")
        return listOf("package.json", "src/index.js", ".gitignore")
    }

    private fun scaffoldGo(dir: File): List<String> {
        val module = dir.name.lowercase().replace(Regex("[^a-z0-9-]"), "")
        write(File(dir, "go.mod"), "module $module\n\ngo 1.22\n")
        write(File(dir, "main.go"), """
            package main

            import "fmt"

            func main() {
                fmt.Println("Hello from ${dir.name}!")
            }
        """)
        write(File(dir, ".gitignore"), """
            /${dir.name}
            *.exe
            .env
        """)
        return listOf("go.mod", "main.go", ".gitignore")
    }

    private fun scaffoldRust(dir: File): List<String> {
        write(File(dir, "Cargo.toml"), """
            [package]
            name = "${dir.name.lowercase().replace(Regex("[^a-z0-9-]"), "")}"
            version = "0.1.0"
            edition = "2021"

            [dependencies]
        """)
        write(File(dir, "src/main.rs"), """
            fn main() {
                println!("Hello from {}!", "${dir.name}");
            }
        """)
        write(File(dir, ".gitignore"), """
            /target
            .env
        """)
        return listOf("Cargo.toml", "src/main.rs", ".gitignore")
    }

    private fun scaffoldKotlin(dir: File): List<String> {
        write(File(dir, "build.gradle.kts"), """
            plugins {
                kotlin("jvm") version "1.9.22"
                application
            }
            repositories { mavenCentral() }
            dependencies { implementation(kotlin("stdlib")) }
            application { mainClass = "MainKt" }
        """)
        write(File(dir, "settings.gradle.kts"), "rootProject.name = \"${dir.name}\"\n")
        write(File(dir, "src/main/kotlin/Main.kt"), """
            fun main() {
                println("Hello from ${dir.name}!")
            }
        """)
        write(File(dir, ".gitignore"), """
            .gradle/
            build/
            .env
        """)
        return listOf("build.gradle.kts", "settings.gradle.kts", "src/main/kotlin/Main.kt", ".gitignore")
    }
}