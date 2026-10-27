@echo off
powershell.exe -NoProfile -File "%~dp0scripts\Build-And-Upload.ps1" %*
exit /b %errorlevel%
