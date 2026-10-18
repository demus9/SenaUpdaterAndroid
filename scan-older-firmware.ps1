<#
.SYNOPSIS
    Probes Sena's firmware server for older firmware files of the selected headset models.

.DESCRIPTION
    Reads https://firmware.sena.com/senabluetoothmanager/Firmware (the current release per
    model), derives the file name pattern from the current file, and sends HEAD requests for
    older version / patch / build combinations. Nothing is downloaded. Hits are appended to a
    CSV file in the same folder as this script while the scan runs, so aborting keeps results.

    A hit only means that a file exists on the server. It does not mean the file is an
    official release, that it fits your hardware, or that flashing it is safe.

.EXAMPLE
    .\scan-older-firmware.ps1 -Models 30KV2,50S,10C
    .\scan-older-firmware.ps1 -ListModels
    .\scan-older-firmware.ps1 -All -MaxPatch 3 -MaxBuild 3 -DelayMs 250
#>
[CmdletBinding()]
param(
    [string[]]$Models,
    [switch]$All,
    [switch]$ListModels,
    [int]$MaxPatch = 5,
    [int]$MaxBuild = 5,
    [int]$MaxMinorForOlderMajors = 9,
    [int]$DelayMs = 150,
    [string]$OutFile
)

$ErrorActionPreference = 'Stop'
if (-not $OutFile) {
    $scriptDir = $PSScriptRoot
    if (-not $scriptDir -and $MyInvocation.MyCommand.Path) {
        $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    }
    if (-not $scriptDir) { $scriptDir = (Get-Location).Path }
    $OutFile = Join-Path $scriptDir 'firmware-scan-results.csv'
}
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$baseUrl = 'https://firmware.sena.com/senabluetoothmanager/'

function Get-FirmwareIndex {
    $client = New-Object Net.WebClient
    $bytes = $client.DownloadData($baseUrl + 'Firmware')
    $text = [Text.Encoding]::UTF8.GetString($bytes)
    $entries = foreach ($line in ($text -split "`n")) {
        $parts = $line.Trim() -split ':'
        if ($parts.Count -eq 3) {
            [pscustomobject]@{ Model = $parts[0]; Language = 'Universal'; File = $parts[2] }
        } elseif ($parts.Count -eq 4) {
            [pscustomobject]@{ Model = $parts[0]; Language = $parts[2]; File = $parts[3] }
        }
    }
    $entries | Where-Object { $_.File -match '^[A-Za-z0-9_.-]+\.img$' }
}

function Test-FirmwareUrl([string]$fileName) {
    try {
        $response = Invoke-WebRequest -Uri ($baseUrl + $fileName) -Method Head `
            -UseBasicParsing -TimeoutSec 20
        return [pscustomobject]@{ Exists = $true; Bytes = $response.Headers['Content-Length'] }
    } catch {
        $status = $null
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        # This server answers 403 for files that do not exist.
        if ($status -ne 403 -and $status -ne 404) {
            Write-Warning "$fileName -> unexpected result: $status $($_.Exception.Message)"
        }
        return [pscustomobject]@{ Exists = $false; Bytes = $null }
    }
}

Write-Host 'Reading firmware index...'
$index = Get-FirmwareIndex
$modelNames = $index | Select-Object -ExpandProperty Model -Unique

if ($ListModels) {
    $modelNames
    return
}
if (-not $All -and -not $Models) {
    throw 'Select models with -Models A,B,C or scan everything with -All. Use -ListModels to see names.'
}
if ($All) { $Models = $modelNames }

$unknown = $Models | Where-Object { $modelNames -notcontains $_ }
if ($unknown) { throw "Unknown model(s): $($unknown -join ', ')" }

# Build the work list first so the request count can be shown before sending anything.
$work = @()
foreach ($model in $Models) {
    $latestFile = ($index | Where-Object { $_.Model -eq $model } | Select-Object -First 1).File
    $pattern = '^(?<prefix>.+?)(?<sep>[-_])v(?<ver>\d+(?:\.\d+)*)(?<build>-build\d+)?(?<suffix>.*)\.img$'
    if (-not ($latestFile -match $pattern)) {
        Write-Warning "$model : cannot derive a version pattern from '$latestFile'; skipped."
        continue
    }
    $prefix = $Matches['prefix']; $sep = $Matches['sep']; $suffix = $Matches['suffix']
    $verText = $Matches['ver']
    if ($verText -notmatch '\.') { $verText += '.0' }
    $latest = [version]$verText

    for ($major = $latest.Major; $major -le $latest.Major; $major++) {
        $maxMinor = $MaxMinorForOlderMajors
        if ($major -eq $latest.Major) { $maxMinor = $latest.Minor }
        for ($minor = 0; $minor -le $maxMinor; $minor++) {
            $versions = @("$major.$minor")
            for ($patch = 1; $patch -le $MaxPatch; $patch++) { $versions += "$major.$minor.$patch" }
            foreach ($v in $versions) {
                if ([version]$v -ge $latest) { continue }
                $builds = @('')
                for ($b = 0; $b -le $MaxBuild; $b++) { $builds += "-build$b" }
                foreach ($build in $builds) {
                    $work += [pscustomobject]@{
                        Model = $model; Version = $v
                        File = "$prefix${sep}v$v$build$suffix.img"
                    }
                }
            }
        }
    }
}

Write-Host ("{0} request(s) for {1} model(s), about {2} minute(s) at {3} ms delay." -f `
    $work.Count, $Models.Count, [math]::Ceiling($work.Count * ($DelayMs + 150) / 60000), $DelayMs)

if (-not (Test-Path $OutFile)) {
    'Model,Version,File,Url,Bytes,ScannedAt' | Set-Content -Path $OutFile -Encoding UTF8
}

$hits = 0; $done = 0
foreach ($item in $work) {
    $done++
    if ($done % 25 -eq 0) {
        Write-Progress -Activity 'Scanning firmware URLs' -Status "$done / $($work.Count)  hits: $hits" `
            -PercentComplete (100 * $done / $work.Count)
    }
    $result = Test-FirmwareUrl $item.File
    if ($result.Exists) {
        $hits++
        $row = '{0},{1},{2},{3},{4},{5}' -f $item.Model, $item.Version, $item.File,
            ($baseUrl + $item.File), $result.Bytes, (Get-Date -Format 's')
        Add-Content -Path $OutFile -Value $row -Encoding UTF8
        Write-Host ("HIT  {0,-14} {1}  ({2} bytes)" -f $item.Model, $item.File, $result.Bytes)
    }
    Start-Sleep -Milliseconds $DelayMs
}
Write-Progress -Activity 'Scanning firmware URLs' -Completed
Write-Host "Done. $hits file(s) found. Results: $OutFile"
