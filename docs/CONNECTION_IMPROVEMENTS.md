# Connection improvements

The exit-node list now offers **Automatic node** alongside manual choices. It uses mihomo's existing checks every five minutes, with a 100 ms tolerance to avoid switching for small changes. Existing manual selections remain manual. Hidden-node exclusions take effect after reconnecting; the node list marks the previous configuration stale.

Routing rules now include three opt-in presets: direct access to `.ir` domains, direct access to local networks, and blocking a small built-in list of common advertising/tracking domains. Custom rules remain intact. Block rules take priority over direct rules. Presets are applied to both Aether and carrier/exit-chain sessions; changing a preset reconnects an active session. Direct traffic uses the device's real address. The ad list updates with app releases; it is not a full malware filter or a geographic database.

Psiphon's local proxy now forwards UDP through the existing native core's shared gateway channel. Tor remains TCP only. No new native runtime or application dependency is required. SOCKS fragments are rejected, and resource limits bound concurrent sessions and destinations. A real-network UDP DNS test is opt-in with the existing `psiphon=1` device-test argument.

[Worker setup](WORKER_SETUP.md) explains deployment on Cloudflare Workers and Pages, custom domains and the independent TCP connection modes. Proxy profiles provide a separate direct connection with protocol and node selection. Cloudflare tokens stay in memory while the management screen is open.

Connection availability depends on the network and the selected carrier.
