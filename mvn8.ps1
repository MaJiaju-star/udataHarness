# mvn8.ps1 — 用 JDK 8 运行 Maven，避免本机默认 JDK 25 干扰
# 用法:
#   .\mvn8.ps1 clean compile
#   .\mvn8.ps1 test
#   .\mvn8.ps1 test -Dtest=SomeClass
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$MvnArgs
)

$ErrorActionPreference = 'Stop'

$jdk8 = 'D:\OtherJDK\dragonwell-8.30.29'
if (-not (Test-Path (Join-Path $jdk8 'bin\javac.exe'))) {
    Write-Error "未找到 JDK 8: $jdk8"
    exit 1
}

$env:JAVA_HOME = $jdk8
$env:Path = "$jdk8\bin;" + $env:Path

Write-Host "JAVA_HOME = $env:JAVA_HOME" -ForegroundColor Cyan
java -version

if (-not $MvnArgs -or $MvnArgs.Count -eq 0) {
    Write-Host "未提供 Maven 参数，示例: .\mvn8.ps1 clean compile" -ForegroundColor Yellow
    exit 0
}

mvn @MvnArgs
