$ErrorActionPreference = 'Stop'

Write-Host "[VinylMatch] Building (Maven)..."

if (Test-Path ".\\.tools\\maven") {
  # Use locally downloaded Maven if present (keeps onboarding easy on Windows)
  $mvn = Get-ChildItem ".\\.tools\\maven" -Recurse -Filter "mvn.cmd" -ErrorAction SilentlyContinue |
    Select-Object -First 1
  if ($mvn) {
    & $mvn.FullName -DskipTests package
    exit $LASTEXITCODE
  }
}

& mvn -DskipTests package

