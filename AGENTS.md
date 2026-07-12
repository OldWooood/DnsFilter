# DnsFilter - Agent Guide

## Project Overview

DnsFilter is an Android application that acts as a local DNS filtering proxy. It intercepts device DNS queries via an Android `VpnService`, blocks ads and tracking domains against customizable blocklists, and forwards allowed queries concurrently to multiple plain UDP DNS servers.

- **Package**: `com.deatrg.dnsfilter`
- **Language**: Kotlin 2.2.21
- **UI Framework**: Jetpack Compose with Material 3
- **Build System**: Gradle 9.4.1 with Kotlin DSL
- **minSdk**: 29, **targetSdk/compileSdk**: 35
- **Java/Kotlin toolchain**: 17

## Technology Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose (BOM 2024.12.01), Material 3, Navigation Compose |
| Architecture | MVVM with manual DI (ServiceLocator pattern) |
| Async | Kotlin Coroutines + Flow |
| Networking | OkHttp 4.12.0 (blocklist downloads), custom `DatagramSocket` (plain DNS) |
| Persistence | DataStore Preferences (settings), local file cache (blocklists) |
| Background updates | `AlarmManager` + `BroadcastReceiver` (WorkManager is deprecated in this project) |

**Important**: The project **does not use Hilt**. Dependency injection is done manually via `ServiceLocator` in `app/src/main/java/com/deatrg/dnsfilter/ServiceLocator.kt`.

## Project Structure

```
app/src/main/java/com/deatrg/dnsfilter/
├── DnsFilterApplication.kt       # Application class, initializes ServiceLocator and default data
├── ServiceLocator.kt             # Manual DI container
├── data/
│   ├── local/
│   │   ├── PreferencesManager.kt       # DataStore wrapper for settings, DNS servers, filter lists, stats
│   │   ├── BlocklistCacheManager.kt    # File-based cache for downloaded blocklists
│   │   └── StatisticsBuffer.kt         # In-memory stats buffer to reduce disk I/O
│   ├── remote/
│   │   ├── DnsQueryExecutor.kt         # Races plain UDP upstreams and rewrites response TTLs
│   │   └── DomainFilter.kt             # Loads blocklists, checks domains, supports AdAway format
│   ├── repository/
│   │   ├── DnsServerRepositoryImpl.kt
│   │   └── FilterListRepositoryImpl.kt
│   └── worker/
│       ├── BlocklistUpdateAlarmScheduler.kt   # AlarmManager scheduling
│       ├── BlocklistUpdateAlarmReceiver.kt    # Handles BOOT_COMPLETED and update alarms
├── domain/
│   ├── model/
│   │   ├── DnsServer.kt          # id, name, address, isEnabled, isBuiltIn
│   │   ├── FilterList.kt         # id, name, url, isEnabled, isBuiltIn
│   │   └── DnsStatistics.kt      # totalQueries, blockedQueries, allowedQueries, avgResponseTime
│   └── repository/
│       └── Repositories.kt       # DnsServerRepository and FilterListRepository interfaces
├── service/
│   └── DnsVpnService.kt          # Core VpnService: intercepts packets, parses DNS, filters, responds
└── ui/
    ├── MainActivity.kt
    ├── navigation/
    │   ├── Screen.kt             # Sealed class for bottom nav screens
    │   └── DnsFilterNavHost.kt
    ├── screens/
    │   ├── dashboard/            # DashboardScreen + DashboardViewModel
    │   ├── dnsserver/            # DnsServersScreen + DnsServersViewModel
    │   └── filterlist/           # FilterListsScreen + FilterListsViewModel
    └── theme/
        ├── Color.kt
        └── Theme.kt
```

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build (requires key.properties for signing)
./gradlew assembleRelease

# Run tests
./gradlew test
./gradlew connectedAndroidTest

# Clean
./gradlew clean
```

APKs are output to `app/build/outputs/apk/`. The build produces split APKs by ABI (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`) plus a universal APK.

## Key Architecture Details

### DNS Interception Flow
1. `DnsVpnService` establishes a local VPN interface (`10.10.10.1/24`, `fd00::1/48`).
2. Virtual DNS servers advertised: `10.10.10.10` (IPv4) and `fd00::10` (IPv6).
3. **Split-tunneling**: Only DNS traffic to the virtual DNS addresses is routed into the VPN. Other traffic bypasses it.
4. The app excludes itself from the VPN (`addDisallowedApplication`) to avoid routing loops.
5. Packets are read from the VPN `ParcelFileDescriptor`, parsed (IPv4/IPv6 → UDP → DNS payload), and processed.
6. If the domain is blocked, an `NXDOMAIN` response with a 24-hour SOA negative TTL is returned immediately.
7. Matching concurrent requests are coalesced while they are in flight; completed responses are not cached by the app.
8. Allowed queries are forwarded concurrently to all enabled upstream DNS servers; the first successful response is used.
9. Positive response TTLs are clamped to 1–6 hours and caching is delegated to Android Resolver.

### Domain Filtering
- Blocklists use the **AdAway/hosts file format**: lines like `0.0.0.0 domain.com` or `127.0.0.1 domain.com`.
- Matching is exact after lowercase normalization and trimming a trailing dot.
- Wildcard entries are ignored by the current in-memory matcher.
- Subdomains are not blocked by parent-domain entries unless the exact subdomain also appears in the blocklist.
- Default built-in list: `Anti-Ad` (`https://anti-ad.net/domains.txt`).

### Blocklist Updates
- **Daily auto-update** at local time 12:00 using `AlarmManager` + `BlocklistUpdateAlarmReceiver`.
- WorkManager is intentionally not used because it is unreliable on some OEM devices.
- On `BOOT_COMPLETED`, the alarm is rescheduled.
- Blocklist cache expires after 24 hours (`UPDATE_INTERVAL_HOURS = 24`).

### Statistics
- `StatisticsBuffer` keeps process-local counters in memory and updates the UI at most once per second.
- Total and allowed counts represent requests handled by the VPN, not actual UDP packets sent upstream.
- Blocked requests are counted without an upstream request; matching concurrent requests are counted individually before one coalesced lookup is sent to every enabled server.
- Android Resolver cache hits do not reach the VPN and are not counted.

### Concurrency Control
- `DnsVpnService` uses a bounded 1024-entry upstream queue and a fixed worker pool.
- Matching in-flight requests share one logical lookup; `DnsQueryExecutor` still sends that lookup concurrently to every enabled server.

## Code Style Guidelines

- Kotlin code style is set to `official` in `gradle.properties`.
- The codebase mixes English and Chinese comments. New code should follow the existing style of the surrounding file.
- Use `viewModelScope.launch` for ViewModel-bound coroutines.
- Use `Dispatchers.IO` for file/network operations.
- StateFlow values are exposed via `collectAsStateWithLifecycle` in Compose screens.

## Testing

Current unit tests cover DNS TTL/NXDOMAIN wire-format behavior and blocklist alarm scheduling. If adding tests:
- Unit tests go under `app/src/test/`
- Instrumented tests go under `app/src/androidTest/`
- The project uses JUnit 4, Espresso, and Compose UI Test JUnit4.

## Security Considerations

### Permissions
The app requires these Android permissions:
- `INTERNET` — upstream DNS queries and blocklist downloads
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` — foreground VPN service
- `POST_NOTIFICATIONS` — service notification
- `RECEIVE_BOOT_COMPLETED` — reschedule alarms after reboot
- `SCHEDULE_EXACT_ALARM` — exact alarm for daily blocklist updates
- `WAKE_LOCK` — keep CPU awake during background blocklist updates

### ProGuard / R8
- Release builds are minified and shrink resources (`isMinifyEnabled = true`, `isShrinkResources = true`).
- `Log.d` and `Log.v` calls are stripped in release builds via ProGuard rules.
- Key classes (`DnsVpnService`, `ServiceLocator`, domain models) are kept.

### Signing
Release builds are signed using credentials from `key.properties` (not in repo). If `key.properties` is missing, the release build is unsigned.

## Important Caveats for Agents

1. **Only plain UDP DNS is implemented**: DoH, DoT, and TCP fallback are not available.
2. **Do not introduce Hilt**: The project intentionally uses manual DI. Do not add Hilt annotations or modify build files to enable it unless explicitly requested.
3. **Prefer AlarmManager over WorkManager** for new background scheduling tasks.
4. **VPN is split-tunnel only**: The VPN routes only DNS traffic. Do not change routing to capture all traffic unless explicitly required.
5. **Default DNS servers** are Chinese providers (Tencent DNS, AliDNS, DNSPod) and are initialized on first launch in `DnsFilterApplication`.
6. **Blocklist parsing** only understands hosts-file format and plain domain lists. It does not support AdBlock Plus syntax, uBlock Origin filters, wildcard matching, or parent-domain traversal.
