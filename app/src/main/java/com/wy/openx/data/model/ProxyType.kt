package com.wy.openx.data.model

/**
 * 代理协议类型：HTTP（CONNECT 隧道，可代理全部 http/https 流量）或 SOCKS5（TCP 级转发）。
 */
enum class ProxyType {
    HTTP, SOCKS5
}
