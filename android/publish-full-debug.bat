@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-full-debug.ps1" %*
