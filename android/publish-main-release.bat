@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-main-release.ps1" %*
