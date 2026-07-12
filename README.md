# DnsFilter

A fast local DNS filtering proxy for Android. Intercepts DNS queries via Android's `VpnService`, blocks ads and tracking domains using customizable blocklists, and forwards to multiple upstream servers concurrently for the fastest response.

**Version:** 3.0.0 | **Package:** `com.deatrg.dnsfilter` | **minSdk:** 29 (Android 10+)

## Features

- **Local VPN-based DNS Proxy** — Routes only DNS traffic into the app via split-tunnel VPN, all other traffic goes through normally
- **Domain Blocking** — Filters against AdAway-format blocklists. Supports multiple lists, add/remove/toggle, and manual refresh
- **Concurrent Multi-Server Queries** — Sends DNS queries to all enabled upstream servers simultaneously, uses the fastest successful response
- **Android Resolver Caching** — Positive responses advertise a speed-first 1–6 hour TTL; the app keeps no separate DNS response cache
- **Pre-queue Query Deduplication** — Concurrent requests for the same domain/type occupy one worker and share one upstream query
- **UDP Socket Pooling** — Up to 16 idle reusable sockets per upstream server reduce socket setup overhead
- **Statistics** — In-memory counters for DNS requests handled by the VPN, blocked requests, block rate, and average upstream response time
- **Dashboard** — Protection status, start/stop toggle, statistics grid
- **DNS Server Management** — Configure multiple upstream servers with enable/disable toggle
- **Foreground Service** — Runs as a foreground service with notification and stop action
- **Automatic Blocklist Updates** — Refreshes enabled lists daily around local noon and reschedules after reboot or time-zone changes
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
| Build | Gradle 9.4.1 + AGP 9.2 + Kotlin DSL |

## Architecture

```
VPN Interface (split-tunnel, DNS only)
       │
       ▼
DnsVpnService — reads IP packets, parses IPv4/IPv6/UDP/DNS
       │
       ├─── DomainFilter — O(1) HashSet blocklist lookup
       ├─── DnsQueryExecutor — concurrent upstream queries → 1–6 hour TTL rewrite
       └─── StatisticsBuffer — in-memory live counters
```

## How It Works

1. Creates a local VPN interface routing only traffic to virtual DNS addresses (`10.10.10.10`, `fd00::10`)
2. Reads raw IP/UDP packets from the VPN interface
3. Parses the DNS question from each packet
4. Checks against loaded blocklists — blocked domains get an immediate, 24-hour cacheable NXDOMAIN response
5. Coalesces matching concurrent requests before the upstream worker queue
6. Forwards each unique request to all enabled upstream DNS servers concurrently via plain UDP
7. Rewrites positive TTLs to 1–6 hours and returns the fastest successful response; Android Resolver owns caching

## Statistics Semantics

- Total and allowed counts represent DNS requests that reach the VPN, not UDP packets sent to upstream servers.
- Blocked requests are counted but never forwarded upstream.
- Concurrent matching requests are counted individually, then coalesced into one logical upstream lookup.
- Each logical lookup sends one UDP request to every enabled upstream server and uses the first successful response.
- Android Resolver cache hits never enter the VPN and are therefore not included in app statistics.

## Protocol Support and Limits

- Upstream DNS currently uses plain UDP on port 53. DoH and DoT are not implemented.
- IPv4 and IPv6 DNS packets are supported; IPv6 extension headers are not currently parsed.
- Oversized UDP responses are returned with the DNS `TC` flag instead of being silently dropped. A TCP DNS proxy/fallback is not implemented yet.
- In-flight request deduplication uses normalized domain, query type, and query class.
- Blocklists support hosts-file entries and plain domains. AdBlock Plus/uBlock syntax and wildcard matching are not supported.

## License

Apache 2.0
