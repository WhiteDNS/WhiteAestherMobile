# Privacy

WhiteAesther routes traffic only when the user explicitly starts whole-device
VPN or local SOCKS5 mode. The app does not include advertising, analytics, or
developer-operated telemetry.

The Aether engine contacts its upstream provisioning, discovery, and encrypted
tunnel services. Device identity material is stored in Android app-private
storage, excluded from backup, and is not shown in diagnostics. Packet payloads
are processed in memory and are not intentionally logged or persisted.

SOCKS5 listens on loopback only. Whole-device mode uses Android's visible VPN
indicator and a foreground notification with a stop action.
