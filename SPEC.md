# DnsFilter - Custom DNS Filtering App Specification

## 1. Project Overview

**Project Name:** DnsFilter
**Package:** com.deatrg.dnsfilter
**Type:** Android Application
**Core Functionality:** A DNS filtering proxy that intercepts DNS queries, filters ads/tracking domains using customizable blocklists, supports plain DNS and DNS over HTTPS (DoH), and allows configuring multiple upstream DNS servers with concurrent request handling.

## 2. Technology Stack & Choices

- **Language:** Kotlin 2.2
- **UI Framework:** Jetpack Compose with Material 3
- **Architecture:** MVVM
- **Async:** Kotlin Coroutines + Flow
- **DI:** Manual `ServiceLocator`
- **Networking:** OkHttp (for DoH), custom DatagramSocket (for plain DNS)
- **Local Storage:** DataStore Preferences
- **Build System:** Gradle 8.x with Kotlin DSL

### Key Libraries
- androidx.compose.* (BOM)
- androidx.lifecycle:lifecycle-viewmodel-compose
- androidx.datastore:datastore-preferences
- com.google.dagger:hilt-android
- com.squareup.okhttp3:okhttp (DNS over HTTPS)
- kotlinx.coroutines

## 3. Feature List

### Core Features
1. **Local DNS Proxy Server** - Runs a local DNS server that intercepts queries
2. **Multi-DNS Support** - Configure multiple upstream DNS servers (DoH/Plain)
3. **Concurrent DNS Requests** - Query multiple DNS servers simultaneously and use fastest response
4. **Domain Filtering** - Filter domains against blocklists using pattern matching
5. **Filter Lists Management** - Add/remove/enable/disable filter lists (AdAway format)
6. **DNS over HTTPS (DoH)** - Support encrypted DNS via HTTPS
8. **Logging** - Log DNS queries with blocked/allowed status
9. **VPN Mode** - Route all DNS through the app via VPN (local VPN service)

### UI Features
1. **Dashboard** - Show filtering status, statistics, toggle on/off
2. **DNS Servers Screen** - Manage upstream DNS servers
3. **Filter Lists Screen** - Manage blocklists
4. **Logs Screen** - Planned, not currently implemented
5. **Settings Screen** - Planned, not currently implemented

## 4. UI/UX Design Direction

- **Visual Style:** Material Design 3 with dynamic theming
- **Color Scheme:** Blue primary color, with green for "allowed" and red for "blocked" indicators
- **Navigation:** Bottom navigation with 4 tabs: Dashboard, DNS Servers, Filter Lists, Logs
- **Layout:** Clean, information-dense cards showing status and statistics
- **Dark Mode:** Full support with system preference detection
