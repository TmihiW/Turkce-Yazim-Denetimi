@echo off
setlocal enabledelayedexpansion

echo Türkçe Yazım Denetimi Uygulaması (Basit Versiyon)
echo ================================================
echo.

echo Java sürümü kontrol ediliyor...
java -version
if %ERRORLEVEL% NEQ 0 (
    echo HATA: Java bulunamadı!
    echo Lütfen Java 8 veya üzerini yükleyin.
    pause
    exit /b 1
)

echo.
echo Zemberek JAR dosyaları kontrol ediliyor...
if not exist "zemberek" (
    echo HATA: zemberek klasörü bulunamadı!
    echo Lütfen Zemberek JAR dosyalarını zemberek klasörüne koyun.
    pause
    exit /b 1
)

set ZEMBEREK_JARS=
for %%i in (zemberek\*.jar) do (
    set ZEMBEREK_JARS=!ZEMBEREK_JARS!;%%i
)

if exist "zemberek\module-jars" (
    for %%i in (zemberek\module-jars\*.jar) do (
        set ZEMBEREK_JARS=!ZEMBEREK_JARS!;%%i
    )
)

if exist "zemberek\dependencies" (
    for %%i in (zemberek\dependencies\*.jar) do (
        set ZEMBEREK_JARS=!ZEMBEREK_JARS!;%%i
    )
)

if "%ZEMBEREK_JARS%"=="" (
    echo HATA: Zemberek JAR dosyaları bulunamadı!
    echo Lütfen zemberek klasöründe JAR dosyalarının olduğundan emin olun.
    pause
    exit /b 1
)

echo Zemberek JAR dosyaları bulundu:
echo %ZEMBEREK_JARS%

echo.
echo Uygulama derleniyor...
javac -cp ".;%ZEMBEREK_JARS%" TurkishSpellCheckerSimple.java

if %ERRORLEVEL% NEQ 0 (
    echo HATA: Derleme başarısız!
    echo Hata detayları yukarıda görülebilir.
    pause
    exit /b 1
)

echo.
echo Uygulama başlatılıyor...
echo.

java -cp ".;%ZEMBEREK_JARS%" TurkishSpellCheckerSimple

pause 