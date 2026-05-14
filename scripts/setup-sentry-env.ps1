# setup-sentry-env.ps1
#
# Interactive Sentry credential setup. Run AFTER rotating your auth
# token at https://sentry.io → Settings → Auth Tokens.
#
# What it does:
#   1. Prompts for the new Sentry auth token (input is hidden).
#   2. Prompts for the new DSN (optional — leave empty to keep the
#      existing one in local.properties).
#   3. Writes SENTRY_AUTH_TOKEN + YANCOTV_SENTRY_DSN as user-scope
#      Windows environment variables via the .NET API. No `setx`
#      quoting / `%`-expansion footguns.
#   4. Backs up packages/android/local.properties (timestamped) and
#      removes only the `sentry.auth.token` + `sentry.dsn` lines.
#      Other keys (release.keystore.*, update.endpoint) stay intact.
#
# Why: TruffleHog's 2026-05-14 050016 audit run verified the on-disk
# Sentry token as live and accepted by api.sentry.io (Critical
# finding). Moving the credentials to environment variables makes
# filesystem-walking scanners (TruffleHog, Gitleaks) stop finding
# them. Gradle reads env vars first (packages/android/app/build.gradle.kts,
# commit 6075b10), so once the env vars are set the file lines are
# redundant and can be removed.
#
# Run from the repo root (D:\YancoTV) — or anywhere inside the
# repo; the script resolves the root via `git rev-parse`:
#   .\scripts\setup-sentry-env.ps1

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

# ─── Locate the repo root ───────────────────────────────────────────────
$repoRoot = (& git rev-parse --show-toplevel 2>$null)
if (-not $repoRoot) {
    Write-Error 'Not inside a git repository. Run this from the YancoTV repo root.'
    exit 1
}
$repoRoot = $repoRoot.Trim()
$localProps = Join-Path $repoRoot 'packages/android/local.properties'

Write-Host ''
Write-Host '=== YancoTV Sentry credential migration ==='
Write-Host ''
Write-Host "Repo root:   $repoRoot"
Write-Host "Target file: $localProps"
Write-Host ''
Write-Host 'You should already have rotated your Sentry auth token at:'
Write-Host '  https://sentry.io  →  Settings  →  Auth Tokens'
Write-Host ''
Write-Host 'Have the new token (and optionally the DSN) ready, then continue.'
Write-Host ''

# ─── Prompt for the new values ──────────────────────────────────────────
$tokenSecure = Read-Host 'New SENTRY_AUTH_TOKEN' -AsSecureString
$dsnSecure   = Read-Host 'New YANCOTV_SENTRY_DSN (leave empty to skip)' -AsSecureString

# Convert SecureString -> plain. Both setx and [Environment]::Set... need
# a plain string; this is the same trip the OS makes when Gradle reads
# the var anyway.
function ConvertFrom-SecureToPlain {
    param([Security.SecureString]$Secure)
    if (-not $Secure) { return '' }
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

$token = ConvertFrom-SecureToPlain $tokenSecure
$dsn   = ConvertFrom-SecureToPlain $dsnSecure

if ([string]::IsNullOrWhiteSpace($token)) {
    Write-Error 'Token cannot be empty. Aborting before any changes are written.'
    exit 1
}

# ─── Set user-scope environment variables ───────────────────────────────
# Using the .NET API rather than `setx` so tokens containing `%`, `&`,
# `"`, or other cmd.exe-special chars don't need escaping. User scope
# persists across shells + Android Studio launches; takes effect for
# new processes only.
Write-Host ''
Write-Host 'Writing environment variables (user scope)...'

[Environment]::SetEnvironmentVariable('SENTRY_AUTH_TOKEN', $token, 'User')
Write-Host '  SENTRY_AUTH_TOKEN written'

if (-not [string]::IsNullOrWhiteSpace($dsn)) {
    [Environment]::SetEnvironmentVariable('YANCOTV_SENTRY_DSN', $dsn, 'User')
    Write-Host '  YANCOTV_SENTRY_DSN written'
} else {
    Write-Host '  YANCOTV_SENTRY_DSN skipped (empty input)'
}

# ─── Remove the lines from local.properties ─────────────────────────────
if (Test-Path $localProps) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $backup = "$localProps.bak-$stamp"
    Copy-Item -Path $localProps -Destination $backup -Force
    Write-Host ''
    Write-Host "Backed up local.properties -> $backup"

    # Pattern: line whose key is exactly `sentry.auth.token` or `sentry.dsn`
    # (with optional whitespace before `=`). Other sentry.* keys (none
    # expected, but defensive) are left alone.
    $patterns = @('^\s*sentry\.auth\.token\s*=', '^\s*sentry\.dsn\s*=')
    $original = Get-Content $localProps
    $kept = $original | Where-Object {
        $line = $_
        -not ($patterns | Where-Object { $line -match $_ })
    }
    $removed = $original.Count - $kept.Count
    if ($removed -gt 0) {
        # ASCII keeps local.properties in the Java-Properties canonical
        # encoding; Gradle parses ISO-8859-1 by default but ASCII is a
        # safe subset. No BOM either way.
        Set-Content -Path $localProps -Value $kept -Encoding ASCII
        Write-Host "Removed $removed Sentry-credential line(s) from local.properties"
    } else {
        Write-Host 'No sentry.auth.token / sentry.dsn lines found in local.properties (nothing to remove).'
    }
} else {
    Write-Host ''
    Write-Host "$localProps not found - skipped line removal (env vars are still set)."
}

# ─── Done ───────────────────────────────────────────────────────────────
Write-Host ''
Write-Host '=== Done ==='
Write-Host ''
Write-Host 'IMPORTANT next steps:'
Write-Host ''
Write-Host '  1. Close THIS PowerShell window AND Android Studio.'
Write-Host '     (Env vars set via setx / Set-EnvironmentVariable are not'
Write-Host '      visible to processes that were already running.)'
Write-Host ''
Write-Host '  2. Open a fresh shell and verify the env vars are visible:'
Write-Host ''
Write-Host '       $env:SENTRY_AUTH_TOKEN.Length     # should print a non-zero number'
Write-Host '       $env:YANCOTV_SENTRY_DSN.Length    # if you set the DSN too'
Write-Host ''
Write-Host '  3. Cold-restart the Gradle daemon (it caches env at startup):'
Write-Host ''
Write-Host '       cd packages\android'
Write-Host '       .\gradlew --stop'
Write-Host ''
Write-Host '  4. Re-run the audit (yancoxplorer) and confirm the Sentry'
Write-Host '     findings on packages/android/local.properties are gone.'
Write-Host ''
Write-Host 'If anything goes wrong, the timestamped backup in step 1 above'
Write-Host 'has your original local.properties content (move it back over'
Write-Host 'local.properties to roll back the file edit).'
Write-Host ''
