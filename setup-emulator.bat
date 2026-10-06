@echo off
setlocal
set JAVA_HOME=C:\Users\aditya\Desktop\opencode android app\jdk17-dist\jdk-17.0.20.1+1
set SDK=C:\Users\aditya\AppData\Local\Android\Sdk

REM Crush ships arm64-v8a ONLY, so an x86_64 image cannot run it.
REM Install the arm64 image and boot it under QEMU TCG (slow but functional).
"%SDK%\cmdline-tools\latest\bin\android.exe" --sdk="%SDK%" sdk install "system-images;android-36;google_apis_playstore;arm64-v8a" > emu-setup.log 2>&1
echo IMAGEXIT=%ERRORLEVEL% >> emu-setup.log

echo "avdmanager create..." >> emu-setup.log
"%SDK%\cmdline-tools\latest\bin\avdmanager.bat" create avd -n crush_arm64 -k "system-images;android-36;google_apis_playstore;arm64-v8a" -d pixel_6 --force >> emu-setup.log 2>&1
echo AVDEXIT=%ERRORLEVEL% >> emu-setup.log
endlocal