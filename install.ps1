# Installs the native jpm binary (and jvm.exe, a copy of it) into %USERPROFILE%\.jpm\bin.
#
#   irm https://github.com/java-package-manager/jpm/releases/latest/download/install.ps1 | iex
#
# $env:JPM_VERSION picks a version ('canary' for the newest prerelease or release), $env:JPM_HOME changes the install root, and $env:JPM_INSTALL_BASE
# replaces the download URL. The install folder is added to your user PATH (once); set
# $env:JPM_NO_MODIFY_PATH to skip that.
$ErrorActionPreference = 'Stop'

$repo = if ($env:JPM_REPO) { $env:JPM_REPO } else { 'java-package-manager/jpm' }
if ($env:JPM_INSTALL_BASE) { $base = $env:JPM_INSTALL_BASE }
elseif ($env:JPM_VERSION -eq 'canary') {
  # the release tagged canary names the newest release or prerelease
  $canary = (Invoke-RestMethod "https://github.com/$repo/releases/download/canary/VERSION").ToString().Trim()
  $base = "https://github.com/$repo/releases/download/$canary"
}
elseif ($env:JPM_VERSION) { $base = "https://github.com/$repo/releases/download/$($env:JPM_VERSION)" }
else { $base = "https://github.com/$repo/releases/latest/download" }

$jpmHome = if ($env:JPM_HOME) { $env:JPM_HOME } else { Join-Path $env:USERPROFILE '.jpm' }
$dir = Join-Path $jpmHome 'bin'
$asset = 'jpm-windows-x64.exe'
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("jpm-" + [guid]::NewGuid())
New-Item -ItemType Directory -Force $tmp | Out-Null

try {
    Write-Host "Downloading $asset from $base"
    Invoke-WebRequest "$base/$asset" -OutFile (Join-Path $tmp 'jpm.exe') -UseBasicParsing
    Invoke-WebRequest "$base/SHA256SUMS" -OutFile (Join-Path $tmp 'SHA256SUMS') -UseBasicParsing

    $expected = $null
    foreach ($line in Get-Content (Join-Path $tmp 'SHA256SUMS')) {
        $parts = $line -split '\s+'
        if ($parts.Length -ge 2 -and ($parts[1] -eq $asset -or $parts[1] -eq "*$asset")) { $expected = $parts[0].ToLower() }
    }
    $actual = (Get-FileHash (Join-Path $tmp 'jpm.exe') -Algorithm SHA256).Hash.ToLower()
    if (-not $expected -or $expected -ne $actual) {
        throw "checksum mismatch for $asset (expected $expected, got $actual)"
    }

    New-Item -ItemType Directory -Force $dir | Out-Null
    Move-Item -Force (Join-Path $tmp 'jpm.exe') (Join-Path $dir 'jpm.exe')
    Copy-Item -Force (Join-Path $dir 'jpm.exe') (Join-Path $dir 'jvm.exe')
    $version = & (Join-Path $dir 'jpm.exe') --version
    Write-Host "Installed $version to $dir (with jvm.exe)"
    if (-not $env:JPM_NO_SHIMS) {
        # jpm writes status lines to stderr; Windows PowerShell would turn those into errors under 'Stop'
        $ErrorActionPreference = 'Continue'
        & (Join-Path $dir 'jpm.exe') jvm setup *> $null
        $shimExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        if ($shimExit -eq 0) { Write-Host "Added java, javac, ... shims that follow your project's JDK (see 'jvm current')" }
        else { Write-Warning "could not create the java shims; run 'jvm setup' to see why" }
    }
    $userPath = [Environment]::GetEnvironmentVariable('PATH', 'User')
    $onUserPath = (($userPath -split ';') | Where-Object { $_ -and ($_.TrimEnd('\') -ieq $dir.TrimEnd('\')) }).Count -gt 0
    if ($env:JPM_NO_MODIFY_PATH) {
        if (-not $onUserPath) { Write-Host "PATH not changed (JPM_NO_MODIFY_PATH). Add $dir to your PATH yourself." }
    } elseif (-not $onUserPath) {
        $newPath = if ($userPath) { "$dir;$userPath" } else { $dir }
        [Environment]::SetEnvironmentVariable('PATH', $newPath, 'User')
        Write-Host "Added $dir to your user PATH. Open a new terminal to use jpm everywhere."
    }
    if (-not (($env:PATH -split ';') -contains $dir)) { $env:PATH = "$dir;$env:PATH" }
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
