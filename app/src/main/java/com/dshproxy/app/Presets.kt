package com.dshproxy.app

/**
 * 常见「OpenAI 兼容」供应商预设。
 *
 * 只填 Base URL 和建议模型名，Key 由用户自己粘贴。
 * 模型名务必以各家控制台「模型列表」为准，预设里的只是占位建议。
 */
data class Preset(
    val name: String,
    val baseUrl: String,
    val model: String,
    val note: String = "",
)

object Presets {

    val ALL: List<Preset> = listOf(
        Preset(
            name = "阿里百炼（DashScope）",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "deepseek-v3.2",
            note = "模型名按百炼控制台填，别照抄预设值",
        ),
        Preset(
            name = "DeepSeek 官方",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
        ),
        Preset(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            model = "gpt-4o-mini",
        ),
        Preset(
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            model = "glm-4-flash",
        ),
        Preset(
            name = "ModelScope 魔搭",
            baseUrl = "https://api-inference.modelscope.cn/v1",
            model = "Qwen/Qwen3-235B-A22B",
            note = "模型名形如 Qwen/Qwen3-xxx，按魔搭页面填",
        ),
        Preset(
            name = "自定义",
            baseUrl = "",
            model = "",
        ),
    )

    fun indexOfBaseUrl(baseUrl: String): Int {
        val i = ALL.indexOfFirst { it.baseUrl.isNotBlank() && it.baseUrl == baseUrl }
        return if (i >= 0) i else ALL.lastIndex
    }
}
