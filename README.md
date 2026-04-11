# DnsFilter

A local DNS filtering proxy for Android. Intercepts DNS queries, filters ads and tracking domains using customizable blocklists, supports DNS over HTTPS (DoH) and DNS over TLS (DoT).

## Build

```bash
./gradlew assembleRelease
```

APKs are output to `app/build/outputs/apk/release/`.

## Features

- **DNS Filtering** — Block ads/tracking domains via blocklists (AdAway format)
- **Multi-DNS Support** — Plain DNS, DoH, DoT
- **Concurrent Queries** — Query multiple servers, use fastest response
- **Blocklist Management** — Add/remove/toggle filter lists, view last update time
- **VPN Mode** — Route all device DNS through the app

## Screens

- **Dashboard** — Protection status, statistics, start/stop
- **DNS Servers** — Manage upstream DNS servers
- **Filters** — Manage blocklists
