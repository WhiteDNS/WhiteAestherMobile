# Worker engine

The Xray-core revision in `go.mod` is the same revision used by BPB-VPN commit
381df1270e3b81feaa7a878603ae049cf79a560f. `go.sum` locks its dependencies.
`build.ps1` stages the pinned module's source and builds the CLI for Android, changing
only Browser Dialer CORS and WebSocket Origin checks to the loopback page's own
origin. The upstream page otherwise exposes its dialer token through wildcard
CORS. Exact source anchors are checked before adaptation; the original module cache is
never modified. The `lib*.so` files
are PIE executables, not JNI libraries; Android extracts them through the
existing legacy native-library packaging, just like Tor's transports.

Run `pwsh -File native/xray/build.ps1` with Go and Android NDK 29 installed.
Xray runs in a child process and exposes a loopback SOCKS listener. It never
owns the VPN interface; the existing service and mihomo own routing and the
kill switch. Browser Dialer uses Xray's loopback dialer page in a WebView.
The executable must be stopped when the VPN ends, including failed startup.

Xray-core is MPL-2.0; see its pinned upstream source and license. The existing
BPB-derived Worker code remains GPL-3.0. No Cloudflare credentials are included.
