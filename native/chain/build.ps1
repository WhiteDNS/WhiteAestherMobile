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
$bin = Join-Path $ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin'

# minSdk is 26, so the toolchain is pinned there rather than to the NDK default.
$targets = @{
    'arm64-v8a'   = @{ Arch = 'arm64'; Clang = 'aarch64-linux-android26-clang.cmd';    Arm = $null }
    'x86_64'      = @{ Arch = 'amd64'; Clang = 'x86_64-linux-android26-clang.cmd';     Arm = $null }
    'armeabi-v7a' = @{ Arch = 'arm';   Clang = 'armv7a-linux-androideabi26-clang.cmd'; Arm = '7'  }
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
