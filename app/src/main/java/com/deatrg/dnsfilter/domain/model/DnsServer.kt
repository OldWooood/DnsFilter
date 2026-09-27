package com.deatrg.dnsfilter.domain.model

data class DnsServer(
    val id: String,
    val name: String,
    val address: String,
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false
) {
    companion object {
        /**
         * 解析上游 DNS 服务器地址。
         *
         * 支持的形式（端口缺省 53）：
         * - `1.2.3.4` / `1.2.3.4:5353`（IPv4，可带端口）
         * - `2001:db8::1`（裸 IPv6，不带端口）
         * - `[2001:db8::1]:5353`（IPv6 + 端口，标准括号形式）
         * - `dns.example.com` / `dns.example.com:5353`（主机名，可带端口）
         *
         * @return (host, port)；URL scheme、路径、空端口、越界端口等非法输入返回 null
         */
        fun parseAddress(address: String): Pair<String, Int>? {
            val trimmed = address.trim()
            if (trimmed.isEmpty() || trimmed.length > 260) return null
            if (trimmed.contains("://") || trimmed.contains('/') || trimmed.contains('@')) return null

            // [IPv6]:port
            if (trimmed.startsWith("[")) {
                val close = trimmed.indexOf(']')
                if (close < 2) return null
                val host = trimmed.substring(1, close)
                if (!looksLikeIpv6Literal(host)) return null
                val rest = trimmed.substring(close + 1)
                if (rest.isEmpty()) return host to DEFAULT_PORT
                if (!rest.startsWith(":")) return null
                val port = parsePort(rest.substring(1)) ?: return null
                return host to port
            }

            val colonCount = trimmed.count { it == ':' }
            // 多个冒号：只可能是裸 IPv6 字面量（host:port 形式只允许一个冒号）
            if (colonCount > 1) {
                return if (looksLikeIpv6Literal(trimmed)) trimmed to DEFAULT_PORT else null
            }

            if (colonCount == 1) {
                val separator = trimmed.indexOf(':')
                val host = trimmed.substring(0, separator)
                val portString = trimmed.substring(separator + 1)
                if (host.isEmpty() || portString.isEmpty()) return null
                val port = parsePort(portString) ?: return null
                if (!isValidHostName(host)) return null
                return host to port
            }

            if (!isValidHostName(trimmed)) return null
            return trimmed to DEFAULT_PORT
        }

        private const val DEFAULT_PORT = 53

        private fun parsePort(value: String): Int? {
            if (value.isEmpty() || value.length > 5) return null
            if (value.any { !it.isDigit() }) return null
            return value.toIntOrNull()?.takeIf { it in 1..65535 }
        }

        /** 粗校验 hostname 标签（ASCII 字母/数字/连字符）；完整解析交给 `InetAddress.getByName`。 */
        private fun isValidHostName(host: String): Boolean {
            if (host.isEmpty() || host.length > 253) return false
            return host.split('.').all { label ->
                label.isNotEmpty() && label.length <= 63 &&
                    label.all { it in 'a'..'z' || it in 'A'..'Z' || it.isDigit() || it == '-' } &&
                    !label.startsWith("-") && !label.endsWith("-")
            }
        }

        /** 粗校验 IPv6 字面量（仅十六进制与冒号）；完整解析交给 `InetAddress.getByName`。 */
        private fun looksLikeIpv6Literal(host: String): Boolean {
            if (host.isEmpty() || !host.contains(':')) return false
            return host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }
        }
    }
}
