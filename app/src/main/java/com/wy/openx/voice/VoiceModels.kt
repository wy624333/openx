package com.wy.openx.voice

/**
 * 离线语音模型元数据。
 *
 * 模型不随安装包内置，改为运行时按需下载：单个 onnx 就有 160MB+，内置等于把安装包体积翻倍。
 * 这里记下载地址，由 [VoiceModelManager] 拉包、解压。
 *
 * 每个模型给多个下载地址（[VoiceModelSpec.urls]）：国内直连 GitHub 拉包实测几乎起不了头，
 * 所以 cnb 镜像排前、GitHub 原链兜底。
 *
 * [requiredFiles] 是解压时从压缩包里挑出来的文件清单（包内的示例音频与文档不进 APK），
 * [fileSizes] 是各文件应有的字节数：只看「文件在不在」会把上一次下载残留的坏文件当成就绪。
 */
data class VoiceModelSpec(
    /** 落盘目录名，解压后的根目录名也用它。 */
    val dirName: String,
    /** 展示名（下载进度用）。 */
    val displayName: String,
    /** 模型压缩包（tar.bz2）的下载地址候选，按优先级排列；前一个失败才试下一个。 */
    val urls: List<String>,
    /** 运行所需文件相对 [dirName] 的路径，全部存在**且字节数对得上**才算就绪。 */
    val requiredFiles: List<String>,
    /** 各必需文件的字节数。 */
    val fileSizes: Map<String, Long> = emptyMap(),
)

object VoiceModels {

    /** 模型根目录（相对 filesDir）。 */
    const val MODEL_ROOT_DIR = "voice_models"

    /**
     * 中文流式识别：streaming-zipformer-zh（int8 量化）。
     *
     * 选流式而不是非流式：需要「边说边出字 + 静音自动断句」，非流式模型只在停录后才出结果。
     * int8 版把 encoder 从 566MB 压到 154MB，手机 CPU 推理也快得多。
     */
    val ASR_ZH = VoiceModelSpec(
        dirName = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30",
        displayName = "中文语音识别（Zipformer 流式）",
        urls = listOf(
            "https://cnb.cool/huxiangli/aharou-releases/-/releases/download/voice-models/" +
                "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2",
        ),
        requiredFiles = listOf("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"),
        fileSizes = mapOf(
            "encoder.int8.onnx" to 161_141_793L,
            "decoder.onnx" to 5_165_083L,
            "joiner.int8.onnx" to 1_033_416L,
            "tokens.txt" to 20_628L,
        ),
    )

    /**
     * 全部模型。下载、状态统计都按这份清单走：少算一个，就会出现「看着已就绪、实际报模型不完整」
     * 这种半截状态。
     * 必须声明在各 spec 之后——object 属性按声明顺序初始化，提前引用只会拿到 null。
     */
    val ALL = listOf(ASR_ZH)
}
