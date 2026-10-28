# Run from any directory; rule/output paths are relative to this project.
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'D:\software\jdk-21.0.7'
$jar = Join-Path $PSScriptRoot 'target\compare-db-tool.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw 'Build the tool first: .\mvnw.cmd clean package'
}
$exitCode = 2
Push-Location $PSScriptRoot
try {
    & "$env:JAVA_HOME\bin\java.exe" -jar $jar @args
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $exitCode
