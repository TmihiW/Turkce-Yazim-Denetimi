# Türkçe Yazım Denetimi Uygulaması (Gelişmiş Versiyon)

Bu uygulama, seçilen metin dosyalarındaki yazım hatalarını Zemberek NLP kütüphanesi kullanarak tespit etmenizi ve interaktif bir şekilde düzeltmenizi sağlar.

![Screenshot_19](https://github.com/user-attachments/assets/2f83437c-1449-472b-a812-cc907914ddc5)

## Gereksinimler

Uygulamayı çalıştırabilmek için bilgisayarınızda **Java Development Kit (JDK)** yüklü olmalıdır.

## Kurulum

1.  **Java Development Kit (JDK) Kurulumu:**
    *   Eğer bilgisayarınızda Java yüklü değilse, aşağıdaki bağlantıdan **Temurin 17 (LTS)** sürümünü indirin:
        *   [**JDK 17 İndirme Linki (Windows x64)**](https://adoptium.net/temurin/releases/?version=17)
    *   İndirdiğiniz `.msi` uzantılı dosyayı çalıştırın ve kurulum sihirbazındaki adımları takip ederek kurulumu tamamlayın.

## Çalıştırma

Java kurulumunu tamamladıktan sonra, proje klasörü içerisindeki `run_simple.bat` dosyasına çift tıklamanız yeterlidir. Uygulama otomatik olarak derlenecek ve başlayacaktır.

## Özellikler

- **Yazım Denetimi**: Metin dosyalarında yazım hatalarını tespit eder
- **Otomatik Öneriler**: Yanlış yazılan kelimeler için doğru alternatifler önerir
- **Etkileşimli Düzeltme**: Her hata için kullanıcıya düzeltme seçeneği sunar
- **Otomatik Kaydetme**: 
  - Taranan belgeleri "Tarananlar" klasörüne kaydeder
  - Yapılan düzeltmeleri "Düzeltmeler" klasörüne kaydeder
- **İlerleme Takibi**: Gerçek zamanlı ilerleme çubuğu
- **Durdurma Özelliği**: İşlemi istediğiniz zaman durdurabilirsiniz

## Kullanım

### 1. Uygulamayı Başlatma
- Uygulama başlatıldığında Zemberek kütüphanesi otomatik olarak yüklenir
- "Zemberek yüklendi - Hazır" mesajını gördüğünüzde kullanıma başlayabilirsiniz

### 2. Metin Girişi
İki seçeneğiniz var:
- **Dosya Seç**: Metin dosyası (.txt) seçerek içeriği yükleyin
- **Manuel Giriş**: Sol paneldeki metin alanına doğrudan metin yazın

### 3. Yazım Denetimi Başlatma
- "Yazım Denetimi Başlat" butonuna tıklayın
- Uygulama metni kelime kelime tarayacak

### 4. Hata Düzeltme
Her yazım hatası bulunduğunda:
- Hata detayları gösterilir (satır numarası, yanlış kelime, önerilen düzeltme)
- "Evet" seçeneği ile düzeltmeyi kabul edin
- "Hayır" seçeneği ile kelimeyi olduğu gibi bırakın

### 5. Sonuçları Görme
- Düzeltilmiş metin sağ panelde görüntülenir
- İşlem tamamlandığında dosyalar otomatik olarak kaydedilir

## Dosya Yapısı

Uygulama çalıştırıldığında aşağıdaki klasörler otomatik olarak oluşturulur:

```
Proje Klasörü/
├── Tarananlar/           # Taranan belgeler buraya kaydedilir
│   └── YYYYMMDD_HHMMSS_original.txt
├── Düzeltmeler/          # Yapılan düzeltmeler buraya kaydedilir
│   └── düzeltmeler_YYYYMMDD_HHMMSS.txt
├── TurkishSpellCheckerApp.java
├── pom.xml
└── README.md
```

### Tarananlar Klasörü
- Orijinal dosya adı + timestamp ile kaydedilir
- Örnek: `20231201_143022_metin.txt`

### Düzeltmeler Klasörü
- Her düzeltme için ayrı satır
- Format: `Dosya: dosya_adi.txt, Satır: 5, Yanlış: yanlış_kelime, Düzeltme: doğru_kelime`

## Örnek Kullanım Senaryosu

1. **Dosya Seç**: "örnek_metin.txt" dosyasını seçin
2. **Başlat**: "Yazım Denetimi Başlat" butonuna tıklayın
3. **Düzeltme**: 
   - "Ankar'ada" → "Ankara'da" (Evet)
   - "okuyablirim" → "okuyabilirim" (Evet)
   - "tartısıyor" → "tartışıyor" (Evet)
4. **Sonuç**: Düzeltilmiş metin sağ panelde görünür
5. **Kaydetme**: Dosyalar otomatik olarak ilgili klasörlere kaydedilir

## Teknik Detaylar

### Kullanılan Kütüphaneler
- **Zemberek NLP**: Türkçe doğal dil işleme
- **Swing**: Kullanıcı arayüzü
- **Maven**: Proje yönetimi ve bağımlılık yönetimi

### Ana Sınıflar
- `TurkishSpellCheckerApp`: Ana uygulama sınıfı
- `TurkishMorphology`: Morfolojik analiz
- `TurkishSpellChecker`: Yazım denetimi
- `TurkishTokenizer`: Metin tokenizasyonu

### Özellikler
- **Çok İş Parçacıklı**: UI bloklanmaz, arka planda işlem yapar
- **Bellek Verimli**: Büyük dosyalar için optimize edilmiş
- **Hata Toleranslı**: Beklenmeyen durumlarda güvenli şekilde durur

## Sorun Giderme

### Yaygın Sorunlar

1. **Zemberek Yüklenemiyor**
   - Java 8+ kullandığınızdan emin olun
   - Maven bağımlılıklarının doğru yüklendiğini kontrol edin

2. **Dosya Okuma Hatası**
   - Dosyanın UTF-8 kodlamasında olduğundan emin olun
   - Dosya yolunda Türkçe karakter olmadığından emin olun

3. **Bellek Hatası**
   - Çok büyük dosyalar için JVM heap size'ını artırın:
   ```bash
   java -Xmx2g -jar target/turkish-spell-checker-app-1.0.0.jar
   ```

### Performans İpuçları
- Küçük dosyalar için daha hızlı sonuç alırsınız
- Büyük dosyalar için sabırlı olun
- İşlem sırasında "Durdur" butonunu kullanabilirsiniz

## Lisans

Bu proje MIT lisansı altında lisanslanmıştır.

## Katkıda Bulunma

1. Projeyi fork edin
2. Feature branch oluşturun (`git checkout -b feature/yeni-ozellik`)
3. Değişikliklerinizi commit edin (`git commit -am 'Yeni özellik eklendi'`)
4. Branch'inizi push edin (`git push origin feature/yeni-ozellik`)
5. Pull Request oluşturun

## İletişim

Sorularınız için issue açabilir veya doğrudan iletişime geçebilirsiniz. 
