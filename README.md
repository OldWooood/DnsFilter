# DnsFilter

A fast local DNS filtering proxy for Android. Intercepts DNS queries via Android's `VpnService`, blocks ads and tracking domains using customizable blocklists, and forwards to multiple upstream servers concurrently for the fastest response.

## Features

- **Local VPN-based DNS Proxy** — Routes only DNS traffic into the app via split-tunnel VPN, all other traffic goes through normally
- **Always-on VPN** — Can be set as "Always-on" in system settings (auto-restarts after reboot, optional "block connections without VPN"); revocation runs the full stop cleanup
- **Domain Blocking** — Filters against AdAway-format blocklists. Supports multiple lists, add/remove/toggle, and manual refresh
- **Concurrent Multi-Server Queries** — Sends DNS queries to all enabled upstream servers simultaneously, uses the fastest successful response; a broken server address only fails its own attempt, never the race
- **Two-Level DNS Caching** — Android Resolver is L1; a 4,096-entry L2 stores positive, RFC 2308 negative (NXDOMAIN/NODATA) and brief RFC 9520 SERVFAIL entries, returns correctly aged remaining TTLs, serves expired entries stale for up to 3 days (RFC 8767) while refreshing in the background, and prefetches hot near-expiry entries
- **In-flight Query Coalescing** — Concurrent requests for the same domain/type/class join one in-flight upstream lookup instead of sending duplicates
- **CNAME Cloaking Protection** — Positive answers whose CNAME chain hits the blocklist are answered NXDOMAIN instead, without polluting the cache; the verdict is remembered so repeats skip upstream
- **TCP Fallback & Oversized Answers** — Upstream TC=1 answers (or datagrams that fill the 4,096-byte receive buffer) are retried over TCP to the same server (RFC 7766); the TUN MTU of 8000 lets big answers reach the client in a single UDP datagram
- **UDP Socket Pooling** — Up to 32 reusable sockets per upstream server, prewarmed on VPN start and network change; sockets that lose a race are drained and reused instead of destroyed
- **Statistics** — In-memory counters for DNS requests handled by the VPN, blocked requests, block rate, and average upstream response time
- **Dashboard** — Protection status, start/stop toggle, statistics grid
- **DNS Server Management** — Configure multiple upstream plain DNS servers (add with validation, enable/disable, delete, reset to defaults); `ip:port`, `[ipv6]:port`, and hostname addresses are supported
- **Foreground Service** — Runs as a foreground service with notification and stop action
- **Automatic Blocklist Updates** — Refreshes enabled lists daily around local noon and reschedules after reboot or time-zone changes; downloads send conditional requests (`If-None-Match`/`If-Modified-Since`), so unchanged lists cost a 304 instead of a full re-download
- **Low Overhead** — Packet buffer recycling, request coalescing, and minimal allocations in the hot path

## Screens

- **Dashboard** — Status card with protection state, start/stop, 2×2 statistics (total queries, blocked, block rate, avg response)
- **DNS Servers** — Manage upstream plain DNS servers (add, enable/disable, delete, reset to defaults)
- **Filters** — Manage blocklists (add, enable/disable, delete, refresh, view last update time)

## Build

```bash
./gradlew assembleRelease
```

APKs are output to `app/build/outputs/apk/release/`. The build generates split APKs per ABI (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`) plus a universal APK.

### Debug Build

```bash
./gradlew assembleDebug
```

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.2 |
| UI | Jetpack Compose + Material 3 + Navigation Compose |
| Architecture | MVVM with manual DI (ServiceLocator) |
| Async | Kotlin Coroutines + Flow |
| Networking | OkHttp (blocklist downloads), `DatagramSocket` (DNS queries) |
| Persistence | DataStore Preferences (server/filter settings), file cache (blocklists, 24h freshness window) |
| Background | Foreground `VpnService` + `AlarmManager` blocklist refresh |
| Build | Gradle 9.4.1 + AGP 9.1 + Kotlin DSL |

## Architecture

```
VPN Interface (split-tunnel, DNS only)
       │
       ▼
DnsVpnService — reads IP packets, parses IPv4/IPv6/UDP/DNS
       │
       ├─── DomainFilter — O(1) HashSet blocklist lookup (hosts-file lists)
       ├─── DnsQueryExecutor — 4,096-entry L2 (positive/negative/SERVFAIL + stale-serve + prefetch) → concurrent UDP racing with TCP fallback → per-qtype TTL rewrite
       └─── StatisticsBuffer — in-memory live counters
```

## How It Works

1. Creates a local VPN interface (MTU 8000) routing only traffic to virtual DNS addresses (`10.10.10.10`, `fd00::10`)
2. Reads raw IP/UDP packets from the VPN interface
3. Parses the DNS question from each packet
4. Checks against loaded blocklists — blocked domains (and remembered CNAME-cloaking verdicts) get an immediate, 24-hour cacheable NXDOMAIN response
5. Checks a 4,096-entry LRU cache (positive + negative + SERVFAIL markers); fresh hits return correctly aged remaining TTLs, expired entries may serve stale (RFC 8767, up to 3 days) while a background refresh runs, and hot near-expiry entries are prefetched
6. Coalesces matching concurrent cache misses into one in-flight lookup per domain/type/class
7. Forwards each unique miss to all enabled upstream DNS servers concurrently via plain UDP; TC=1 or receive-buffer-saturated answers are retried over TCP to the same server (RFC 7766)
8. Clamps positive TTLs per qtype (A/AAAA 1–6 hours, others 10 minutes–2 hours), stores them in L2, and returns the fastest successful response; answers whose CNAME chain hits the blocklist are answered NXDOMAIN instead

## Statistics Semantics

- Total and allowed counts represent DNS requests that reach the VPN, not UDP packets sent to upstream servers.
- Blocked requests are counted but never forwarded upstream.
- L2 cache hits are counted as allowed requests but do not contact an upstream server.
- Concurrent matching L2 misses are counted individually, then coalesced into one logical upstream lookup.
- Each logical cache miss sends one UDP request to every enabled upstream server and uses the first successful response.
- Android Resolver cache hits never enter the VPN and are therefore not included in app statistics.

## Protocol Support and Limits

- Upstream DNS currently uses plain UDP; the port defaults to 53, and custom `ip:port`, `[ipv6]:port`, and hostname server addresses are supported. DoH and DoT are not implemented.
- IPv4 and IPv6 DNS packets are supported; IPv6 extension headers are not currently parsed.
- Truncated upstream answers (TC=1, RFC 7766) and datagrams that fill the 4,096-byte receive buffer are retried over TCP to the same server. The TUN MTU is 8000, so oversized answers reach the client in a single UDP datagram; only answers larger than ~7.9 KB still get TC=1 (a client-facing TCP DNS listener is not implemented).
- L2 caching and in-flight request deduplication use normalized domain, query type, and query class.
- L2 stores positive, RFC 2308 negative and RFC 9520 SERVFAIL entries; expired entries serve stale for up to 3 days (RFC 8767) while a background refresh runs, and hot near-expiry entries are prefetched. L2 is cleared when the upstream server list changes; on default-network changes entries are kept for stale-serve.
- Blocklists support hosts-file entries and plain domains. AdBlock Plus/uBlock syntax and wildcard matching are not supported.

## License

Apache 2.0
