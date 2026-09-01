# Google Play submission notes

WhiteAesther is a VPN/circumvention client. The Play Console submission must be
kept explicit about that because the app declares and uses Android's
`VpnService` API.

## VpnService declaration

Complete the Play Console VpnService declaration form for the stable release.
Upload a short video, under 90 seconds, that shows:

1. Opening WhiteAesther.
2. Tapping the connect control in whole-device mode.
3. Reading the in-app VPN disclosure.
4. Accepting that disclosure.
5. Accepting Android's VPN permission prompt.
6. The tunnel reaching the connected state.

Steps 3 and 4 need the disclosure screen described below, which does not exist
yet.

## In-app disclosure — not built yet

Play requires a prominent in-app disclosure before Android's VPN permission
prompt, and this app does not have one. Nothing in the source shows a screen
between the connect control and the system dialog, so the video described above
cannot be recorded as written until it exists.

What it has to say, once built:

- `VpnService` is used only for whole-device mode.
- Device network traffic is routed into WhiteAesther and carried through an
  encrypted Aether tunnel, using MASQUE over HTTP/3 or TLS over TCP.
- VPN access is not used for ads, analytics, telemetry, traffic injection or
  monetization.

This is the one blocking item on this page. Everything below it is already true
of the build.

## Encryption

Traffic from the device to the tunnel endpoint is encrypted by the Aether engine:

- MASQUE over HTTP/3 for the primary QUIC path.
- TLS over TCP for networks that block UDP.

The Android manifest also sets `android:usesCleartextTraffic="false"` and uses a
network security config that blocks cleartext by default.

## Privacy URL

Use a hosted copy of `PRIVACY.md` as the Play Store privacy policy URL. The
policy states that WhiteAesther has no advertising, analytics, or
developer-operated telemetry; diagnostics are user-reviewed before sharing.

## Store listing disclosure

Include wording like this in the public Play Store description:

> WhiteAesther uses Android's VpnService API to create a user-started
> whole-device VPN tunnel. When enabled, device traffic is routed through
> WhiteAesther and carried to the tunnel endpoint over encrypted Aether
> transports. WhiteAesther does not use VPN access for ads, analytics,
> telemetry, traffic injection, or monetization.

## What was checked against the source

Verified on 2026-09-01, at commit 3dd2ac96:

- `android:usesCleartextTraffic="false"` — present in the manifest.
- `android:networkSecurityConfig` — present, pointing at
  `res/xml/network_security_config.xml`, which exists.
- `PRIVACY.md` — exists, and states no advertising, analytics or telemetry.
- The in-app disclosure — **absent**. No such screen is in the source.
