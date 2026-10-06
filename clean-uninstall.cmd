@echo off
title Quick Pear - Clean Uninstall Utility
echo ==============================================
echo       Quick Pear Clean Uninstall Utility
echo ==============================================
echo.
echo Stopping running Quick Pear instances...
taskkill /F /IM "Quick Pear.exe" >nul 2>&1

echo Removing Windows Explorer context menu entries...
reg delete "HKCU\Software\Classes\*\shell\QuickPear" /f >nul 2>&1
reg delete "HKCU\Software\Classes\Directory\shell\QuickPear" /f >nul 2>&1
reg delete "HKCU\Software\Classes\Directory\Background\shell\QuickPear" /f >nul 2>&1
reg delete "HKCU\Software\Classes\*\shell\Kirim dengan Quick Pear" /f >nul 2>&1
reg delete "HKCU\Software\Classes\Directory\shell\Kirim dengan Quick Pear" /f >nul 2>&1
reg delete "HKCU\Software\Classes\Directory\Background\shell\Kirim dengan Quick Pear" /f >nul 2>&1

echo Removing Startup / Autostart entries...
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "QuickPear" /f >nul 2>&1
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "Quick Pear" /f >nul 2>&1

echo Removing SendTo and Start Menu shortcuts...
del /f /q "%APPDATA%\Microsoft\Windows\SendTo\Quick Pear.lnk" >nul 2>&1
del /f /q "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Quick Pear.lnk" >nul 2>&1
rmdir /s /q "%APPDATA%\Microsoft\Windows\Start Menu\Programs\Quick Pear" >nul 2>&1
del /f /q "%USERPROFILE%\Desktop\Quick Pear.lnk" >nul 2>&1

echo Removing installed application binaries in LocalAppData...
rmdir /s /q "%LOCALAPPDATA%\Quick Pear" >nul 2>&1
rmdir /s /q "%LOCALAPPDATA%\Programs\Quick Pear" >nul 2>&1

echo Removing application data, identity, and trusted devices...
rmdir /s /q "%APPDATA%\QuickPear" >nul 2>&1

echo.
echo [SUCCESS] All Quick Pear registry keys, shortcuts, and data have been completely removed.
pause