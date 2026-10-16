# Automatic endpoint discovery and Identity release candidate

The automatic direct engine, Aether carrier lane, and automatic protocol
recovery use the same discovery coordinator as Routes. Identity provisioning
finishes before profile searches begin. The coordinator rechecks network-scoped
hints, tries both MASQUE framings and TLS recovery profiles, and selects a route
that carried traffic. It reserves ten seconds of the route budget for native
preparation and opening the tunnel. The winning configuration, including its
framing and TLS options, is passed to the running engine and route memory.
Stopping a carrier cancels both preparation and discovery. A changed network
invalidates the pending selection. Manual fixed-protocol connections keep their
existing behavior.

## Confirmed code defects repaired

- MASQUE enrollment discarded interface addresses and the assigned edge from
  the PATCH response. They now update the Identity before probing and saving.
- Device lookup used a refreshed edge with the old tunnel source address.
  Discovery and embedded preparation now use and persist the refreshed metadata
  together. An empty partial response preserves existing addresses.
- Cancelling a registration future could omit the persistent registration
  budget. An attempt is now recorded before the POST, including interruption.
- Retry-After was truncated to thirty seconds, including longer server holds.
  Full numeric and HTTP-date holds are retained; a 429 is returned without
  sending the registration again in the same call.
- Enrollment persisted an in-flight marker without the key being enrolled.
  The pending certificate/key pair is now stored before PATCH and reused after
  an interruption, including renewal of an existing certificate. An ambiguous
  enrollment failure is not reported as a confirmed usable old certificate.

Regression tests use fake discovery backends and local TLS listeners. They
exercise H3 failure/H2 selection, hint revalidation, preservation of proxy and
identity configuration, route budgets, network invalidation, metadata changes,
interrupted registration and enrollment, and a single HTTP 429 with a one-hour
Retry-After. Production TLS authentication stays enabled.

## Evidence still required for stable publication

Passing local tests does not establish why every reported phone fell back to
Psiphon/Tor. Those symptoms alone cannot distinguish Identity API blocking,
server rate limits, tunnel filtering, and a remote service failure.

The user explicitly authorized a separate diagnostic registration on
2026-10-16. The live registration, MASQUE enrollment, H2/H3 authenticated checks
and tunneled HTTP requests passed on the Windows test host. The embedded engine
also passed provisioning with the same Identity, scanning, preparation and two
independent HTTP checks per framing. See `LIVE_IDENTITY_TEST_2026-10-16.md` for
sanitized evidence and scope. Do not run the older ignored tests that print
registration JSON.

Before stable publication, complete `DEVICE_TEST_PLAN.md`, especially fresh
installation, retained/imported Identity, interrupted enrollment, restart,
blocked UDP, network switching, and reconnection without a new registration.
Test on affected carriers as well as an unrestricted network. Archive the APK
hash and sanitized evidence. The current APK is a preview release candidate;
it has not been published as stable.
