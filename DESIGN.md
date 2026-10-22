<!-- Hallmark · pre-emit critique: P5 H5 E4 S5 R5 V4 -->
# WhiteAesther Android design system

The native Android interface follows the WhiteDNS 1.6.9 / WhiteVPN family.
Source reference: https://github.com/WhiteDNS/WhiteDNS-Android/tree/1.6.9
This system supersedes the older HTML prototype in design/ for the Android app.

## Structure

Use a compact, functional workbench: shared app header, one primary connection
control, grouped selection rows, and progressive disclosure for technical detail.
Bottom navigation: Connect, Methods, Tools, Logs. App settings live in the header.
Retain all existing connection, routing, identity, update and diagnostic features.
Methods represent Aether/Psiphon/Tor choices, not imported server subscriptions.

Home exposes VPN/Proxy, a method selector and app coverage. Technical values and
session totals belong behind Connection details. Settings that cannot be changed
from Home during a session explain that the user must disconnect first.
Never display invented progress, latency, connection quality or server counts.

## Native tokens

Theme.kt is the palette source for every screen and shared component.
Dark: background #050E09, surface #09140F, elevated #0F1B16,
border #273730, divider #1B2A23, text #E4EEE9, secondary #9BA8A2,
accent/live #3FBE90, working #E8AA4E, error #F07F77.
Light: background #F1F9F5, surface #F8FDFB, elevated #ECF6F1,
border #B9C8C1, divider #D3E2DB, text #0A1410, secondary #495650,
accent/live #007E50, working text #9A6400, error #CF4040.

Use Vazirmatn for interface text in both languages. Keep the existing monospaced
font for addresses, ports, measured traffic, clocks and diagnostic records.
Use sentence case, compact headings and normal letter spacing.
Maintain user font scaling; expand or stack controls instead of clipping text.

Spacing follows a 4 dp rhythm. Screen inset 16 dp, card radius 22 dp,
control radius 8 dp, minimum interactive target 48 dp, phone content width 520 dp.
Use a navigation rail on wide screens and TV. Keep Persian layout RTL and technical
addresses LTR. Focus indicators and explicit button/tab semantics are required.

## Motion

Keep the shared native orb and its four real Aesther states. Stop animation when
the app is backgrounded or the control is detached. Honor Android animator settings.
Use restrained state changes elsewhere; avoid decorative entrance animations.

## Implementation and validation

Native exports are Theme.kt, Type.kt and the reusable Compose components; this
Android project does not introduce web CSS tokens or web framework exports.
Changes stay in the presentation layer. Saved preferences and engine contracts
remain compatible. Verify navigation, direct Home selections, busy states,
permission/update actions, RTL, large text, small phones, tablets and TV input.
