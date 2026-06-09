param(
    [string]$Version = "latest",
    [string]$OutFile = "app/src/main/assets/loom-linux-arm64.tgz",
    [string]$WorkDir = "build/loom-addon"
)

$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }

    return [System.IO.Path]::GetFullPath((Join-Path $repoRoot $Path))
}

function Assert-UnderPath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Parent,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $fullPath = [System.IO.Path]::GetFullPath($Path).TrimEnd('\', '/')
    $fullParent = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\', '/')
    if ($fullPath -ne $fullParent -and -not $fullPath.StartsWith($fullParent + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "$Description must be under $fullParent, got $fullPath"
    }
}

function Get-LoomAssetUrl {
    param(
        [Parameter(Mandatory = $true)][string]$VersionValue,
        [Parameter(Mandatory = $true)][string]$AssetName
    )

    $escapedAsset = [System.Uri]::EscapeDataString($AssetName)
    if ($VersionValue -eq "latest") {
        return "https://github.com/agentserver/loom/releases/latest/download/$escapedAsset"
    }

    $escapedVersion = [System.Uri]::EscapeDataString($VersionValue)
    return "https://github.com/agentserver/loom/releases/download/$escapedVersion/$escapedAsset"
}

function Invoke-Download {
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [Parameter(Mandatory = $true)][string]$Destination,
        [bool]$Required = $true
    )

    $partFile = "$Destination.part"
    if (Test-Path -LiteralPath $partFile) {
        Remove-Item -LiteralPath $partFile -Force
    }
    if (Test-Path -LiteralPath $Destination) {
        Remove-Item -LiteralPath $Destination -Force
    }

    try {
        Invoke-WebRequest -Uri $Url -OutFile $partFile -Headers @{ "User-Agent" = "Codex" }
        if (-not (Test-Path -LiteralPath $partFile) -or (Get-Item -LiteralPath $partFile).Length -le 0) {
            throw "Downloaded asset is empty: $Url"
        }
        Move-Item -LiteralPath $partFile -Destination $Destination -Force
        return $true
    } catch {
        if (Test-Path -LiteralPath $partFile) {
            Remove-Item -LiteralPath $partFile -Force
        }
        if ($Required) {
            throw
        }
        Write-Host "Skipping optional asset: $Url"
        return $false
    }
}

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][string]$Path)

    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
}

function Read-Sha256Sums {
    param([Parameter(Mandatory = $true)][string]$Path)

    $map = @{}
    if (-not (Test-Path -LiteralPath $Path)) {
        return $map
    }

    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed -eq "" -or $trimmed.StartsWith("#")) {
            continue
        }
        $parts = $trimmed -split "\s+", 2
        if ($parts.Count -lt 2) {
            continue
        }
        $hash = $parts[0].ToLowerInvariant()
        $name = $parts[1].TrimStart("*")
        $map[$name] = $hash
    }
    return $map
}

function Assert-ExpectedSha256 {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Checksums,
        [Parameter(Mandatory = $true)][string]$AssetName,
        [Parameter(Mandatory = $true)][string]$Path
    )

    if (-not $Checksums.ContainsKey($AssetName)) {
        Write-Host "No sha256 entry for $AssetName; skipping checksum verification"
        return
    }

    $expected = $Checksums[$AssetName]
    $actual = Get-Sha256Hex -Path $Path
    if ($actual -ne $expected) {
        throw "Checksum mismatch for $AssetName. Expected $expected, got $actual"
    }
    Write-Host "Verified $AssetName sha256"
}

function Assert-SafeTarEntries {
    param([Parameter(Mandatory = $true)][string]$TarPath)

    $entries = tar -tzf $TarPath
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to list $TarPath"
    }
    foreach ($entry in $entries) {
        $normalized = $entry.Replace("\", "/")
        if ($normalized.StartsWith("/") -or $normalized -match "^[A-Za-z]:/" -or
                $normalized -eq ".." -or $normalized.StartsWith("../") -or
                $normalized.Contains("/../")) {
            throw "Unsafe tar entry in $TarPath`: $entry"
        }
    }
}

function Copy-DownloadedAsset {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$buildRoot = Join-Path $repoRoot "build"
$assetsRoot = Join-Path $repoRoot "app/src/main/assets"
$resolvedWorkDir = Resolve-RepoPath $WorkDir
$resolvedOutFile = Resolve-RepoPath $OutFile

Assert-UnderPath -Path $resolvedWorkDir -Parent $buildRoot -Description "WorkDir"
Assert-UnderPath -Path $resolvedOutFile -Parent $assetsRoot -Description "OutFile"

if (Test-Path -LiteralPath $resolvedWorkDir) {
    Remove-Item -LiteralPath $resolvedWorkDir -Recurse -Force
}

$downloadDir = Join-Path $resolvedWorkDir "downloads"
$loomRoot = Join-Path $resolvedWorkDir "loom"
$binDir = Join-Path $loomRoot "bin"
$skillsDir = Join-Path $loomRoot "skills"
$promptsDir = Join-Path $loomRoot "prompts-codex"
$deployDir = Join-Path $loomRoot "deploy"

New-Item -ItemType Directory -Path $resolvedWorkDir -Force | Out-Null
New-Item -ItemType Directory -Path $downloadDir -Force | Out-Null
New-Item -ItemType Directory -Path $loomRoot -Force | Out-Null
New-Item -ItemType Directory -Path $binDir -Force | Out-Null

$requiredAssets = @(
    @{ Asset = "observer-server.linux-arm64"; Target = "observer-server" },
    @{ Asset = "driver-agent.linux-arm64"; Target = "driver-agent" },
    @{ Asset = "slave-agent.linux-arm64"; Target = "slave-agent" }
)

$optionalEntries = New-Object System.Collections.Generic.List[string]

$shaAsset = "sha256sums.txt"
$shaPath = Join-Path $downloadDir $shaAsset
if (Invoke-Download -Url (Get-LoomAssetUrl -VersionValue $Version -AssetName $shaAsset) -Destination $shaPath -Required $false) {
    Copy-DownloadedAsset -Source $shaPath -Destination (Join-Path $loomRoot "sha256sums.txt")
    $optionalEntries.Add("sha256sums.txt")
}
$sha256Sums = Read-Sha256Sums -Path $shaPath

foreach ($item in $requiredAssets) {
    $downloadPath = Join-Path $downloadDir $item.Asset
    $url = Get-LoomAssetUrl -VersionValue $Version -AssetName $item.Asset
    Write-Host "Downloading required asset $($item.Asset)"
    Invoke-Download -Url $url -Destination $downloadPath -Required $true | Out-Null
    Assert-ExpectedSha256 -Checksums $sha256Sums -AssetName $item.Asset -Path $downloadPath
    Copy-DownloadedAsset -Source $downloadPath -Destination (Join-Path $binDir $item.Target)
}

$optionalBinaryAsset = "mcp-userspace.linux-arm64"
$optionalBinaryPath = Join-Path $downloadDir $optionalBinaryAsset
if (Invoke-Download -Url (Get-LoomAssetUrl -VersionValue $Version -AssetName $optionalBinaryAsset) -Destination $optionalBinaryPath -Required $false) {
    Copy-DownloadedAsset -Source $optionalBinaryPath -Destination (Join-Path $binDir "mcp-userspace")
    $optionalEntries.Add("bin/mcp-userspace")
}

$optionalBundles = @(
    @{ Asset = "driver-skills.tar.gz"; TargetDir = $skillsDir; ManifestPath = "skills" },
    @{ Asset = "driver-codex-prompts.tar.gz"; TargetDir = $promptsDir; ManifestPath = "prompts-codex" }
)

foreach ($bundle in $optionalBundles) {
    $downloadPath = Join-Path $downloadDir $bundle.Asset
    if (Invoke-Download -Url (Get-LoomAssetUrl -VersionValue $Version -AssetName $bundle.Asset) -Destination $downloadPath -Required $false) {
        New-Item -ItemType Directory -Path $bundle.TargetDir -Force | Out-Null
        Assert-SafeTarEntries -TarPath $downloadPath
        tar -xzf $downloadPath -C $bundle.TargetDir
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to extract $($bundle.Asset)"
        }
        $optionalEntries.Add($bundle.ManifestPath)
    }
}

$bootstrapAssets = @("bootstrap-driver.sh", "bootstrap-observer.sh", "bootstrap-slave.sh")
foreach ($bootstrapAsset in $bootstrapAssets) {
    $downloadPath = Join-Path $downloadDir $bootstrapAsset
    if (Invoke-Download -Url (Get-LoomAssetUrl -VersionValue $Version -AssetName $bootstrapAsset) -Destination $downloadPath -Required $false) {
        New-Item -ItemType Directory -Path $deployDir -Force | Out-Null
        Copy-DownloadedAsset -Source $downloadPath -Destination (Join-Path $deployDir $bootstrapAsset)
        $optionalEntries.Add("deploy/$bootstrapAsset")
    }
}

$manifest = [ordered]@{
    name = "loom"
    version = $Version
    source = "https://github.com/agentserver/loom"
    arch = "linux-arm64"
    required = @(
        "bin/observer-server",
        "bin/driver-agent",
        "bin/slave-agent"
    )
    optional = @($optionalEntries.ToArray())
}

$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $loomRoot "manifest.json") -Encoding UTF8

$outDir = Split-Path -Parent $resolvedOutFile
New-Item -ItemType Directory -Path $outDir -Force | Out-Null
$partOut = "$resolvedOutFile.part"
if (Test-Path -LiteralPath $partOut) {
    Remove-Item -LiteralPath $partOut -Force
}
if (Test-Path -LiteralPath $resolvedOutFile) {
    Remove-Item -LiteralPath $resolvedOutFile -Force
}

tar -czf $partOut -C $resolvedWorkDir loom
if ($LASTEXITCODE -ne 0) {
    throw "Failed to create $resolvedOutFile"
}

Move-Item -LiteralPath $partOut -Destination $resolvedOutFile -Force
Write-Host "Created $resolvedOutFile"
