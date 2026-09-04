# Refreshes the built-in bridge lines from Tor's own service.
#
#   pwsh -File native/tor/refresh-bridges.ps1
#
# Prints the lines to paste into TorConfig.kt, and says which of them answer
# from this machine. It does not edit the source: what goes in the app is a
# decision, and a script that silently rewrote a constant would make it a
# side effect of running a script.
#
# Why this exists: the list that shipped first was written from memory and two
# of its three bridges had been gone long enough to be unreachable from an
# uncensored network. A list of addresses rots, and there has to be a way to
# tell that it has.

$ErrorActionPreference = 'Stop'

$body = '{"country":""}'
Write-Host "asking Tor for its current built-in bridges..." -ForegroundColor Cyan
$response = Invoke-RestMethod -Uri 'https://bridges.torproject.org/moat/circumvention/builtin' `
    -Method Post -ContentType 'application/vnd.api+json' -Body $body

foreach ($transport in $response.PSObject.Properties) {
    $lines = $transport.Value
    if (-not $lines) { continue }
    Write-Host ""
    Write-Host "$($transport.Name):" -ForegroundColor Green
    foreach ($line in $lines) {
        $address = ($line -split ' ')[1]
        $host_, $port = $address -split ':', 2
        # Snowflake and meek advertise placeholder addresses -- 192.0.2.x is
        # the documentation range -- because the real endpoint is a CDN or a
        # volunteer browser. Reachability means nothing for those.
        $reachable = if ($host_ -like '192.0.2.*') {
            'n/a'
        } else {
            try {
                $client = [System.Net.Sockets.TcpClient]::new()
                $ok = $client.ConnectAsync($host_, [int]$port).Wait(8000)
                $client.Close()
                if ($ok) { 'up' } else { 'DEAD' }
            } catch { 'DEAD' }
        }
        Write-Host ("  [{0,-4}] {1}" -f $reachable, $line)
    }
}

Write-Host ""
Write-Host "Anything marked DEAD should not ship. Built-ins are public and are" -ForegroundColor Yellow
Write-Host "blocked first wherever they matter -- they are a fallback, and the" -ForegroundColor Yellow
Write-Host "answer for a censored network is the in-app fetch or a bridge the" -ForegroundColor Yellow
Write-Host "user was given." -ForegroundColor Yellow
