@echo off
setlocal
cd /d "%~dp0frontend\angular-app" || exit /b 1
if not exist node_modules\.bin\ng.cmd (
  call npm.cmd ci || exit /b 1
)
call npm.cmd run build || exit /b 1
node "%~dp0scripts\controlo-local.cjs" --abrir
exit /b %errorlevel%
