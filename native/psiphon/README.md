# Psiphon carrier

Psiphon as an alternative to the Aether engine: it finds its own way out of a
network, and mihomo routes the interface into the SOCKS5 listener it produces.

```powershell
pwsh -File native/psiphon/setup.ps1     # fetch the embedded server list
```

Nothing else to build. The tunnel core arrives as `ca.psiphon:psiphontunnel`
from Psiphon's own maven distribution, pinned in `app/build.gradle.kts` and
scoped to that group alone in `settings.gradle.kts`.

## Its own process, deliberately

`PsiphonService` is declared `android:process=":psiphon"`. Go supports one
runtime per process, and this process already spends its one on mihomo -- two
`-buildmode=c-shared` libraries export the same runtime symbols into a single
linker namespace, and a call binding to the wrong copy enters a runtime that has
never heard of the calling goroutine.

The cost is an IPC hop carrying two integers and a string. The alternative was
merging psiphon-tunnel-core into the FlClash module so there is one Go library
again, which is a dependency-resolution problem between two large trees that
both vendor quic-go.

## Why it cannot leak

Psiphon's own sockets have to leave by the physical network. `protect()` is no
help: it acts on a descriptor in the calling process and these are in another
one. What holds instead is that both processes share a uid and the interface is
built with `addDisallowedApplication(packageName)` -- `applySplitTunnel` is
called with `excludeSelf = true` on every path a carrier takes, because a
carrier always routes through mihomo.

Whole-device only. In proxy mode the listener applications are pointed at is
ours, with its own port validation and LAN rules, and Psiphon's is neither --
`runCarrierSession` refuses rather than substituting it.

## The server list

`app/src/main/assets/psiphon_server_entries.txt` is the bootstrap list, one
hex-encoded server entry per line. It is fetched by `setup.ps1` at a pinned
revision and checked against a SHA-256, and it is gitignored for the same
reasons `native/chain/third_party` is: large, regenerable, not ours.

A build without it produces an app whose Psiphon carrier reports the list
missing at connect time rather than one that spends two minutes dialling
nothing. Psiphon replaces the list from inside the tunnel once a connection is
up, so it goes stale the way a phone book does rather than the way a key does.

## The identifiers

`PropagationChannelId` and `SponsorId` say who distributed a client so Psiphon
can attribute usage and plan capacity. Real ones are issued by Psiphon Inc. to
partners. `PsiphonConfig` uses the all-Fs and all-1s placeholders that appear
throughout tunnel-core's own tests and in open-source clients that have not
asked for a channel of their own.

They are not credentials and nothing is authenticated by them. What they cost is
that our sessions are indistinguishable from every other unattributed client. If
this carrier turns out to matter to the people using it, asking Psiphon for a
channel is the next step, and it is a conversation rather than a patch.

## Licence

psiphon-tunnel-core is GPL-3.0. This app is AGPL-3.0 and section 13 permits the
combination; the notice obligation is real and is met in
`THIRD_PARTY_NOTICES.md`.
