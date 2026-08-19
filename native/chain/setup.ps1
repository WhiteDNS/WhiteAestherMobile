# Fetches the Go core that carries mihomo, and patches it.
#
#   pwsh -File native/chain/setup.ps1 [-Force]
#
# third_party/ is not committed: it is large, regenerable, and separately
# licensed. The build refuses rather than producing an APK missing an ABI, so a
# failure here is visible now instead of at run time on a device nobody tested.

param([switch]$Force)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# FlClash's core wraps Clash.Meta and already exports startTUN taking a
# descriptor, which is the part that would otherwise need patching in.
#
# Both are pinned to the revision the shipped binaries were built from. This is
# not only reproducibility: the core and Clash.Meta are GPL-3.0, so what we
# distribute has to be traceable to source somebody else can fetch. A branch tip
# is not an answer to "which source is this binary".
$CoreRepo = 'https://github.com/chen08209/FlClash.git'
$CoreRev  = '62addf738a76b1a492e19af2dbabdb6d572b9e72'
$MetaRepo = 'https://github.com/chen08209/Clash.Meta.git'
$MetaRev  = '80362fc1895dcf60b79b562896653046e0687413'
$ThirdParty = Join-Path $here 'third_party'
$Clone = Join-Path $ThirdParty 'flclash'
$CoreSrc = Join-Path $Clone 'core'

if ($Force -and (Test-Path $ThirdParty)) {
    Write-Host "removing existing third_party/" -ForegroundColor Yellow
    Remove-Item $ThirdParty -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $ThirdParty | Out-Null

if (-not (Test-Path (Join-Path $CoreSrc 'go.mod'))) {
    Write-Host "fetching the core (sparse, blobless)..." -ForegroundColor Cyan
    if (Test-Path $Clone) { Remove-Item $Clone -Recurse -Force }
    # No --depth: a shallow clone cannot check out an arbitrary revision, and
    # the pin is worth more than the download it saves.
    git clone --filter=blob:none --sparse $CoreRepo $Clone 2>&1 | Out-Null
    Push-Location $Clone
    git sparse-checkout set core 2>&1 | Out-Null
    git checkout --quiet $CoreRev 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw "could not check out core $CoreRev" }
    Pop-Location
    Write-Host "  -> native/chain/third_party/flclash/core @ $($CoreRev.Substring(0,12))" -ForegroundColor Green
} else {
    Write-Host "core already present" -ForegroundColor Cyan
}

$Meta = Join-Path $CoreSrc 'Clash.Meta'
if (-not (Test-Path (Join-Path $Meta 'go.mod'))) {
    Write-Host "fetching Clash.Meta..." -ForegroundColor Cyan
    if (Test-Path $Meta) { Remove-Item $Meta -Recurse -Force }
    git clone --filter=blob:none $MetaRepo $Meta 2>&1 | Out-Null
    Push-Location $Meta
    git checkout --quiet $MetaRev 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Pop-Location; throw "could not check out Clash.Meta $MetaRev" }
    Pop-Location
    Write-Host "  -> core/Clash.Meta @ $($MetaRev.Substring(0,12))" -ForegroundColor Green
}

# Mihomo advertises a hardcoded REALITY client version of 1.8.2 in the ClientHello
# session id, while Xray builds those bytes from its own version. A current Xray
# server rejects the handshake outright. Measured, not guessed.
$Patch = Join-Path $here 'patches/0001-reality-client-version.patch'
if (Test-Path $Patch) {
    Push-Location $Meta
    $already = git apply --reverse --check $Patch 2>$null; $ok = $?
    if ($ok) {
        Write-Host "REALITY patch already applied" -ForegroundColor Cyan
    } else {
        git apply $Patch
        Write-Host "REALITY patch applied" -ForegroundColor Green
    }
    Pop-Location
}

Write-Host "`ncore ready." -ForegroundColor Green

# The core's checked-in go.sum was written against a different Clash.Meta than
# the one pinned above, so it is missing an entry for sing-shadowtls and the
# first build fails on it. Reconciling here rather than carrying a patched go.sum
# keeps the pinned pair the only thing that decides what gets built.
Write-Host "reconciling modules..." -ForegroundColor Cyan
Push-Location $CoreSrc
try {
    $env:GOFLAGS = '-mod=mod'
    go mod tidy
    if ($LASTEXITCODE -ne 0) { throw "go mod tidy failed" }
} finally {
    Pop-Location
    Remove-Item Env:GOFLAGS -ErrorAction SilentlyContinue
}

Write-Host "core ready. Build with: pwsh -File native/chain/build.ps1" -ForegroundColor Green
