# Live Identity and endpoint test — 2026-10-16

The user explicitly authorized creation and testing of an independent Cloudflare
Identity. The diagnostic cache was absent before the first execution. No phone
credentials, private keys, tokens or account identifiers are included here.

## Result

Registration and MASQUE key enrollment both completed successfully. The assigned
endpoint was `162.159.198.2:443`. Certificate pin verification stayed enabled.

Both `www.cloudflare.com` and
`consumer-masque-proxy.cloudflareclient.com` were tested as SNI, with real HTTP
204 traffic through H2 and H3. All four requests succeeded. The HTTP-check
measurements were 123.8–138.9 ms; these are test request timings, not a speed or
uptime guarantee.

The comparison address `162.159.197.3:443` did not pass the tested tunnel
validation on this network. Failures retained their actual TLS/pin close
reasons, rather than being reported as an open tunnel without traffic.

## Embedded production engine path

The same test Identity was loaded into the engine's MASQUE slot. No additional
registration was needed. The default `firewall` padding profile and IPv4 were
used. These are the Rust entry points invoked by the Android bridge:

| Check | H2 | H3 |
| --- | --- | --- |
| `provision_embedded` | Same Identity reused | Same Identity reused |
| `scan_embedded` | Found assigned endpoint, 449.6 ms | Found assigned endpoint, 473.1 ms |
| `prepare_embedded` | Selected the scanned endpoint | Selected the scanned endpoint |
| `test_embedded_peer`, HTTP check 1 | 146.0 ms | 135.7 ms |
| `test_embedded_peer`, HTTP check 2 | 142.2 ms | 120.2 ms |
| Persisted credentials | Original test Identity retained | Original test Identity retained |

Each traffic check creates a new tunnel and requests the engine's
`www.gstatic.com/generate_204` target through it. A second process was also used
to verify reuse from the engine's saved files, without copying the Identity
over those files again. All four repeated HTTP checks passed: H2 measured
144.7 and 137.4 ms; H3 measured 137.5 ms and 3.074 s. The slower final check
shows latency variation even on this successful route. Throughput and long-term
stability were not measured.

The initial Windows diagnostic harness overflowed its default main-thread stack
during embedded preparation. The harness was corrected to use the Android
bridge's existing 8 MiB owned native and Tokio stacks, and to box large futures.
The subsequent embedded checks above passed. No Android runtime change was
needed for that harness correction.

## Scope

This establishes that a fresh test Identity and the patched production Rust
engine can authenticate, discover, prepare and carry traffic from this host's
network. It does not exercise Android JNI, VPN socket protection, the Kotlin
automatic race, physical phones or the affected carriers. Local app tests cover
the discovery coordinator separately; physical-device acceptance remains
required before stable publication.

The previously supplied `auto-identity-rc1-preview` ARM64 APK contains the same
engine fixes. APK SHA-256:
`dbb113fe9cffd2b61053c0c3748ae92b0a23c64960d5aa300fb801d178179fef`.
Only the diagnostic harness and documentation changed during this live test.
