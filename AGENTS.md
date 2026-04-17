# DnsFilter - Agent Guide

## Project Overview

DnsFilter is an Android application that acts as a local DNS filtering proxy. It intercepts device DNS queries via an Android `VpnService`, blocks ads and tracking domains against customizable blocklists, and forwards allowed queries to upstream DNS servers. It supports plain DNS, DNS over HTTPS (DoH), and concurrent queries to multiple servers.

- **Package**: `com.deatrg.dnsfilter`
- **Language**: Kotlin 2.2.21
- **UI Framework**: Jetpack Compose with Material 3
- **Build System**: Gradle 9.3.1 with Kotlin DSL
- **minSdk**: 29, **targetSdk/compileSdk**: 35
- **Java/Kotlin toolchain**: 17

## Technology Stack

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose (BOM 2024.12.01), Material 3, Navigation Compose |
| Architecture | MVVM with manual DI (ServiceLocator pattern) |
| Async | Kotlin Coroutines + Flow |
| Networking | OkHttp 4.12.0 (DoH), custom `DatagramSocket` (plain DNS) |
| Persistence | DataStore Preferences (settings), local file cache (blocklists) |
| Background updates | `AlarmManager` + `BroadcastReceiver` (WorkManager is deprecated in this project) |

**Important**: Although Hilt plugins and libraries are declared in `gradle/libs.versions.toml`, the project **does not use Hilt**. Dependency injection is done manually via `ServiceLocator` in `app/src/main/java/com/deatrg/dnsfilter/ServiceLocator.kt`.

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
│   │   ├── DnsQueryExecutor.kt         # Queries upstream DNS (plain/DoH/DoT) with LRU cache
│   │   └── DomainFilter.kt             # Loads blocklists, checks domains, supports AdAway format
│   ├── repository/
│   │   ├── DnsServerRepositoryImpl.kt
│   │   └── FilterListRepositoryImpl.kt
│   └── worker/
│       ├── BlocklistUpdateAlarmScheduler.kt   # AlarmManager scheduling
│       ├── BlocklistUpdateAlarmReceiver.kt    # Handles BOOT_COMPLETED and update alarms
│       └── BlocklistUpdateWorker.kt           # Deprecated WorkManager worker
├── domain/
│   ├── model/
│   │   ├── DnsServer.kt          # id, name, address, type (PLAIN/DOH/DOT), isEnabled
│   │   ├── FilterList.kt         # id, name, url, isEnabled, isBuiltIn
│   │   ├── DnsQuery.kt           # domain, timestamp, isBlocked, responseIp, etc.
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
6. If the domain is blocked, an `NXDOMAIN` response is returned immediately.
7. If allowed, the query is forwarded concurrently to all enabled upstream DNS servers; the first successful response is used.
8. Responses are cached in an LRU cache (4096 entries, 5-minute TTL).

### Domain Filtering
- Blocklists use the **AdAway/hosts file format**: lines like `0.0.0.0 domain.com` or `127.0.0.1 domain.com`.
- Supports wildcard patterns (e.g., `*.tracker.com`).
- Two-level LRU cache for lookup results: one for allowed domains, one for blocked domains.
- Parent domain traversal: `sub.ad.example.com` is blocked if `ad.example.com` or `example.com` is in the blocklist.
- Default built-in list: `anti-ad` (`https://anti-ad.net/domains.txt`).

### Blocklist Updates
- **Daily auto-update** at local time 12:00 using `AlarmManager` + `BlocklistUpdateAlarmReceiver`.
- `WorkManager` (`BlocklistUpdateWorker`) exists but is **deprecated** and cancelled on app startup because it is unreliable on some OEM devices.
- On `BOOT_COMPLETED`, the alarm is rescheduled.
- Blocklist cache expires after 24 hours (`UPDATE_INTERVAL_HOURS = 24`).

### Statistics
- `StatisticsBuffer` accumulates query stats in memory to avoid frequent DataStore writes.
- Flushes to DataStore every 5 seconds (`FLUSH_INTERVAL_MS = 5000L`).
- Also flushed when VPN stops or the ViewModel is cleared.

### Concurrency Control
- `DnsVpnService` uses a `Semaphore(1024)` to limit concurrent packet processing and prevent excessive coroutine creation.

## Code Style Guidelines

- Kotlin code style is set to `official` in `gradle.properties`.
- The codebase mixes English and Chinese comments. New code should follow the existing style of the surrounding file.
- Use `viewModelScope.launch` for ViewModel-bound coroutines.
- Use `Dispatchers.IO` for file/network operations.
- StateFlow values are exposed via `collectAsStateWithLifecycle` in Compose screens.

## Testing

The project currently has only placeholder tests:

- `app/src/test/java/com/deatrg/dnsfilter/ExampleUnitTest.kt` — JUnit 4 unit test
- `app/src/androidTest/java/com/deatrg/dnsfilter/ExampleInstrumentedTest.kt` — Android instrumented test

There are no meaningful domain logic or UI tests yet. If adding tests:
- Unit tests go under `app/src/test/`
- Instrumented tests go under `app/src/androidTest/`
- The project uses JUnit 4, Espresso, and Compose UI Test JUnit4.

## Security Considerations

### Permissions
The app requires these Android permissions:
- `INTERNET`, `ACCESS_NETWORK_STATE` — network operations
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

1. **DoT is not implemented**: `DnsQueryExecutor.queryDoT()` returns a hardcoded failure. DoH and plain DNS are functional.
2. **Do not introduce Hilt**: The project intentionally uses manual DI. Do not add Hilt annotations or modify build files to enable it unless explicitly requested.
3. **Prefer AlarmManager over WorkManager** for new background scheduling tasks.
4. **VPN is split-tunnel only**: The VPN routes only DNS traffic. Do not change routing to capture all traffic unless explicitly required.
5. **Default DNS servers** are Chinese providers (Tencent DNS, AliDNS, DNSPod) and are initialized on first launch in `DnsFilterApplication`.
6. **Blocklist parsing** only understands hosts-file format and plain domain lists. It does not support AdBlock Plus syntax or uBlock Origin filters.
