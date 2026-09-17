# Physical-device acceptance plan

Do not label a release fully functional until every mandatory check passes on
at least one API 26 device and one current target-API device.

## Whole-device TUN

1. Grant VPN and notification permission, connect, and confirm Android shows
   WhiteAesther as the active VPN.
2. Verify IPv4 browsing and DNS resolution with mobile data and Wi-Fi.
3. Verify IPv6 on a native dual-stack network; repeat on IPv4-only Wi-Fi.
4. Confirm DNS queries use the tunnel resolvers and no ISP DNS leak appears.
5. Confirm the Aether upstream socket does not loop into the TUN interface.
6. Switch Wi-Fi to mobile data, lock/unlock the device, and run for 30 minutes.
7. Revoke VPN permission and verify the foreground service and native engine stop.

## Local SOCKS5

1. Select proxy mode and a non-default port, then connect an Android client to
   `127.0.0.1:<port>` using SOCKS5.
2. Verify TCP, UDP-associate, IPv4, IPv6, and DNS behavior.
3. Confirm the selected port is not reachable from another LAN device.
4. Attempt a conflicting port and verify a clear error is shown.

## Endpoint discovery

1. Run each scan strategy on IPv4-only and dual-stack networks; verify cancel
   returns the UI to idle without leaving native work running.
2. Select a scan result and verify Custom first connects to that exact endpoint.
3. Test valid IPv4 and bracketed IPv6 endpoints with both MASQUE H3 and H2.
4. Verify an invalid address is rejected before connection and an incompatible
   endpoint fails the authenticated MASQUE check.
5. Verify Custom first falls back to scanning while Custom only fails closed.
6. Start a scan while disconnected, then immediately connect; confirm only one
   native operation runs and no unprotected upstream socket is created.

## Lifecycle and security

1. Start/stop each mode 20 times and switch modes without restarting the app.
2. Force-stop and reboot; confirm no orphan VPN or native process remains.
3. Deny notifications, use battery saver, and test background restrictions.
4. Inspect `adb logcat` for private keys, access tokens, packet payloads, crashes,
   ANRs, and JNI exceptions. None are acceptable.
5. Run `adb shell dumpsys connectivity`, `dumpsys vpn_management`, and packet
   capture/leak tests; archive evidence with the release candidate.

## Release evidence

Record device model, Android/API version, APK SHA-256, network type, result for
each step, and links to sanitized logs. Any mandatory failure blocks release.
