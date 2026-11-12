# SPDX-License-Identifier: GPL-3.0-or-later
param(
    [Parameter(Mandatory = $true)][string]$ArchivePath,
    [Parameter(Mandatory = $true)][string]$Destination
)
$ErrorActionPreference = 'Stop'
$archive = (Resolve-Path $ArchivePath).Path
$expected = '39178e0a867f9e3eaa6292a4ab1dd3613f2519c5b564341e1e7b29b270736cc4'
if ((Get-FileHash $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
    throw 'Expected original crates.io redb 4.4.0 archive; checksum mismatch.'
}
$destination = [IO.Path]::GetFullPath($Destination)
if (Test-Path $destination) {
    throw 'Destination must not exist; original source/cache will not be overwritten.'
}
if ($destination.Contains("'") -or $destination.Contains("`n") -or $destination.Contains("`r")) {
    throw 'Destination cannot be represented in the evaluation TOML configuration.'
}
[IO.Directory]::CreateDirectory($destination) | Out-Null
tar -xf $archive -C $destination
if ($LASTEXITCODE -ne 0) { throw 'Archive extraction failed.' }
$source = Join-Path $destination 'redb-4.4.0'
$patches = @(
    (Join-Path $PSScriptRoot '..\third_party\redb\android-ofd-locks.patch'),
    (Join-Path $PSScriptRoot '..\third_party\redb\android-armv7-ofd-locks.patch')
)
Push-Location $source
try {
    foreach ($patch in $patches) {
        git apply --check $patch
        if ($LASTEXITCODE -ne 0) { throw "Patch does not match source: $patch" }
        git apply $patch
        if ($LASTEXITCODE -ne 0) { throw "Patch application failed: $patch" }
    }
}
finally {
    Pop-Location
}
$config = Join-Path $destination 'redb-evaluation.toml'
[IO.File]::WriteAllText($config, "[patch.crates-io]`nredb = { path = '$source' }`n")
Write-Output "Evaluation source: $source"
Write-Output "Cargo override: --config $config"
