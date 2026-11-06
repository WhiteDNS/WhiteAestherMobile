param([string[]]$Abi = @('arm64-v8a', 'x86_64', 'armeabi-v7a'))

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) {
    $properties = Join-Path $here '../../local.properties'
    $sdk = ((Select-String -Path $properties -Pattern '^sdk\.dir=(.+)$').Matches.Groups[1].Value).Replace('\:', ':').Replace('\\', '\')
}
$hostTag = if ($IsLinux) { 'linux-x86_64' } elseif ($IsMacOS) { 'darwin-x86_64' } else { 'windows-x86_64' }
$suffix = if ($IsWindows) { '.cmd' } else { '' }
$bin = Join-Path $sdk "ndk/29.0.14206865/toolchains/llvm/prebuilt/$hostTag/bin"
$targets = @{
    'arm64-v8a' = @('arm64', 'aarch64-linux-android26-clang')
    'x86_64' = @('amd64', 'x86_64-linux-android26-clang')
    'armeabi-v7a' = @('arm', 'armv7a-linux-androideabi26-clang')
}
$previous = @{}
foreach ($name in @('GOOS', 'GOARCH', 'GOARM', 'CGO_ENABLED', 'CC', 'GOTOOLCHAIN')) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name)
}
Push-Location $here
try {
    $env:GOTOOLCHAIN = 'auto'
    $module = go mod download -json github.com/xtls/xray-core@v1.260327.1-0.20261031132019-a4a3dff5b20c | Out-String | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0 -or -not $module.Dir) { throw 'Could not resolve the pinned Xray source' }
    $stage = Join-Path $here 'build/source'
    New-Item -ItemType Directory -Path (Split-Path -Parent $stage) -Force | Out-Null
    if (-not (Test-Path -LiteralPath (Join-Path $stage 'go.mod'))) {
        Copy-Item -LiteralPath $module.Dir -Destination $stage -Recurse
    }
    $relative = 'transport/internet/browser_dialer/dialer.go'
    $text = [IO.File]::ReadAllText((Join-Path $module.Dir $relative))
    $origin = "CheckOrigin: func(r *http.Request) bool {`n`t`treturn true"
    $cors = 'w.Header().Set("Access-Control-Allow-Origin", "*")'
    if (-not $text.Contains($origin) -or -not $text.Contains($cors)) { throw 'Pinned Browser Dialer source changed; review the origin adaptation' }
    # The stock page exposes its token through wildcard CORS; restrict it and the socket to its own origin.
    $text = $text.Replace($origin, "CheckOrigin: func(r *http.Request) bool {`n`t`treturn r.Header.Get(`"Origin`") == `"http://`"+r.Host")
    $text = $text.Replace($cors, 'w.Header().Set("Access-Control-Allow-Origin", "http://"+r.Host)')
    $adapted = Join-Path $stage $relative
    [IO.File]::SetAttributes($adapted, [IO.FileAttributes]::Normal)
    [IO.File]::WriteAllText($adapted, $text, [Text.UTF8Encoding]::new($false))
    $env:GOOS = 'android'
    $env:CGO_ENABLED = '1'
    $env:GOTOOLCHAIN = 'auto'
    foreach ($architecture in $Abi) {
        if (-not $targets.ContainsKey($architecture)) { throw "Unsupported ABI: $architecture" }
        $env:GOARCH = $targets[$architecture][0]
        $env:GOARM = '7'
        $env:CC = Join-Path $bin ($targets[$architecture][1] + $suffix)
        if (-not (Test-Path -LiteralPath $env:CC)) { throw "Missing NDK compiler: $env:CC" }
        $output = Join-Path $here "build/$architecture/libwhiteaesther_xray.so"
        New-Item -ItemType Directory -Path (Split-Path -Parent $output) -Force | Out-Null
        # An executable avoids a second Go runtime/JNI library in the VPN process.
        go build -mod=readonly -p=2 -trimpath -buildmode=pie -ldflags '-s -w -buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384' -o $output github.com/xtls/xray-core/main
        if ($LASTEXITCODE -ne 0) { throw "Xray build failed for $architecture" }
        Write-Host "Built $architecture Xray executable"
    }
} finally {
    Pop-Location
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name]) }
}
