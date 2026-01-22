$ErrorActionPreference = 'Stop'

param(
  [int]$Port = 8888,
  [switch]$Build
)

if ($Build -or !(Test-Path ".\\target\\VinylMatch.jar")) {
  & "$PSScriptRoot\\build.ps1"
}

$env:PORT = "$Port"

Write-Host "[VinylMatch] Starting on http://127.0.0.1:$Port/ (PORT=$Port)"
Write-Host "[VinylMatch] (Also available on http://localhost:$Port/ )"
Write-Host "[VinylMatch] Spotify OAuth does not allow 'localhost' redirect URIs - use 127.0.0.1 in the browser for login."
Write-Host "[VinylMatch] If Spotify login fails, ensure the Redirect URI matches:"
Write-Host "           http://127.0.0.1:$Port/api/auth/callback"
Write-Host "           http://[::1]:$Port/api/auth/callback"
Write-Host "[VinylMatch] (Use the same hostname as in your browser address bar.)"

& java -jar ".\\target\\VinylMatch.jar"

