# Fetches the embedded Psiphon server list into the app's assets.
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

if ((Test-Path $dest) -and -not $Force) {
    $have = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLower()
    if ($have -eq $Sha256) {
        Write-Host "server list already present and matches the pin" -ForegroundColor Cyan
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
# never becomes the list a client bootstraps from. Every entry is signed and
# verified by tunnel-core itself, but a wrong file here is still an app that
# spends its first minute dialling nothing.
$have = (Get-FileHash $tmp -Algorithm SHA256).Hash.ToLower()
if ($have -ne $Sha256) {
    Remove-Item $tmp -Force
    throw "server list checksum mismatch: expected $Sha256, got $have"
}

# One line that is not hex is a file that is not this format -- an HTML error
# page saved with a 200, most likely -- and tunnel-core would reject the lot
# without saying which line lost it.
$bad = Select-String -Path $tmp -Pattern '^[0-9a-fA-F]+$' -NotMatch | Select-Object -First 1
if ($bad) { Remove-Item $tmp -Force; throw "server list is not hex at line $($bad.LineNumber)" }

Move-Item $tmp $dest -Force
$kb = [math]::Round((Get-Item $dest).Length / 1KB)
$lines = (Get-Content $dest | Measure-Object -Line).Lines
Write-Host "  -> app/src/main/assets/psiphon_server_entries.txt  ($kb KB, $lines entries)" -ForegroundColor Green
