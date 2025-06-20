#!/bin/bash

echo "Türkçe Yazım Denetimi Uygulaması (Basit Versiyon)"
echo "================================================"
echo

echo "Java sürümü kontrol ediliyor..."
if ! java -version 2>&1; then
    echo "HATA: Java bulunamadı!"
    echo "Lütfen Java 8 veya üzerini yükleyin."
    read -p "Devam etmek için Enter tuşuna basın..."
    exit 1
fi

echo
echo "Zemberek JAR dosyaları kontrol ediliyor..."
if [ ! -d "zemberek" ]; then
    echo "HATA: zemberek klasörü bulunamadı!"
    echo "Lütfen Zemberek JAR dosyalarını zemberek klasörüne koyun."
    read -p "Devam etmek için Enter tuşuna basın..."
    exit 1
fi

ZEMBEREK_JARS=""

# Main zemberek directory JAR files
for jar in zemberek/*.jar; do
    if [ -f "$jar" ]; then
        if [ -z "$ZEMBEREK_JARS" ]; then
            ZEMBEREK_JARS="$jar"
        else
            ZEMBEREK_JARS="$ZEMBEREK_JARS:$jar"
        fi
    fi
done

# Module JARs if they exist
if [ -d "zemberek/module-jars" ]; then
    for jar in zemberek/module-jars/*.jar; do
        if [ -f "$jar" ]; then
            if [ -z "$ZEMBEREK_JARS" ]; then
                ZEMBEREK_JARS="$jar"
            else
                ZEMBEREK_JARS="$ZEMBEREK_JARS:$jar"
            fi
        fi
    done
fi

# Dependencies if they exist
if [ -d "zemberek/dependencies" ]; then
    for jar in zemberek/dependencies/*.jar; do
        if [ -f "$jar" ]; then
            if [ -z "$ZEMBEREK_JARS" ]; then
                ZEMBEREK_JARS="$jar"
            else
                ZEMBEREK_JARS="$ZEMBEREK_JARS:$jar"
            fi
        fi
    done
fi

if [ -z "$ZEMBEREK_JARS" ]; then
    echo "HATA: Zemberek JAR dosyaları bulunamadı!"
    echo "Lütfen zemberek klasöründe JAR dosyalarının olduğundan emin olun."
    read -p "Devam etmek için Enter tuşuna basın..."
    exit 1
fi

echo "Zemberek JAR dosyaları bulundu:"
echo "$ZEMBEREK_JARS"

echo
echo "Uygulama derleniyor..."
if ! javac -cp ".:$ZEMBEREK_JARS" TurkishSpellCheckerSimple.java; then
    echo "HATA: Derleme başarısız!"
    echo "Hata detayları yukarıda görülebilir."
    read -p "Devam etmek için Enter tuşuna basın..."
    exit 1
fi

echo
echo "Uygulama başlatılıyor..."
echo

java -cp ".:$ZEMBEREK_JARS" TurkishSpellCheckerSimple

read -p "Devam etmek için Enter tuşuna basın..." 