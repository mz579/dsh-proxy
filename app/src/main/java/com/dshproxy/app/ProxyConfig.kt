package com.dshproxy.app

data class ProxyConfig(
    val baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    val model: String = "deepseek-v3.2",
    val apiKey: String = "",
    val readTimeoutSec: Long = 300,
    val port: Int = 8787,
)
