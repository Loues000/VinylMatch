$ErrorActionPreference = 'Stop'

if (!(Test-Path ".\\config\\env.example")) {
  throw "Missing config/env.example (template)."
}

if (Test-Path ".\\.env") {
  Write-Host "[VinylMatch] .env already exists - leaving it untouched."
  exit 0
}

Copy-Item -Force ".\\config\\env.example" ".\\.env"
Write-Host "[VinylMatch] Created .env from config/env.example"
Write-Host "[VinylMatch] Next: edit .env and fill in SPOTIFY_CLIENT_ID / SPOTIFY_CLIENT_SECRET"

