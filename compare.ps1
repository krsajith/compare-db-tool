# Run from any directory; rule/output paths are relative to this project.
# Preserve arguments before invoking any helpers. Do not hardcode a machine-specific JDK.
$toolArguments = @($args)
$ErrorActionPreference = 'Stop'
# Exit 1 means comparison differences, not a PowerShell execution failure.
$PSNativeCommandUseErrorActionPreference = $false
$exitCode = 2
$locationPushed = $false
try {
    $jar = Join-Path $PSScriptRoot 'target\compare-db-tool.jar'
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
        throw 'JAR missing. Build the tool first: .\mvnw.cmd clean package'
    }
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $javaExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
            throw 'JAVA_HOME does not contain bin\java.exe. Set JAVA_HOME to your installed JDK 21 directory.'
        }
    } else {
        $javaCommand = Get-Command java.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -eq $javaCommand) {
            throw 'Java was not found. Set JAVA_HOME to your installed JDK 21 directory, or add Java to PATH.'
        }
        $javaExecutable = $javaCommand.Source
    }
    Write-Host "compare-db-tool: starting Java ($javaExecutable)"
    Write-Host "Working directory: $PSScriptRoot"
    Write-Host "JAR: $jar"
    Push-Location $PSScriptRoot
    $locationPushed = $true
    & $javaExecutable -jar $jar @toolArguments
    $exitCode = $LASTEXITCODE
    switch ($exitCode) {
        0 { Write-Host 'compare-db-tool finished: SUCCESS (exit 0).' }
        1 { Write-Host 'compare-db-tool finished: DIFFERENCES (exit 1). See the printed output directory.' }
        2 { Write-Host 'compare-db-tool finished: ERROR or PARTIAL (exit 2). Review the messages above.' }
        default { Write-Host "Java exited with code $exitCode. Review the Java/launcher messages above." }
    }
} catch {
    [Console]::Error.WriteLine("compare-db-tool launcher ERROR: " + $_.Exception.Message)
    $exitCode = 2
} finally {
    if ($locationPushed) { Pop-Location }
}
exit $exitCode
