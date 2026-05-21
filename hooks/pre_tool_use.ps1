param(
    [string]$ExampleArg = ""
)

$ErrorActionPreference = "Stop"

Write-Output "PreToolUse hook"
Write-Output "event=$env:HARNESS_HOOK_EVENT"
Write-Output "detail=$env:HARNESS_HOOK_DETAIL"
if ($ExampleArg -ne "") {
    Write-Output "arg=$ExampleArg"
}
