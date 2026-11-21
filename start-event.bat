@echo off
rem One2Many - event day. Double-click this file.
rem Starts the app (event settings) and the Cloudflare tunnel in a second window.
chcp 65001 >nul
title One2Many
cd /d "%~dp0"

rem the freshly built jar in build\libs first, otherwise one copied next to this file
set "JAR=build\libs\one2many.jar"
if not exist "%JAR%" set "JAR=one2many.jar"
if not exist "%JAR%" (
  echo No encuentro one2many.jar. Primero: gradlew.bat bootJar
  pause
  exit /b 1
)
for %%F in ("%JAR%") do echo Usando %%~fF  (creado %%~tF)

rem the event app has its own port (8090); stop if something already uses it
netstat -ano | findstr /r /c:":8090 .*LISTENING" >nul
if not errorlevel 1 (
  echo.
  echo  El puerto 8090 ya esta ocupado: la aplicacion ya esta abierta en otra ventana?
  echo  Cierrala primero y vuelve a empezar.
  pause
  exit /b 1
)

rem admin password: typed hidden, only lives in this window (never saved anywhere).
rem Rules: at least 12 characters (16+ random is best: login is reachable through the tunnel),
rem and not starting with { (Spring would read it as an encoding prefix).
:askpassword
set "SPRING_SECURITY_USER_PASSWORD="
for /f "usebackq delims=" %%p in (`powershell -NoProfile -Command "$s = Read-Host 'Contrasena de administrador' -AsSecureString; [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))"`) do set "SPRING_SECURITY_USER_PASSWORD=%%p"
powershell -NoProfile -Command "$p = $env:SPRING_SECURITY_USER_PASSWORD; if (-not $p -or $p.Length -lt 12 -or $p.StartsWith('{')) { exit 1 }"
if errorlevel 1 (
  echo  Minimo 12 caracteres, y que no empiece por {  - prueba otra vez.
  goto askpassword
)

echo.
echo  1. En la ventana del tunel aparece una direccion https://....trycloudflare.com
echo  2. Abre ESA direccion (no localhost) en los dos navegadores:
echo     - estacion:  .../station   (los QR personales usan esa direccion)
echo     - panel:     .../dashboard
echo  Para parar: Ctrl+C aqui y en la ventana del tunel.
echo.

start "One2Many - tunel" cmd /k cloudflared tunnel --url http://localhost:8090
java -jar "%JAR%" --spring.profiles.active=event
pause
