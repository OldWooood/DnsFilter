# Changelog

## 3.0.0 — 2026-07-12

- Removed the app-level DNS response cache, stale serving, and prefetching.
- Delegated response caching to Android Resolver with positive TTLs clamped to 1–6 hours.
- Added RFC 2308-style blocked-domain NXDOMAIN responses with a 24-hour SOA negative TTL.
- Retained in-flight request coalescing, concurrent multi-server racing, and UDP socket reuse.
- Clarified that dashboard query counts represent requests handled by the VPN rather than upstream UDP packet counts.
- Removed the unused network-state listener and `ACCESS_NETWORK_STATE` permission.
