$ErrorActionPreference = 'Stop'

param(
  [int]$Port = 8888,
  [switch]$Build
)

if ($Build -or !(Test-Path ".\\target\\VinylMatch.jar")) {
  & "$PSScriptRoot\\build.ps1"
}

$env:PORT = "$Port"

Write-Host "[VinylMatch] Starting on http://localhost:$Port/ (PORT=$Port)"
Write-Host "[VinylMatch] If Spotify login fails, ensure the Redirect URI matches:"
Write-Host "           http://127.0.0.1:$Port/api/auth/callback"

& java -jar ".\\target\\VinylMatch.jar"

