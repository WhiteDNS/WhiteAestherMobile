# Fetches the embedded Psiphon server list into the app's assets, and refuses to
# install one that tunnel-core would not accept.
#
#   pwsh -File native/psiphon/setup.ps1 [-Force]
#
# Not committed, for the same three reasons native/chain/third_party is not: it
# is large, it is regenerable, and it is not ours. The build packages whatever it
# finds; a build without it produces an app whose Psiphon carrier reports itself
# unavailable rather than one that fails at connect time on a user's phone.

param([switch]$Force)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent (Split-Path -Parent $here)
$dest = Join-Path $root 'app/src/main/assets/psiphon_server_entries.txt'
$keyFile = Join-Path $here 'server_entry_signature_key.txt'
$thirdParty = Join-Path $here 'third_party'
$checkout = Join-Path $thirdParty 'psiphon-tunnel-core'
# Beside the checkout rather than beside the list: anything next to the list is
# under assets/ and would ship inside the APK.
$marker = Join-Path $thirdParty 'server_list.verified'

# What this file is: one hex-encoded Psiphon server entry per line, which is the
# format psiphon-tunnel-core's startTunneling takes as its embedded bootstrap
# list. Psiphon replaces it from inside the tunnel once a connection is up, so
# it goes stale in the way a phone book does rather than in the way a key does.
#
# Where it comes from: pinned to a revision, not a branch. A branch tip is not an
# answer to "which list is in this APK", and this one reaches the network on
# first connect.
$Repo   = 'mbm110/MSN-GUARD'
$Rev    = 'a6379f5d060bc7ca48a4c4ee015648afc8c07a05'
$Path   = 'app/src/main/assets/server_entries.txt'
$Sha256 = '6d6d10c4ef8eaf656cb9614513568f40d5590477fc25215507517d51fc6a293e'

# The tunnel-core whose own code checks the signatures: the revision the app
# ships as ca.psiphon:psiphontunnel:2.0.41, so the answer here is the answer the
# phone will give.
$TunnelCoreRepo = 'https://github.com/Psiphon-Labs/psiphon-tunnel-core.git'
$TunnelCoreRev  = 'v2.0.41'

function Get-Sha256([string]$file) {
    (Get-FileHash $file -Algorithm SHA256).Hash.ToLower()
}

function Get-TextSha256([string]$text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($text)
    $hash = [Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    ([BitConverter]::ToString($hash) -replace '-', '').ToLower()
}

# Checks every entry of a list against the key the app ships with.
#
# The digest pin proves the file has not changed; it proves nothing about the
# entries. Their signatures do, and tunnel-core only checks them when it is
# given the key -- which is what server_entry_signature_key.txt is for. A list
# that does not verify is either a key Psiphon has rotated, which would make the
# app reject every entry at runtime and quietly stop connecting, or a list that
# is not Psiphon's, which is worse. So this refuses rather than warns.
#
# Verified once per list-and-key pair. Those are the two things that can change,
# and neither changes between two ordinary builds, so checking every time would
# only make Go and a clone of tunnel-core part of every local build. CI starts
# from nothing, so it always checks.
function Test-ServerList([string]$list) {
    $key = (Get-Content $keyFile -Raw).Trim()
    $pair = "$(Get-Sha256 $list) $(Get-TextSha256 $key)"
    if ((Test-Path $marker) -and ((Get-Content $marker -Raw).Trim() -eq $pair)) {
        Write-Host "  server list already verified against this signature key" -ForegroundColor Cyan
        return
    }
    # Gone before the check rather than after it, so a failed or interrupted
    # check can never leave a marker claiming this pair was verified.
    Remove-Item $marker -Force -ErrorAction SilentlyContinue

    if (-not (Test-Path (Join-Path $checkout 'go.mod'))) {
        Write-Host "cloning psiphon-tunnel-core $TunnelCoreRev for the signature check..." -ForegroundColor Cyan
        New-Item -ItemType Directory -Force -Path $thirdParty | Out-Null
        git clone --quiet --depth 1 --branch $TunnelCoreRev $TunnelCoreRepo $checkout
        if ($LASTEXITCODE -ne 0) { throw "could not clone psiphon-tunnel-core $TunnelCoreRev" }
    }
    # A checkout that is present but on another revision would answer for a
    # tunnel-core the app does not ship. Refuse rather than check with it.
    $described = (git -C $checkout describe --tags --always).Trim()
    if ($described -ne $TunnelCoreRev) {
        throw "$checkout is at $described, not $TunnelCoreRev. Delete it and run this again."
    }

    $probe = Join-Path $checkout 'zz_whiteaesther_verify'
    New-Item -ItemType Directory -Force -Path $probe | Out-Null
    Copy-Item (Join-Path $here 'verify/main.go') (Join-Path $probe 'main.go') -Force

    $savedPreference = $ErrorActionPreference
    $savedCgo = $env:CGO_ENABLED
    $savedToolchain = $env:GOTOOLCHAIN
    Push-Location $checkout
    try {
        # Go writes progress to stderr; that is not a failure, the exit code is.
        $ErrorActionPreference = 'Continue'
        # The host's own toolchain, whatever the build is targeting.
        $env:CGO_ENABLED = '0'
        Remove-Item Env:GOOS, Env:GOARCH -ErrorAction SilentlyContinue
        # tunnel-core v2.0.41 declares go 1.26.0. The workflows install an older
        # Go for the rest of the build and set GOTOOLCHAIN=local, which makes Go
        # refuse the module outright rather than fetch the toolchain it names.
        # Auto, for this step only, lets it fetch exactly that toolchain without
        # moving the chain engine or the transports onto a Go they were not
        # built with. Its dependencies are vendored, so nothing else is fetched.
        $env:GOTOOLCHAIN = 'auto'
        # A verdict is final; anything else is Go failing to get as far as one --
        # most often a toolchain download proxy.golang.org dropped mid-stream --
        # and is worth another try. Never retried away: an answer of N/430.
        $verdict = 'server entries verify against the signature key'
        foreach ($attempt in 1..3) {
            $out = (go run ./zz_whiteaesther_verify $list $keyFile 2>&1 | Out-String).Trim()
            $code = $LASTEXITCODE
            if ($code -eq 0 -or $out.Contains($verdict) -or $attempt -eq 3) { break }
            Write-Host "  the verifier did not run (attempt $attempt of 3); retrying" -ForegroundColor Yellow
            Start-Sleep -Seconds (5 * $attempt)
        }
    } finally {
        Pop-Location
        $ErrorActionPreference = $savedPreference
        $env:CGO_ENABLED = $savedCgo
        $env:GOTOOLCHAIN = $savedToolchain
        Remove-Item $probe -Recurse -Force -ErrorAction SilentlyContinue
    }

    if ($code -ne 0 -and -not $out.Contains($verdict)) {
        throw "The Psiphon signature check could not run: $out"
    }
    if ($code -ne 0) {
        throw ("The Psiphon server list does not verify against the signature key the app ships with. " +
            "Either Psiphon rotated the key or the list is not Psiphon's; refusing it.`n$out")
    }
    Write-Host "  $out" -ForegroundColor Green
    Set-Content -Path $marker -Value $pair -NoNewline
}

if ((Test-Path $dest) -and -not $Force) {
    if ((Get-Sha256 $dest) -eq $Sha256) {
        Write-Host "server list already present and matches the pin" -ForegroundColor Cyan
        # Present is not the same as checked: this path used to return here, so
        # a list installed before the signature check existed was never checked.
        Test-ServerList $dest
        exit 0
    }
    Write-Host "server list present but does not match the pin; replacing" -ForegroundColor Yellow
}

New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
$url = "https://raw.githubusercontent.com/$Repo/$Rev/$Path"
Write-Host "fetching the embedded server list..." -ForegroundColor Cyan
$tmp = "$dest.part"
Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing

# Checked before it is moved into place, so a truncated or substituted download
# never becomes the list a client bootstraps from. The digest catches a file that
# changed; the signature check below catches a list that is not Psiphon's, or a
# key Psiphon has rotated.
$have = Get-Sha256 $tmp
if ($have -ne $Sha256) {
    Remove-Item $tmp -Force
    throw "server list checksum mismatch: expected $Sha256, got $have"
}

# One line that is not hex is a file that is not this format -- an HTML error
# page saved with a 200, most likely -- and tunnel-core would reject the lot
# without saying which line lost it.
$bad = Select-String -Path $tmp -Pattern '^[0-9a-fA-F]+$' -NotMatch | Select-Object -First 1
if ($bad) { Remove-Item $tmp -Force; throw "server list is not hex at line $($bad.LineNumber)" }

try {
    Test-ServerList $tmp
} catch {
    Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    throw
}

Move-Item $tmp $dest -Force
$kb = [math]::Round((Get-Item $dest).Length / 1KB)
$lines = (Get-Content $dest | Measure-Object -Line).Lines
Write-Host "  -> app/src/main/assets/psiphon_server_entries.txt  ($kb KB, $lines entries)" -ForegroundColor Green
