# Endpoint discovery

The Android scanner discovers connection recipes, not just IP addresses. A
result records its transport, TLS options, padding profile and address-family
setting, including the fragmentation preset and ECH setting. Selecting
it applies those settings together and enables endpoint fallback.

## Search pipeline

Automatic connections also use this coordinator, for the direct engine and
Aether carrier lane. They provision Identity once, stop once one recipe passes
both checks, and reserve ten seconds for preparation/startup. Automatic
reconnects recheck the same network-scoped hints. See `AUTO_IDENTITY_RC.md` for
the Identity fixes and remaining stable-release validation.

1. Read up to three saved hints for the current network and TLS configuration.
   Recheck them before presenting them as current results.
2. Try the selected MASQUE framing and its sibling in separate native passes.
   Automatic uses the connection planner's ordering. TLS record fragmentation,
   H2 ECH and H3 ECH are additional recovery passes. WireGuard and nested modes
   stay in their own protocol; a nested result describes the outer hop.
3. Native MASQUE discovery includes the account's assigned endpoints before
   the seed pool. IPv4, IPv6 and alternate-port lanes take turns, so a large
   IPv4 pool cannot consume the entire deadline before the other lanes run.
4. Publish native completion counts and authenticated hits every 300 ms. A hit
   is initially labelled as awaiting its internet check.
5. Confirm a bounded shortlist with two independent tunnel setups and DNS/HTTP
   round trips through the tunnel. Rank repeated success ahead of handshake
   latency, then variation and round-trip time. Keep handshake-only results
   explicitly labelled when the HTTP check fails or the budget runs out.
6. If no result passes a traffic check, spend remaining time expanding the
   first two search profiles. Stop after three results pass both checks.

Each initial pass is bounded to 18 seconds including registration and ECH;
expanded passes are bounded to 60 seconds and confirmations to 25 seconds.
The whole request has a 210-second budget, 60 seconds for Turbo, or 360 seconds
for Thorough. Turbo limits each confirmation to 10 seconds. Native
strategy deadlines and device concurrency limits still apply. Quiet/Verified
strategies retain their quieter probing settings. Native calls are sequential
because TLS/environment configuration is process-wide.

## Cancellation, network changes and storage

Cancellation interrupts enrollment, ECH, probing and traffic confirmation via
the same native request slot. Partial fresh results survive an explicit stop.
A network handle/key change cancels discovery and discards results for the old
connection. The new request starts with an empty list.

Only results that passed a traffic check become saved hints. The cache has a
seven-day expiry, twelve hints per entry and eight network/configuration
entries. Network identifiers and TLS configuration form a SHA-256 cache key;
only endpoint recipes and timestamps are stored, without credentials. Saved
success counts are not restored. Forget-and-search removes the matching hints.

## What the checks prove

Authenticated probing checks the actual tunnel protocol and existing certificate
pins. A traffic confirmation resolves and requests the engine's existing
`www.gstatic.com/generate_204` target through that tunnel, requiring HTTP 204.
It establishes that this target worked at that moment. Failure can also mean
that the target is unavailable; it does not prove the endpoint is unusable for
every destination. Two checks are a short stability sample, not an uptime
guarantee. Nested tunnel confirmation concerns the outer hop; connection still
has to establish its inner hop. Scanning cannot create a reachable server when
the network blocks every supported path.

## Verification

- `EndpointDiscoveryTest` uses a fake backend to cover blocked UDP/TCP fallback,
  live results, traffic failure, deepening, cancellation, network changes,
  stale hints, protocol isolation, selection, ranking and request budgets.
- Native tests check candidate fairness, assigned-address priority, family
  filtering and telemetry serialization. Existing local protocol tests remain
  applicable.
- Pinned loopback TLS tests cover negotiated H2, explicitly negotiated HTTP/1.1
  rejection, and the existing H2 fallback when an edge omits ALPN. No production
  certificate checks are disabled for these tests.
- `EndpointDiscoveryUiTest` checks recipe selection, disabled selection during
  discovery, Persian rendering and network/security-scoped cache behavior.
- Before release, run the physical-device endpoint checks in
  `DEVICE_TEST_PLAN.md` on affected mobile carriers and Wi-Fi, including blocked
  UDP, interrupted registration and a network switch during confirmation.

## Diagnosing an empty scan

Scan details and the app's diagnostic log include bounded examples of the
underlying errors, alongside per-stage counts. QUIC closure before CONNECT-IP
acceptance retains the local/peer close code; it is not labelled as a tunnel
that opened but carried no traffic. Protocol negotiation, ECH rejection,
client-certificate rejection, pin mismatch and traffic failure have separate
categories. Rejections from unrelated web/CDN addresses do not stop the
discovery pass while slower candidates are still pending.

These checks improve discovery and diagnosis; local test success does not
establish that an affected phone can connect. The opt-in
`examples/diagnose_endpoint.rs` diagnostic creates or loads a separate test
registration and performs pinned live probes. Run it only with explicit
authorization for registration and credential use, passing a private cache
path followed by `--allow-live`. It prints outcomes without identity secrets.
