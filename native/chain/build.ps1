# Builds the chain core into one shared library per ABI.
#
#   pwsh -File native/chain/build.ps1 [-Abi arm64-v8a,x86_64,armeabi-v7a]
#
# ONE Go library in the process, and that is a requirement rather than a
# simplification. Two -buildmode=c-shared libraries are two Go runtimes exporting
# the same runtime symbols into one linker namespace; a call binding to the wrong
# copy enters a runtime that has never heard of the calling goroutine. It survives
# a couple of connect cycles and then dies inside a cgo callback.
#
# Our own engine is Rust, so Rust plus this is fine. A second Go library is not.

param([string[]]$Abi = @('arm64-v8a', 'x86_64', 'armeabi-v7a'))

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$core = Join-Path $here 'third_party/flclash/core'
$out  = Join-Path $here 'build'

if (-not (Test-Path (Join-Path $core 'go.mod'))) {
    throw "core missing. Run native/chain/setup.ps1 first."
}

# proxy.golang.org is a single point of failure for a release and it does drop
# connections mid-zip: one such stream error ended a v1.4.0 build twenty minutes
# in, with every module but one already fetched. Pull the whole graph up front
# and retry it, so the per-ABI builds below run against a warm cache and a bad
# second costs seconds instead of the release.
function Get-GoModules([string]$dir, [string]$label, [string]$flags) {
    Push-Location $dir
    try {
        if ($flags) { $env:GOFLAGS = $flags }
        foreach ($attempt in 1..3) {
            Write-Host "fetching modules for $label ..." -ForegroundColor Cyan
            go mod download
            if ($LASTEXITCODE -eq 0) { return }
            if ($attempt -eq 3) { throw "go mod download failed for $label" }
            Write-Host "  download failed, retrying ($attempt/3) ..." -ForegroundColor Yellow
            Start-Sleep -Seconds (5 * $attempt)
        }
    } finally {
        Pop-Location
        if ($flags) { Remove-Item Env:GOFLAGS -ErrorAction SilentlyContinue }
    }
}

Get-GoModules $core 'the exit chain engine' ''

# Resolve the SDK the way Gradle does rather than assuming where it lives. The
# SDK moved off C: once already, and the hardcoded path is what broke when it did.
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $props = Join-Path (Split-Path -Parent (Split-Path -Parent $here)) 'local.properties'
    if (Test-Path $props) {
        $match = Select-String -Path $props -Pattern 'sdk\.dir\s*=\s*(.+)' | Select-Object -First 1
        if ($match) {
            $sdk = $match.Matches[0].Groups[1].Value.Trim()
            $sdk = $sdk.Replace('\', '\').Replace('\:', ':')
        }
    }
}
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

$ndkRoot = Join-Path $sdk 'ndk'
if (-not (Test-Path $ndkRoot)) {
    throw "Android NDK not found under $sdk. Set ANDROID_HOME, or sdk.dir in local.properties."
}
$ndk = (Get-ChildItem $ndkRoot -Directory | Sort-Object Name -Descending)[0].FullName

# The NDK ships one toolchain per host, and CI is Linux while development is
# Windows. Getting this from the host rather than hardcoding it is what lets the
# release build the same libraries the developer does.
$hostTag = if ($IsLinux) { 'linux-x86_64' } elseif ($IsMacOS) { 'darwin-x86_64' } else { 'windows-x86_64' }
$bin = Join-Path $ndk "toolchains/llvm/prebuilt/$hostTag/bin"
if (-not (Test-Path $bin)) { throw "NDK toolchain not found for this host: $bin" }

# Only Windows wraps the clang drivers in .cmd shims.
$ext = if ($IsLinux -or $IsMacOS) { '' } else { '.cmd' }

# minSdk is 26, so the toolchain is pinned there rather than to the NDK default.
$targets = @{
    'arm64-v8a'   = @{ Arch = 'arm64'; Clang = "aarch64-linux-android26-clang$ext";    Arm = $null }
    'x86_64'      = @{ Arch = 'amd64'; Clang = "x86_64-linux-android26-clang$ext";     Arm = $null }
    'armeabi-v7a' = @{ Arch = 'arm';   Clang = "armv7a-linux-androideabi26-clang$ext"; Arm = '7'  }
}

Push-Location $core
try {
    foreach ($name in $Abi) {
        $spec = $targets[$name]
        if (-not $spec) { throw "unknown ABI: $name" }
        $cc = Join-Path $bin $spec.Clang
        if (-not (Test-Path $cc)) { throw "NDK clang not found: $cc" }

        $dir = Join-Path $out $name
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        $so = Join-Path $dir 'libwhiteaestherchain.so'

        $env:CGO_ENABLED = '1'
        $env:GOOS   = 'android'
        $env:GOARCH = $spec.Arch
        $env:CC     = $cc
        if ($spec.Arm) { $env:GOARM = $spec.Arm } else { Remove-Item Env:GOARM -ErrorAction SilentlyContinue }

        Write-Host "building $name ..." -ForegroundColor Cyan
        # 16 KB pages: Android 15+ requires it on some devices, and a library
        # aligned to 4 KB simply will not load there.
        go build -tags=with_gvisor -buildmode=c-shared -trimpath -ldflags="-w -s -extldflags=-Wl,-z,max-page-size=16384" -o $so .
        if ($LASTEXITCODE -ne 0) { throw "go build failed for $name" }
        $mb = [math]::Round((Get-Item $so).Length / 1MB, 1)
        Write-Host "  -> $so  ($mb MB)" -ForegroundColor Green
    }
} finally {
    Pop-Location
    Remove-Item Env:CGO_ENABLED, Env:GOOS, Env:GOARCH, Env:CC, Env:GOARM -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "built: $($Abi -join ', ')" -ForegroundColor Green
