package com.wy.openx

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wy.openx.ui.theme.Accent
import com.wy.openx.ui.theme.AgentConsoleTheme
import com.wy.openx.ui.theme.Border
import com.wy.openx.ui.theme.Panel
import com.wy.openx.ui.theme.TextPrimary
import com.wy.openx.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全屏崩溃错误页：全局未捕获异常处理器捕获崩溃后拉起本页面，
 * 展示崩溃详情并支持一键复制，方便用户把错误信息反馈。
 *
 * 设计要点：
 * - handler 落盘「错误页展示中」标志 → 杀进程 → 系统重启并恢复本页（见 [OpenXApp.installCrashHandler]）。
 * - 本页内容成功设置后清除标志：若本页自身再崩溃，handler 会交回系统默认处理器，避免无限重启。
 * - 详情区用**普通可滚动 Text**（非 SelectionContainer）：崩溃页绝不能再依赖 SelectionContainer，
 *   否则其内部选择逻辑再次抛异常 → 错误页也崩，等于白做。
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val threadName = intent.getStringExtra(EXTRA_THREAD_NAME) ?: "unknown"
        val stack = intent.getStringExtra(EXTRA_STACK) ?: ""
        val screen = intent.getStringExtra(EXTRA_SCREEN)
        val logDir = resolveLogDir()
        val report = buildReport(threadName, stack, screen, logDir)
        setContent {
            AgentConsoleTheme(themeName = "暗色") {
                CrashScreen(
                    report = report,
                    logDir = logDir,
                    onCopy = {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("crash", report))
                        Toast.makeText(this, "已复制崩溃信息", Toast.LENGTH_SHORT).show()
                    },
                    onRestart = {
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                        )
                        finish()
                    },
                    onExit = {
                        finish()
                        Process.killProcess(Process.myPid())
                    }
                )
            }
        }
        // 页面内容已成功设置：清除落盘标志，允许下一次崩溃再次进入错误页。
        OpenXApp.resetCrashUiFlag(applicationContext)
    }

    /** 组装可复制的崩溃报告：页面、版本、设备、时间、线程、堆栈、日志位置与最近日志路径。 */
    private fun buildReport(threadName: String, stack: String, screen: String?, logDir: String): String {
        val appVersion = runCatching {
            packageManager.getPackageInfo(packageName, 0).let { info ->
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
                "${info.versionName ?: "unknown"} ($code)"
            }
        }.getOrDefault("unknown")
        val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return buildString {
            appendLine("===== OpenX 崩溃报告 =====")
            appendLine("页面：${screenName(screen)}")
            appendLine("时间：$time")
            appendLine("版本：$appVersion")
            appendLine("设备：$device")
            appendLine("线程：$threadName")
            appendLine()
            appendLine(stack)
            appendLine()
            appendLine("日志目录：$logDir")
        }
    }

    private fun screenName(route: String?): String =
        if (route.isNullOrBlank()) "未知" else route

    private fun resolveLogDir(): String =
        runCatching { getExternalFilesDir(null)?.absolutePath }.getOrNull()
            ?.let { "$it/logs/" } ?: "filesDir/logs/"

    companion object {
        const val EXTRA_THREAD_NAME = "thread_name"
        const val EXTRA_STACK = "stack"
        const val EXTRA_SCREEN = "screen"
    }
}

@Composable
private fun CrashScreen(
    report: String,
    logDir: String,
    onCopy: () -> Unit,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    // 返回键视为退出应用，避免回到已崩溃的界面
    BackHandler { onExit() }
    val ctx = LocalContext.current
    Scaffold(containerColor = Panel) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            Text(text = "⚠", color = Color(0xFFFF6B6B), style = MaterialTheme.typography.displaySmall)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "应用遇到错误", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "下面是崩溃详情，可一键复制后反馈给开发者。",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            // 错误详情卡片：等宽字体、可滚动，普通 Text（非 SelectionContainer，避免复现同类崩溃）
            Surface(
                modifier = Modifier.fillMaxWidth().height(340.dp),
                shape = RoundedCornerShape(14.dp),
                color = Color.Black.copy(alpha = 0.35f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Text(
                    text = report,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextPrimary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "完整日志：$logDir\n（应用内「日志」页亦可查看/导出）",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) { Text("复制崩溃信息") }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) { Text("重新启动", color = Accent) }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text("退出应用", color = TextSecondary)
            }
        }
    }
}

private const val EXTRA_THREAD_NAME = "thread_name"
private const val EXTRA_STACK = "stack"
private const val EXTRA_SCREEN = "screen"
