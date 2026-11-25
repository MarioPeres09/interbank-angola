@echo off
setlocal
node "%~dp0scripts\arrancar-local.cjs" %*
exit /b %errorlevel%
