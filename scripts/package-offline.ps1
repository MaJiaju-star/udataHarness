<#
.SYNOPSIS
Builds UData Harness archives with Java and TypeScript language servers.
.DESCRIPTION
Run frontend build and Maven package first. This script downloads pinned language-server
packages, verifies their checksums, then creates ZIP and tar.gz releases. Java and Node.js
are supplied by the deploy host.
#>
param()

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$targetRoot = Join-Path $projectRoot 'target'
$applicationJar = Join-Path $targetRoot 'udata-harness.jar'
$archiveName = 'jdt-language-server-1.62.0-202610131109.tar.gz'
$archiveUrl = 'https://download.eclipse.org/jdtls/milestones/1.62.0/' + $archiveName
$expectedHash = 'f587dafd8410633c2c2bf005e853a2bbb03cddee88933d49bfe6d9cb1cf09342'
$typeScriptServerName = 'typescript-language-server-6.0.2.tgz'
$typeScriptServerUrl = 'https://registry.npmjs.org/typescript-language-server/-/' + $typeScriptServerName
$typeScriptServerIntegrity = 'sha512-Ph7Ql+xkUJvMamnkh1h+aKP6HxLR7lrWIYkPn9/LZBkJdJZgtIj7OoVhkDYtQ7zFPTPmqZHOVXaUKGyz9M2q1Q=='
$typeScriptName = 'typescript-6.0.3.tgz'
$typeScriptUrl = 'https://registry.npmjs.org/typescript/-/' + $typeScriptName
$typeScriptIntegrity = 'sha512-y2TvuxSZPDyQakkFRPZHKFm+KKVqIisdg9/CZwm9ftvKXLP8NRWj38/ODjNbr43SsoXqNuAisEf1GdCxqWcdBw=='
$cacheRoot = Join-Path $targetRoot 'offline-cache'
$download = Join-Path $cacheRoot $archiveName
$serverDownload = Join-Path $cacheRoot $typeScriptServerName
$typeScriptDownload = Join-Path $cacheRoot $typeScriptName
$stage = Join-Path $targetRoot 'offline-stage'
$releaseRoot = Join-Path $targetRoot 'offline'

if (-not (Test-Path -LiteralPath $applicationJar)) {
    throw "Build the application first: $applicationJar"
}

# 1. Download once and verify the pinned Eclipse artifact.
New-Item -ItemType Directory -Path $cacheRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $download)) {
    Invoke-WebRequest -Uri $archiveUrl -OutFile $download -UseBasicParsing
}
$actualHash = (Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actualHash -ne $expectedHash) {
    throw "JDT LS checksum mismatch: $download"
}
foreach ($asset in @(
    @{Uri=$typeScriptServerUrl; Path=$serverDownload; Integrity=$typeScriptServerIntegrity},
    @{Uri=$typeScriptUrl; Path=$typeScriptDownload; Integrity=$typeScriptIntegrity}
)) {
    if (-not (Test-Path -LiteralPath $asset.Path)) {
        Invoke-WebRequest -Uri $asset.Uri -OutFile $asset.Path -UseBasicParsing
    }
    $expected = [System.BitConverter]::ToString(
        [System.Convert]::FromBase64String($asset.Integrity.Substring(7))).Replace('-', '')
    $actual = (Get-FileHash -LiteralPath $asset.Path -Algorithm SHA512).Hash
    if ($actual -ne $expected) {
        throw "npm package checksum mismatch: $($asset.Path)"
    }
}

# 2. Recreate only the build-owned staging directory under target.
$targetFull = [System.IO.Path]::GetFullPath($targetRoot).TrimEnd('\')
$stageFull = [System.IO.Path]::GetFullPath($stage)
if (-not $stageFull.StartsWith($targetFull + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Stage is outside target: $stageFull"
}
if (Test-Path -LiteralPath $stageFull) {
    Remove-Item -LiteralPath $stageFull -Recurse -Force
}
$jdtlsRoot = Join-Path $stageFull 'jdtls'
New-Item -ItemType Directory -Path $jdtlsRoot -Force | Out-Null
tar -xzf $download -C $jdtlsRoot
if ($LASTEXITCODE -ne 0) { throw 'JDT LS extraction failed' }
$serverRoot = Join-Path $stageFull 'typescript-lsp\node_modules\typescript-language-server'
$typeScriptRoot = Join-Path $stageFull 'typescript-lsp\node_modules\typescript'
New-Item -ItemType Directory -Path $serverRoot,$typeScriptRoot -Force | Out-Null
tar -xzf $serverDownload --strip-components=1 -C $serverRoot
if ($LASTEXITCODE -ne 0) { throw 'TypeScript Language Server extraction failed' }
tar -xzf $typeScriptDownload --strip-components=1 -C $typeScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'TypeScript extraction failed' }
Copy-Item -LiteralPath $applicationJar -Destination (Join-Path $stageFull 'udata-harness.jar')
$releaseJar = Join-Path $stageFull 'udata-harness.jar'
# The developer's local model.yml may contain credentials. Replace only the
# release JAR copy's api-key values; leave the source file and local JAR alone.
$sourceModel = Join-Path $projectRoot 'src\main\resources\model.yml'
$modelText = Get-Content -LiteralPath $sourceModel -Raw
$secretPattern = '(?m)^([ \t]*(?:#[ \t]*)?api-key[ \t]*:[ \t]*).*$'
$safeModel = [regex]::Replace($modelText, $secretPattern, {
    param($match)
    $match.Groups[1].Value + '"${AGENT_API_KEY:}"'
})
$overrideRoot = Join-Path $targetRoot 'offline-model-overlay'
$overrideResource = Join-Path $overrideRoot 'BOOT-INF\classes'
New-Item -ItemType Directory -Path $overrideResource -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $overrideResource 'model.yml'), $safeModel,
    [System.Text.UTF8Encoding]::new($false))
Push-Location $overrideRoot
try {
    jar uf $releaseJar 'BOOT-INF/classes/model.yml'
    if ($LASTEXITCODE -ne 0) { throw 'Release model configuration update failed' }
} finally {
    Pop-Location
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'offline\run.bat') -Destination (Join-Path $stageFull 'run.bat')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'offline\run.sh') -Destination (Join-Path $stageFull 'run.sh')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'offline\THIRD-PARTY.md') -Destination (Join-Path $stageFull 'THIRD-PARTY.md')
Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/eclipse-jdtls/eclipse.jdt.ls/main/LICENSE' `
    -OutFile (Join-Path $stageFull 'LICENSE-JDTLS') -UseBasicParsing

# 3. Produce archives that can be copied to an offline Windows or Linux host.
New-Item -ItemType Directory -Path $releaseRoot -Force | Out-Null
$zip = Join-Path $releaseRoot 'udata-harness-lsp-jdtls-1.62.0-tsls-6.0.2.zip'
$tarball = Join-Path $releaseRoot 'udata-harness-lsp-jdtls-1.62.0-tsls-6.0.2.tar.gz'
Compress-Archive -Path (Join-Path $stageFull '*') -DestinationPath $zip -Force
tar -czf $tarball -C $stageFull .
if ($LASTEXITCODE -ne 0) { throw 'Linux archive creation failed' }
Write-Output $zip
Write-Output $tarball
