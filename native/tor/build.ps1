# Builds the pluggable transports as Android executables, one per ABI.
#
#   pwsh -File native/tor/build.ps1 [-Abi arm64-v8a,x86_64,armeabi-v7a]
#
# They are executables, not libraries, and they are named lib*.so anyway. That
# is not a disguise: Android only extracts and marks executable the files under
# lib/<abi>/ that end in .so, so a program that has to be exec'd has to be
# called one. tor launches them itself through ClientTransportPlugin, which is
# the interface they were written for.
#
# This is also why jniLibs.useLegacyPackaging must stay on. With it off, .so
# files are mapped straight out of the APK and never land on the filesystem --
# and a file that was never extracted cannot be executed.

param([string[]]$Abi = @('arm64-v8a', 'x86_64', 'armeabi-v7a'))

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $here 'build'

$Transports = @(
    @{ Name = 'lyrebird';  Source = 'lyrebird';  Package = './cmd/lyrebird' },
    @{ Name = 'snowflake'; Source = 'snowflake'; Package = './client' }
)

foreach ($transport in $Transports) {
    $src = Join-Path $here "third_party/$($transport.Source)"
    if (-not (Test-Path (Join-Path $src 'go.mod'))) {
        throw "$($transport.Source) missing. Run native/tor/setup.ps1 first."
    }
}

# Resolve the SDK the way Gradle does rather than assuming where it lives.
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $props = Join-Path (Split-Path -Parent (Split-Path -Parent $here)) 'local.properties'
    if (Test-Path $props) {
        $match = Select-String -Path $props -Pattern 'sdk\.dir\s*=\s*(.+)' | Select-Object -First 1
        if ($match) { $sdk = $match.Matches[0].Groups[1].Value.Trim().Replace('\:', ':') }
    }
}
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

$ndkRoot = Join-Path $sdk 'ndk'
if (-not (Test-Path $ndkRoot)) {
    throw "Android NDK not found under $sdk. Set ANDROID_HOME, or sdk.dir in local.properties."
}
$ndk = (Get-ChildItem $ndkRoot -Directory | Sort-Object Name -Descending)[0].FullName

$hostTag = if ($IsLinux) { 'linux-x86_64' } elseif ($IsMacOS) { 'darwin-x86_64' } else { 'windows-x86_64' }
$bin = Join-Path $ndk "toolchains/llvm/prebuilt/$hostTag/bin"
if (-not (Test-Path $bin)) { throw "NDK toolchain not found for this host: $bin" }
$ext = if ($IsLinux -or $IsMacOS) { '' } else { '.cmd' }

# minSdk is 26, so the toolchain is pinned there rather than to the NDK default.
$targets = @{
    'arm64-v8a'   = @{ Arch = 'arm64'; Clang = "aarch64-linux-android26-clang$ext";    Arm = $null }
    'x86_64'      = @{ Arch = 'amd64'; Clang = "x86_64-linux-android26-clang$ext";     Arm = $null }
    'armeabi-v7a' = @{ Arch = 'arm';   Clang = "armv7a-linux-androideabi26-clang$ext"; Arm = '7'  }
}

foreach ($name in $Abi) {
    $spec = $targets[$name]
    if (-not $spec) { throw "unknown ABI: $name" }
    $cc = Join-Path $bin $spec.Clang
    if (-not (Test-Path $cc)) { throw "NDK clang not found: $cc" }

    $dir = Join-Path $out $name
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    foreach ($transport in $Transports) {
        $src = Join-Path $here "third_party/$($transport.Source)"
        $so = Join-Path $dir "lib$($transport.Name).so"

        Push-Location $src
        try {
            $env:GOFLAGS = '-mod=mod'
            $env:CGO_ENABLED = '1'
            $env:GOOS = 'android'
            $env:GOARCH = $spec.Arch
            $env:CC = $cc
            if ($spec.Arm) { $env:GOARM = $spec.Arm } else { Remove-Item Env:GOARM -ErrorAction SilentlyContinue }

            Write-Host "building $($transport.Name) for $name ..." -ForegroundColor Cyan
            # -checklinkname=0 because both transports depend on wlynxg/anet,
            # which reaches into net's internals by //go:linkname to enumerate
            # interfaces on Android -- something the platform's own API will not
            # do for an app. Go 1.23 started refusing those pulls by default.
            # Relaxing it is upstream's own documented answer, not a workaround
            # invented here.
            #
            # -buildmode=pie because Android will not exec a non-PIE binary, and
            # has not since API 21.
            go build -buildmode=pie -trimpath -ldflags="-w -s -checklinkname=0" -o $so $transport.Package
            if ($LASTEXITCODE -ne 0) { throw "go build failed for $($transport.Name) on $name" }
        } finally {
            Pop-Location
            Remove-Item Env:GOFLAGS, Env:CGO_ENABLED, Env:GOOS, Env:GOARCH, Env:CC, Env:GOARM -ErrorAction SilentlyContinue
        }

        $mb = [math]::Round((Get-Item $so).Length / 1MB, 1)
        Write-Host "  -> $so  ($mb MB)" -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "built: $($Abi -join ', ')" -ForegroundColor Green
