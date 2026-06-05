# run_coverage.ps1
# Usage (from repo root): .\setup\run_coverage.ps1

$root = Split-Path -Parent $PSScriptRoot

# Go image per service — must match the go.mod version
$serviceImages = [ordered]@{
    "banking-core-service-go"  = "golang:1.25-alpine"
    "credit-service-go"        = "golang:1.26-alpine"
    "interbank-service"        = "golang:1.25-alpine"
    "market-service-go"        = "golang:1.25-alpine"
    "notification-service-go"  = "golang:1.26-alpine"
    "saga-orchestrator-service"= "golang:1.25-alpine"
    "trading-service-go"       = "golang:1.25-alpine"
    "user-service-go"          = "golang:1.25-alpine"
}

$services = $serviceImages.Keys

function Parse-Coverage($pct) {
    if ($pct -match "([\d\.]+)%") {
        return [float]$matches[1]
    }
    return -1
}

function Coverage-Color($val) {
    if ($val -lt 0)  { return "DarkGray" }
    if ($val -lt 40) { return "Red" }
    if ($val -lt 70) { return "Yellow" }
    return "Green"
}

$results = @()
$total = $services.Count
$i = 0

Write-Host ""
Write-Host "  GO TEST COVERAGE" -ForegroundColor White
Write-Host "  $('=' * 50)" -ForegroundColor DarkGray
Write-Host ""

foreach ($svc in $services) {
    $i++
    Write-Host "  [$i/$total] " -NoNewline -ForegroundColor DarkGray
    Write-Host "$svc" -NoNewline -ForegroundColor Cyan
    Write-Host " ... " -NoNewline -ForegroundColor DarkGray

    $image = $serviceImages[$svc]
    $raw = docker run --rm `
        -v "${root}:/workspace" `
        -e GOWORK=off `
        -w "/workspace/$svc" `
        $image `
        sh -c "go test ./... -coverprofile=/tmp/cov.out 2>&1; echo '---COVER---'; go tool cover -func=/tmp/cov.out 2>/dev/null | tail -1" 2>&1

    $coverLine = ($raw | Where-Object { $_ -match "total:" }) -join ""
    $pct = Parse-Coverage $coverLine
    $pctStr = if ($pct -ge 0) { "$pct%" } else { "n/a" }

    $failed = $raw | Where-Object { $_ -match "^(FAIL|---\s*FAIL)" }
    $hasError = ($raw | Where-Object { $_ -match "^(FAIL|build failed|cannot)" }).Count -gt 0

    if ($hasError -and $pct -lt 0) {
        Write-Host "ERROR" -ForegroundColor Red
    } elseif ($failed.Count -gt 0) {
        Write-Host "FAIL  " -NoNewline -ForegroundColor Red
        Write-Host "($pctStr coverage)" -ForegroundColor DarkGray
    } else {
        Write-Host "OK    " -NoNewline -ForegroundColor Green
        Write-Host "($pctStr coverage)" -ForegroundColor (Coverage-Color $pct)
    }

    if ($failed.Count -gt 0) {
        foreach ($line in $failed) {
            Write-Host "         $line" -ForegroundColor Red
        }
    }

    $results += [PSCustomObject]@{
        Service  = $svc
        Pct      = $pct
        PctStr   = $pctStr
        Status   = if ($hasError -and $pct -lt 0) { "ERROR" } elseif ($failed.Count -gt 0) { "FAIL" } else { "OK" }
        Failed   = $failed.Count
    }
}

Write-Host ""
Write-Host "  $('=' * 50)" -ForegroundColor DarkGray
Write-Host "  SUMMARY" -ForegroundColor White
Write-Host "  $('=' * 50)" -ForegroundColor DarkGray
Write-Host ""
Write-Host ("  {0,-35} {1,8}   {2}" -f "Service", "Coverage", "Status") -ForegroundColor DarkGray
Write-Host "  $('-' * 55)" -ForegroundColor DarkGray

foreach ($r in $results) {
    $statusColor = switch ($r.Status) {
        "OK"    { "Green" }
        "FAIL"  { "Red" }
        default { "DarkGray" }
    }
    $pctColor = Coverage-Color $r.Pct

    Write-Host ("  {0,-35} " -f $r.Service) -NoNewline -ForegroundColor White
    Write-Host ("{0,8}   " -f $r.PctStr) -NoNewline -ForegroundColor $pctColor
    Write-Host $r.Status -ForegroundColor $statusColor
}

Write-Host ""
$okCount   = ($results | Where-Object { $_.Status -eq "OK" }).Count
$failCount = ($results | Where-Object { $_.Status -ne "OK" }).Count
Write-Host "  Passed: $okCount   Failed/Error: $failCount" -ForegroundColor White
Write-Host ""
