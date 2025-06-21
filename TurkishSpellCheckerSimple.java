import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import java.util.Properties;

// Zemberek sınıflarını doğrudan kullan
import zemberek.morphology.TurkishMorphology;
import zemberek.normalization.TurkishSpellChecker;
import zemberek.tokenization.TurkishTokenizer;
import zemberek.tokenization.Token;

public class TurkishSpellCheckerSimple extends JFrame {
    
    private TurkishMorphology morphology;
    private TurkishSpellChecker spellChecker;
    private TurkishTokenizer tokenizer;
    
    private JTextArea inputArea;
    private JTextArea outputArea;
    private JButton selectFilesButton;
    private JButton calismalarimButton;
    private JButton startButton;
    private JButton continueButton;
    private JButton elenenlerButton;
    private JButton ayarlarButton;
    private JButton kayitliKelimelerButton;
    private JButton klavuzButton;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JList<String> fileList;
    private DefaultListModel<String> fileListModel;
    private JPanel leftPanel; // Sol panel referansı
    
    private java.util.Set<String> eliminatedWords = new java.util.LinkedHashSet<>();
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private AtomicBoolean isPaused = new AtomicBoolean(false);
    private AtomicInteger totalWords = new AtomicInteger(0);
    private AtomicInteger processedWords = new AtomicInteger(0);
    
    private int currentIncorrectWordIndex = 0; // Hatalı kelime listesindeki ilerlemeyi takip eder
    private StringBuilder correctionsLog = new StringBuilder();
    private List<File> selectedFiles = new ArrayList<>();
    private int currentFileIndex = 0;
    private int pausedFileIndex = 0; // Duraklatıldığında hangi dosyada olduğunu hatırla
    private int pausedIncorrectWordIndex = 0; // Duraklatıldığında hangi kelimede olduğunu hatırla
    
    // Ayarlar sistemi
    private Properties settings;
    private File settingsFile;
    private boolean autoLoadEliminatedWords = true;
    private boolean autoSaveEliminatedWords = false;
    private boolean saveOnlyOnExit = true;
    private boolean autoLoadSavedWords = true;
    private boolean autoSaveSavedWords = false;
    private boolean saveSavedWordsOnlyOnExit = true;
    
    // Son dosya seçim konumu
    private File lastFileChooserDirectory = new File(System.getProperty("user.home"));
    private boolean showGuideOnStartup = true;
    
    // Kayıtlı kelimeler sistemi
    private java.util.Map<String, String> savedWords = new java.util.LinkedHashMap<>();
    
    // Çalışma alanı sistemi
    private String currentWorkspaceName = null;
    private File currentWorkspaceDir = null;
    
    // Geri Al özelliği için eylem tanımları
    private enum UserAction { ACCEPT, IGNORE, PAUSE, ELIMINATE, UNDO, CUSTOM, APPLY_TO_FILE, APPLY_TO_ALL_FILES, STOP }
    private static class CorrectionResult {
        UserAction action;
        String text; // Kabul edilen, özel veya orijinal kelime
        
        CorrectionResult(UserAction action, String text) {
            this.action = action;
            this.text = text;
        }
    }
    
    public TurkishSpellCheckerSimple() {
        // UTF-8 encoding ayarları
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");
        
        initializeSettings();
        initializeComponents();
        initializeZemberek();
        createDirectories();
        loadEliminatedWordsIfEnabled();
        loadSavedWordsIfEnabled();

        if (showGuideOnStartup) {
            // Use invokeLater to make sure the main frame is visible before the dialog
            SwingUtilities.invokeLater(() -> showGuideDialog(true));
        }
    }
    
    private void initializeComponents() {
        setTitle("Turkce Yazim Denetimi Uygulamasi (Gelismis Versiyon)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        
        // Ana panel
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Üst panel - Butonlar
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        selectFilesButton = new JButton("Dosya Sec");
        calismalarimButton = new JButton("Calismalarim");
        startButton = new JButton("Yazim Denetimi Baslat");
        continueButton = new JButton("Devam Et");
        continueButton.setEnabled(false);
        elenenlerButton = new JButton("Elenenler");
        kayitliKelimelerButton = new JButton("Kayitli Kelimeler");
        ayarlarButton = new JButton("Ayarlar");
        klavuzButton = new JButton("Klavuz");
        
        topPanel.add(selectFilesButton);
        topPanel.add(calismalarimButton);
        topPanel.add(startButton);
        topPanel.add(continueButton);
        topPanel.add(elenenlerButton);
        topPanel.add(kayitliKelimelerButton);
        topPanel.add(ayarlarButton);
        topPanel.add(klavuzButton);
        
        // Sol panel - Dosya listesi
        leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBorder(BorderFactory.createTitledBorder("Secilen Dosyalar"));
        leftPanel.setPreferredSize(new Dimension(250, 0));
        
        fileListModel = new DefaultListModel<>();
        fileList = new JList<>(fileListModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        
        // Dosya listesine tıklama olayı ekle
        fileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && fileList.getSelectedIndex() >= 0) {
                loadFile(fileList.getSelectedIndex());
            }
        });
        
        JScrollPane fileScrollPane = new JScrollPane(fileList);
        leftPanel.add(fileScrollPane, BorderLayout.CENTER);
        
        // Orta panel - Metin alanları
        JPanel centerPanel = new JPanel(new GridLayout(1, 2, 10, 0));
        
        // Sol metin alanı - Giriş metni
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.setBorder(BorderFactory.createTitledBorder("Giris Metni"));
        inputArea = new JTextArea();
        inputArea.setFont(new Font("Arial", Font.PLAIN, 14));
        JScrollPane inputScrollPane = new JScrollPane(inputArea);
        inputPanel.add(inputScrollPane, BorderLayout.CENTER);
        
        // Sağ metin alanı - Çıkış metni
        JPanel outputPanel = new JPanel(new BorderLayout());
        outputPanel.setBorder(BorderFactory.createTitledBorder("Duzeltilmis Metin"));
        outputArea = new JTextArea();
        outputArea.setFont(new Font("Arial", Font.PLAIN, 14));
        outputArea.setEditable(false);
        JScrollPane outputScrollPane = new JScrollPane(outputArea);
        outputPanel.add(outputScrollPane, BorderLayout.CENTER);
        
        centerPanel.add(inputPanel);
        centerPanel.add(outputPanel);
        
        // Alt panel - İlerleme ve durum
        JPanel bottomPanel = new JPanel(new BorderLayout());
        progressBar = new JProgressBar(0, 100);
        statusLabel = new JLabel("Hazir");
        bottomPanel.add(progressBar, BorderLayout.CENTER);
        bottomPanel.add(statusLabel, BorderLayout.SOUTH);
        
        // Ana panel düzeni
        JPanel contentPanel = new JPanel(new BorderLayout(10, 0));
        contentPanel.add(leftPanel, BorderLayout.WEST);
        contentPanel.add(centerPanel, BorderLayout.CENTER);
        
        mainPanel.add(topPanel, BorderLayout.NORTH);
        mainPanel.add(contentPanel, BorderLayout.CENTER);
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);
        
        add(mainPanel);
        
        // Event listeners
        selectFilesButton.addActionListener(e -> selectFiles(false));
        calismalarimButton.addActionListener(e -> showWorkspaceDialog());
        startButton.addActionListener(e -> startSpellChecking());
        continueButton.addActionListener(e -> continueSpellChecking());
        elenenlerButton.addActionListener(e -> showEliminatedWordsDialog());
        kayitliKelimelerButton.addActionListener(e -> showSavedWordsDialog());
        ayarlarButton.addActionListener(e -> showSettingsDialog());
        klavuzButton.addActionListener(e -> showGuideDialog(false));
        
        // Uygulama kapatılırken elenenler listesini kaydet
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                saveEliminatedWordsOnExit();
                saveSavedWordsOnExit();
                
                // Eğer aktif bir çalışma alanı varsa, işlenmiş dosyaları Tarandi klasörüne taşı
                if (currentWorkspaceDir != null && !selectedFiles.isEmpty()) {
                    try {
                        File taranacakDir = new File(currentWorkspaceDir, "Taranacak");
                        File tarandiDir = new File(currentWorkspaceDir, "Tarandi");
                        File workspaceSonucDir = new File(currentWorkspaceDir, "Sonuc");
                        
                        // Çalışma alanının Sonuc klasöründeki dosyaları kontrol et ve Tarandi klasörüne taşı
                        if (workspaceSonucDir.exists()) {
                            File[] workspaceSonucFiles = workspaceSonucDir.listFiles();
                            if (workspaceSonucFiles != null) {
                                for (File workspaceSonucFile : workspaceSonucFiles) {
                                    // Bu dosya seçili dosyalar arasında mı kontrol et
                                    boolean isSelectedFile = false;
                                    for (File selectedFile : selectedFiles) {
                                        if (selectedFile.getName().equals(workspaceSonucFile.getName())) {
                                            isSelectedFile = true;
                                            break;
                                        }
                                    }
                                    
                                    if (isSelectedFile) {
                                        // Dosyayı Tarandi klasörüne taşı
                                        File destFile = new File(tarandiDir, workspaceSonucFile.getName());
                                        if (!destFile.exists()) {
                                            Files.move(workspaceSonucFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (IOException ex) {
                        System.err.println("Calisma alani dosyalari tasinirken hata: " + ex.getMessage());
                    }
                }
            }
        });
    }
    
    private void updateLeftPanelTitle() {
        if (currentWorkspaceDir != null && currentWorkspaceName != null) {
            leftPanel.setBorder(BorderFactory.createTitledBorder("Secilen Dosyalar - " + currentWorkspaceName));
        } else {
            leftPanel.setBorder(BorderFactory.createTitledBorder("Secilen Dosyalar"));
        }
        leftPanel.revalidate();
        leftPanel.repaint();
    }
    
    private void initializeZemberek() {
        try {
            statusLabel.setText("Zemberek yukleniyor...");
            
            // Zemberek'i başlat
            morphology = TurkishMorphology.createWithDefaults();
            spellChecker = new TurkishSpellChecker(morphology);
            tokenizer = TurkishTokenizer.ALL;
            
            statusLabel.setText("Zemberek yuklendi - Hazir");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, 
                "Zemberek kutuphanesi yuklenirken hata olustu: " + e.getMessage() + "\n\n" +
                "Lutfen zemberek klasorunun bu dizinde oldugundan emin olun.",
                "Hata", JOptionPane.ERROR_MESSAGE);
            statusLabel.setText("Zemberek yuklenemedi");
            e.printStackTrace();
        }
    }
    
    private void createDirectories() {
        try {
            Files.createDirectories(Paths.get("Sonuc"));
            Files.createDirectories(Paths.get("Duzeltmeler"));
            Files.createDirectories(Paths.get("Elenenler"));
            Files.createDirectories(Paths.get("KayitliKelimeler"));
            Files.createDirectories(Paths.get("Ayarlar"));
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, 
                "Klasorler olusturulurken hata: " + e.getMessage(),
                "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void initializeSettings() {
        settings = new Properties();
        settingsFile = new File("Ayarlar/ayarlar.txt");
        
        // Varsayılan ayarlar
        settings.setProperty("autoLoadEliminatedWords", "true");
        settings.setProperty("autoSaveEliminatedWords", "false");
        settings.setProperty("saveOnlyOnExit", "true");
        settings.setProperty("autoLoadSavedWords", "true");
        settings.setProperty("autoSaveSavedWords", "false");
        settings.setProperty("saveSavedWordsOnlyOnExit", "true");
        settings.setProperty("lastFileChooserDirectory", System.getProperty("user.home"));
        settings.setProperty("showGuideOnStartup", "true");
        
        // Ayarlar dosyası varsa yükle
        if (settingsFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(settingsFile), "UTF-8"))) {
                // Manuel olarak dosyayı oku (Properties.load() yerine)
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                        String[] parts = line.split("=", 2);
                        if (parts.length == 2) {
                            String key = parts[0].trim();
                            String value = parts[1].trim();
                            settings.setProperty(key, value);
                        }
                    }
                }
                
                // Ayarları yeni sırayla oku (kaydetme sırasıyla aynı)
                autoLoadEliminatedWords = Boolean.parseBoolean(settings.getProperty("autoLoadEliminatedWords", "false"));
                autoSaveEliminatedWords = Boolean.parseBoolean(settings.getProperty("autoSaveEliminatedWords", "false"));
                saveOnlyOnExit = Boolean.parseBoolean(settings.getProperty("saveOnlyOnExit", "false"));
                autoLoadSavedWords = Boolean.parseBoolean(settings.getProperty("autoLoadSavedWords", "false"));
                autoSaveSavedWords = Boolean.parseBoolean(settings.getProperty("autoSaveSavedWords", "false"));
                saveSavedWordsOnlyOnExit = Boolean.parseBoolean(settings.getProperty("saveSavedWordsOnlyOnExit", "false"));
                showGuideOnStartup = Boolean.parseBoolean(settings.getProperty("showGuideOnStartup", "true"));
                
                // Son dosya seçim konumunu yükle (en son)
                String lastDir = settings.getProperty("lastFileChooserDirectory", System.getProperty("user.home"));
                System.out.println("Ayarlardan okunan lastFileChooserDirectory: " + lastDir);
                File lastDirFile = new File(lastDir);
                if (lastDirFile.exists() && lastDirFile.isDirectory()) {
                    lastFileChooserDirectory = lastDirFile;
                    System.out.println("lastFileChooserDirectory başarıyla ayarlandı: " + lastFileChooserDirectory.getAbsolutePath());
                } else {
                    System.out.println("lastFileChooserDirectory geçersiz veya bulunamadı: " + lastDir);
                }
            } catch (IOException e) {
                System.err.println("Ayarlar dosyasi okunurken hata: " + e.getMessage());
            }
        }
    }
    
    private void loadEliminatedWordsIfEnabled() {
        if (autoLoadEliminatedWords) {
            File eliminatedWordsFile = new File("Elenenler/elenenler.txt");
            if (eliminatedWordsFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(eliminatedWordsFile), "UTF-8"))) {
                    String line;
                    int count = 0;
                    // Dosyadan okunan sırayı korumak için önce listeye ekle
                    List<String> wordsFromFile = new ArrayList<>();
                    while ((line = reader.readLine()) != null) {
                        String word = line.trim();
                        if (!word.isEmpty()) {
                            wordsFromFile.add(word);
                        }
                    }
                    
                    // Dosyadan okunan kelimeleri sırayla HashSet'e ekle (LinkedHashSet sırayı korur)
                    for (String word : wordsFromFile) {
                        if (eliminatedWords.add(word)) {
                            count++;
                        }
                    }
                    
                    if (count > 0) {
                        statusLabel.setText(count + " elenen kelime yuklendi");
                    }
                } catch (IOException e) {
                    System.err.println("Elenenler dosyasi okunurken hata: " + e.getMessage());
                }
            }
        }
    }
    
    private void loadSavedWordsIfEnabled() {
        if (autoLoadSavedWords) {
            File savedWordsFile = new File("KayitliKelimeler/kayitlikelimeler.txt");
            if (savedWordsFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(savedWordsFile), "UTF-8"))) {
                    String line;
                    int count = 0;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty() && line.contains("=")) {
                            String[] parts = line.split("=", 2);
                            if (parts.length == 2) {
                                String wrongWord = parts[0].trim();
                                String correctWord = parts[1].trim();
                                if (!wrongWord.isEmpty() && !correctWord.isEmpty()) {
                                    savedWords.put(wrongWord, correctWord);
                                    count++;
                                }
                            }
                        }
                    }
                    if (count > 0) {
                        System.out.println(count + " kayitli kelime yuklendi");
                    }
                } catch (IOException e) {
                    System.err.println("Kayitli kelimeler dosyasi okunurken hata: " + e.getMessage());
                }
            }
        }
    }
    
    private void saveSavedWords() {
        File savedWordsFile = new File("KayitliKelimeler/kayitlikelimeler.txt");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(savedWordsFile), "UTF-8"))) {
            // En son eklenen en üstte olacak şekilde kaydet
            List<java.util.Map.Entry<String, String>> entries = new ArrayList<>(savedWords.entrySet());
            Collections.reverse(entries);
            for (java.util.Map.Entry<String, String> entry : entries) {
                writer.write(entry.getKey() + "=" + entry.getValue());
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Kayitli kelimeler dosyasi yazilirken hata: " + e.getMessage());
        }
    }
    
    private void saveEliminatedWordsIfEnabled() {
        if (autoSaveEliminatedWords && !saveOnlyOnExit) {
            File eliminatedWordsFile = new File("Elenenler/elenenler.txt");
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(eliminatedWordsFile), "UTF-8"))) {
                // En son eklenen en başta olacak şekilde kaydet
                List<String> wordsList = new ArrayList<>(eliminatedWords);
                Collections.reverse(wordsList);
                for (String word : wordsList) {
                    writer.write(word);
                    writer.newLine();
                }
            } catch (IOException e) {
                System.err.println("Elenenler dosyasi yazilirken hata: " + e.getMessage());
            }
        }
    }
    
    private void saveEliminatedWordsOnExit() {
        if (autoSaveEliminatedWords || saveOnlyOnExit) {
            File eliminatedWordsFile = new File("Elenenler/elenenler.txt");
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(eliminatedWordsFile), "UTF-8"))) {
                // En son eklenen en başta olacak şekilde kaydet
                List<String> wordsList = new ArrayList<>(eliminatedWords);
                Collections.reverse(wordsList);
                for (String word : wordsList) {
                    writer.write(word);
                    writer.newLine();
                }
            } catch (IOException e) {
                System.err.println("Elenenler dosyasi yazilirken hata: " + e.getMessage());
            }
        }
    }
    
    private void saveSavedWordsIfEnabled() {
        if (autoSaveSavedWords && !saveSavedWordsOnlyOnExit) {
            File savedWordsFile = new File("KayitliKelimeler/kayitlikelimeler.txt");
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(savedWordsFile), "UTF-8"))) {
                // En son eklenen en üstte olacak şekilde kaydet
                List<java.util.Map.Entry<String, String>> entries = new ArrayList<>(savedWords.entrySet());
                Collections.reverse(entries);
                for (java.util.Map.Entry<String, String> entry : entries) {
                    writer.write(entry.getKey() + "=" + entry.getValue());
                    writer.newLine();
                }
            } catch (IOException e) {
                System.err.println("Kayitli kelimeler dosyasi yazilirken hata: " + e.getMessage());
            }
        }
    }
    
    private void saveSavedWordsOnExit() {
        if (autoSaveSavedWords || saveSavedWordsOnlyOnExit) {
            saveSavedWords();
        }
    }
    
    private void selectFiles(boolean forNewWorkspace) {
        JFileChooser fileChooser = new JFileChooser(lastFileChooserDirectory);
        fileChooser.setFileFilter(new FileNameExtensionFilter("Metin Dosyalari", "txt"));
        fileChooser.setMultiSelectionEnabled(true);
        
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File[] files = fileChooser.getSelectedFiles();
            
            // Eğer aktif bir çalışma alanı varsa ve bu yeni bir çalışma alanı için dosya seçimi değilse, çalışma alanından çık
            if (currentWorkspaceDir != null && !forNewWorkspace) {
                String workspaceName = currentWorkspaceName;
                currentWorkspaceName = null;
                currentWorkspaceDir = null;
                
                // Sol panel başlığını güncelle
                updateLeftPanelTitle();
                
                JOptionPane.showMessageDialog(this, 
                    "Calisma alani '" + workspaceName + "' kapatildi. Normal moda gecildi.", 
                    "Calisma Alani Kapatildi", JOptionPane.INFORMATION_MESSAGE);
            }
            
            // Son seçilen dosyanın konumunu kaydet
            if (files.length > 0) {
                lastFileChooserDirectory = files[0].getParentFile();
                System.out.println("Dosya seçimi sırasında lastFileChooserDirectory ayarlandı: " + lastFileChooserDirectory.getAbsolutePath());
                // Ayarlara kaydet
                saveSettings();
            }
            
            selectedFiles.clear();
            fileListModel.clear();
            
            for (int i = 0; i < files.length; i++) {
                selectedFiles.add(files[i]);
                // Numaralı dosya listesi: "1. dosyaadi.txt"
                fileListModel.addElement((i + 1) + ". " + files[i].getName());
            }
            
            // Eğer aktif bir çalışma alanı varsa, dosyaları Taranacak klasörüne kopyala
            if (currentWorkspaceDir != null) {
                try {
                    File taranacakDir = new File(currentWorkspaceDir, "Taranacak");
                    for (File sourceFile : files) {
                        File destFile = new File(taranacakDir, sourceFile.getName());
                        Files.copy(sourceFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    JOptionPane.showMessageDialog(this, 
                        selectedFiles.size() + " dosya calisma alaninin 'Taranacak' klasorune kopyalandi.", 
                        "Basarili", JOptionPane.INFORMATION_MESSAGE);
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(this, 
                        "Dosyalar calisma alanina kopyalanirken hata: " + e.getMessage(), 
                        "Hata", JOptionPane.ERROR_MESSAGE);
                }
            }
            
            // Yeni dosya seçildiğinde durumu sıfırla
            currentFileIndex = 0;
            currentIncorrectWordIndex = 0;
            pausedIncorrectWordIndex = 0;
            isRunning.set(false);
            isPaused.set(false);
            
            // Buton durumlarını ayarla
            startButton.setEnabled(true);
            continueButton.setEnabled(false);
            
            statusLabel.setText(selectedFiles.size() + " dosya secildi");
            
            // İlk dosyayı yükle
            if (!selectedFiles.isEmpty()) {
                loadFile(0);
            }
            
            // Durum etiketini güncelle
            if (currentWorkspaceDir != null) {
                statusLabel.setText(selectedFiles.size() + " dosya secildi (Calisma Alani: " + currentWorkspaceName + ")");
            } else {
                statusLabel.setText(selectedFiles.size() + " dosya secildi (Normal Mod)");
            }
        }
    }
    
    private void loadFile(int index) {
        if (index >= 0 && index < selectedFiles.size()) {
            currentFileIndex = index;
            File listFile = selectedFiles.get(index); // This is the reference file from the list
            File fileToLoad = null;

            if (currentWorkspaceDir != null) {
                File taranacakFile = new File(new File(currentWorkspaceDir, "Taranacak"), listFile.getName());
                File tarandiFile = new File(new File(currentWorkspaceDir, "Tarandi"), listFile.getName());
                
                // Check Tarandi first, then Taranacak
                if (tarandiFile.exists()) {
                    fileToLoad = tarandiFile;
                } else if (taranacakFile.exists()) {
                    fileToLoad = taranacakFile;
                }
            } else {
                // Normal mode, the file should be at the path specified in the list
                fileToLoad = listFile;
            }

            if (fileToLoad == null || !fileToLoad.exists()) {
                inputArea.setText("");
                outputArea.setText("");
                statusLabel.setText("Dosya bulunamadi: " + listFile.getName());
                return;
            }
                
            try {
                String content = new String(Files.readAllBytes(fileToLoad.toPath()), "UTF-8");
                inputArea.setText(content);
                
                // Düzeltilmiş dosyayı kontrol et ve yükle
                String correctedFilePath;
                if (currentWorkspaceDir != null) {
                    // Çalışma alanı aktifse, çalışma alanının Sonuc klasöründen yükle
                    correctedFilePath = currentWorkspaceDir.getPath() + "/Sonuc/" + listFile.getName();
                } else {
                    // Normal modda global Sonuc klasöründen yükle
                    correctedFilePath = "Sonuc/" + listFile.getName();
                }
                File correctedFile = new File(correctedFilePath);
                if (correctedFile.exists()) {
                    String correctedContent = new String(Files.readAllBytes(correctedFile.toPath()), "UTF-8");
                    outputArea.setText(correctedContent);
                } else {
                    outputArea.setText("");
                }
                
                fileList.setSelectedIndex(index);
                fileList.ensureIndexIsVisible(index);
                statusLabel.setText("Dosya yuklendi: " + fileToLoad.getName() + " (" + (index + 1) + "/" + selectedFiles.size() + ")");
            } catch (IOException e) {
                JOptionPane.showMessageDialog(this, 
                    "Dosya okunurken hata: " + e.getMessage(),
                    "Hata", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void startSpellChecking() {
        if (selectedFiles.isEmpty()) {
            JOptionPane.showMessageDialog(this, 
                "Lutfen once dosya secin.",
                "Uyari", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String[] options = {"Tumunu Tara", "Seciliden Basla", "Iptal"};
        String message = "Yazim denetimine nasil baslamak istersiniz?";
        if (currentWorkspaceDir != null) {
            message += "\n('Tarandi' klasorundeki dosyalar tekrar taranmak uzere geri tasinacaktir.)";
        }

        int choice = JOptionPane.showOptionDialog(this,
                message,
                "Denetimi Baslat",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);

        if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) { // Cancel or closed
            return;
        }

        int startIndex = 0;
        if (choice == 1) { // "Seçiliden Başla"
            startIndex = fileList.getSelectedIndex();
            if (startIndex < 0) {
                startIndex = 0; 
            }
        }
        
        currentFileIndex = startIndex;

        isRunning.set(true);
        isPaused.set(false);
        correctionsLog.setLength(0);
        startButton.setEnabled(false);
        continueButton.setEnabled(false);
        progressBar.setValue(0);
        
        // Ayrı thread'de çalıştır
        new Thread(() -> {
            processAllFiles();
        }).start();
    }
    
    private void processAllFiles() {
        final int totalFileCount = selectedFiles.size();
        for (int i = currentFileIndex; i < totalFileCount && isRunning.get(); i++) {
            final int fileIndex = i; // Final variable for lambda
            currentFileIndex = i;
            File listFile = selectedFiles.get(i);
            File currentFile = listFile; // Default for normal mode

            if (currentWorkspaceDir != null) {
                File taranacakFile = new File(new File(currentWorkspaceDir, "Taranacak"), listFile.getName());
                File tarandiFile = new File(new File(currentWorkspaceDir, "Tarandi"), listFile.getName());
                
                // If the file is in Tarandi, move it back to be re-scanned
                if (tarandiFile.exists()) {
                    try {
                        Files.move(tarandiFile.toPath(), taranacakFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        final String errorMessage = "Dosya '" + tarandiFile.getName() + "' geri tasinirken hata: " + e.getMessage();
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(this, errorMessage, "Hata", JOptionPane.ERROR_MESSAGE);
                        });
                        continue; // Skip this file
                    }
                }
                currentFile = taranacakFile; // The file to process is always in Taranacak
            }
            
            if (!currentFile.exists()) {
                // This can happen if file was deleted externally.
                continue; 
            }
            
            final File finalCurrentFile = currentFile;
            SwingUtilities.invokeLater(() -> {
                loadFile(fileIndex);
                statusLabel.setText("Isleniyor: " + finalCurrentFile.getName() + " (" + (fileIndex + 1) + "/" + selectedFiles.size() + ")");
            });
            
            try {
                String content = new String(Files.readAllBytes(currentFile.toPath()), "UTF-8");
                
                // Her dosya için ayrı düzeltme logu oluştur
                StringBuilder fileCorrectionsLog = new StringBuilder();
                
                // Duraklatmadan devam ediliyorsa, kalınan kelimeden başla
                int startFromIndex = (i == pausedFileIndex && isPaused.get() == false) ? pausedIncorrectWordIndex : 0;
                
                String correctedContent = processText(content, currentFile.getName(), fileCorrectionsLog, startFromIndex);
                
                // Eğer işlem durdurulduysa (geri al değil)
                if (!isRunning.get() || (isPaused.get() && correctedContent == null)) {
                    break;
                }
                
                // Eğer işlem duraklatıldıysa
                if (isPaused.get()) {
                    break;
                }
                
                // Düzeltilmiş içeriği orijinal dosya ismi ile kaydet
                String correctedFileName;
                if (currentWorkspaceDir != null) {
                    // Çalışma alanı aktifse, çalışma alanının altında Sonuc klasörü oluştur
                    File workspaceSonucDir = new File(currentWorkspaceDir, "Sonuc");
                    if (!workspaceSonucDir.exists()) {
                        workspaceSonucDir.mkdir();
                    }
                    correctedFileName = workspaceSonucDir.getPath() + "/" + currentFile.getName();
                } else {
                    // Normal modda global Sonuc klasörünü kullan
                    correctedFileName = "Sonuc/" + currentFile.getName();
                }
                Files.write(Paths.get(correctedFileName), correctedContent.getBytes("UTF-8"));
                
                // Eğer aktif bir çalışma alanı varsa, dosyayı Tarandi klasörüne taşı
                if (currentWorkspaceDir != null) {
                    try {
                        File taranacakDir = new File(currentWorkspaceDir, "Taranacak");
                        File tarandiDir = new File(currentWorkspaceDir, "Tarandi");
                        File sourceFile = new File(taranacakDir, currentFile.getName());
                        File destFile = new File(tarandiDir, currentFile.getName());
                        
                        if (sourceFile.exists()) {
                            Files.move(sourceFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(this, 
                                "Dosya calisma alanina tasinirken hata: " + e.getMessage(),
                                "Hata", JOptionPane.ERROR_MESSAGE);
                        });
                    }
                }
                
                // Bu dosya için düzeltmeleri ayrı dosyaya kaydet (sadece düzeltme varsa)
                if (fileCorrectionsLog.length() > 0) {
                    try {
                        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                        String baseFileName = currentFile.getName().replaceFirst("[.][^.]+$", ""); // Uzantıyı kaldır
                        
                        String correctionsFileName;
                        if (currentWorkspaceDir != null) {
                            File workspaceDuzeltmelerDir = new File(currentWorkspaceDir, "Duzeltmeler");
                            if (!workspaceDuzeltmelerDir.exists()) {
                                workspaceDuzeltmelerDir.mkdir();
                            }
                            correctionsFileName = workspaceDuzeltmelerDir.getPath() + "/" + baseFileName + "_duzeltmesi_" + timestamp + ".txt";
                        } else {
                            correctionsFileName = "Duzeltmeler/" + baseFileName + "_duzeltmesi_" + timestamp + ".txt";
                        }
                        
                        Files.write(Paths.get(correctionsFileName), fileCorrectionsLog.toString().getBytes("UTF-8"));
                    } catch (IOException e) {
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(this, 
                                "Duzeltmeler kaydedilirken hata: " + e.getMessage(),
                                "Hata", JOptionPane.ERROR_MESSAGE);
                        });
                    }
                }
                
                // Son düzeltilmiş içeriği göster
                final String finalContent = correctedContent;
                SwingUtilities.invokeLater(() -> {
                    outputArea.setText(finalContent);
                    outputArea.setCaretPosition(0); // Başa git
                });
                
                // İlerleme çubuğunu güncelle
                final int progress = (int) Math.round(((double) (i + 1) / totalFileCount) * 100);
                SwingUtilities.invokeLater(() -> progressBar.setValue(progress));
                
                // Dosya tamamlandı, bir sonraki dosyaya geç
                currentIncorrectWordIndex = 0;
                pausedIncorrectWordIndex = 0;
                
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, 
                        "Dosya islenirken hata: " + e.getMessage(),
                        "Hata", JOptionPane.ERROR_MESSAGE);
                });
            }
        }
        
        // Tüm dosyalar tamamlandı (duraklatılmadıysa)
        if (isRunning.get() && !isPaused.get()) {
            SwingUtilities.invokeLater(() -> {
                startButton.setEnabled(true);
                continueButton.setEnabled(false);
                statusLabel.setText("Tamamlandi");
                progressBar.setValue(100);
            });
        }
    }
    
    private String processText(String inputText, String fileName, StringBuilder fileCorrectionsLog, int startFromIncorrectWordIndex) {
        
        // 1. Tüm metni kelimelere ve diğer karakterlere ayır (tokenization)
        List<String> tokensAsString = new ArrayList<>();
        List<Token> tokens = tokenizer.tokenize(inputText);
        for (Token token : tokens) {
            tokensAsString.add(token.getText());
        }

        // 2. Hatalı kelimelerin indekslerini bul
        List<Integer> incorrectTokenIndices = new ArrayList<>();
        for (int i = 0; i < tokensAsString.size(); i++) {
            String token = tokensAsString.get(i);
            if (tokens.get(i).getType() == Token.Type.Word && !spellChecker.check(token) && !eliminatedWords.contains(token)) {
                // Kayıtlı kelimeleri kontrol et
                if (savedWords.containsKey(token)) {
                    // Kayıtlı kelime bulundu, otomatik düzelt
                    String correctWord = savedWords.get(token);
                    tokensAsString.set(i, correctWord);
                    // Düzeltme loguna ekle
                    fileCorrectionsLog.append(String.format("Dosya: %s, Satir: %d, Yanlis: %s, Duzeltme: %s (Kayitli)\n",
                            fileName, 0, token, correctWord));
                } else {
                incorrectTokenIndices.add(i);
                }
            }
        }

        // 3. Düzeltme döngüsü
        String[] correctedTokens = tokensAsString.toArray(new String[0]);
        String[] lines = inputText.split("\\r?\\n");

        for (int i = startFromIncorrectWordIndex; i < incorrectTokenIndices.size(); i++) {
             // Duraklatma ve durdurma kontrolü
            while (isPaused.get() && isRunning.get()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            if (!isRunning.get()) break;

            currentIncorrectWordIndex = i;
            int tokenIndex = incorrectTokenIndices.get(i);
            String wrongWord = tokensAsString.get(tokenIndex);

            // ANINDA KONTROL: Kelime döngü içinde elenmiş olabilir, tekrar kontrol et.
            if (eliminatedWords.contains(wrongWord)) {
                continue; // Bu kelimeyi atla ve döngüye devam et
            }

            // Satır numarasını ve içeriğini bul
            int charCount = 0;
            int lineNumber = 1;
            for (int j = 0; j < tokenIndex; j++) {
                for(char c : tokensAsString.get(j).toCharArray()) {
                    if (c == '\n') lineNumber++;
                }
            }
            String lineContent = (lineNumber <= lines.length) ? lines[lineNumber - 1] : "";

            List<String> suggestions = spellChecker.suggestForWord(wrongWord);
            
            CorrectionResult result = askUserForCorrectionWithCustomInput(
                wrongWord, suggestions, lineNumber, fileName, lineContent, i > 0);

            switch (result.action) {
                case UNDO:
                    // Bir önceki hatalı kelimenin düzeltmesini geri al
                    if (i > 0) {
                        int prevIncorrectTokenIndex = incorrectTokenIndices.get(i-1);
                        correctedTokens[prevIncorrectTokenIndex] = tokensAsString.get(prevIncorrectTokenIndex);
                    }
                    i = Math.max(-1, i - 2); // Döngü i++ yapacağı için -2
                    continue; // Döngünün başına dön
                case ACCEPT:
                case CUSTOM:
                    correctedTokens[tokenIndex] = result.text;
                    if (!result.text.equals(wrongWord)) {
                        fileCorrectionsLog.append(String.format("Dosya: %s, Satir: %d, Yanlis: %s, Duzeltme: %s\n",
                                fileName, lineNumber, wrongWord, result.text));
                    }
                    break;
                case APPLY_TO_FILE:
                    // Bu dosyada aynı kelimeyi bul ve düzelt
                    correctedTokens[tokenIndex] = result.text;
                    if (!result.text.equals(wrongWord)) {
                        fileCorrectionsLog.append(String.format("Dosya: %s, Satir: %d, Yanlis: %s, Duzeltme: %s (Dosyaya Uygulandi)\n",
                                fileName, lineNumber, wrongWord, result.text));
                    }
                    // Bu dosyada aynı kelimeyi bul ve düzelt, sonra incorrectTokenIndices'den çıkar
                    for (int j = incorrectTokenIndices.size() - 1; j > i; j--) {
                        int nextTokenIndex = incorrectTokenIndices.get(j);
                        String nextWrongWord = tokensAsString.get(nextTokenIndex);
                        if (nextWrongWord.equals(wrongWord)) {
                            correctedTokens[nextTokenIndex] = result.text;
                            fileCorrectionsLog.append(String.format("Dosya: %s, Satir: %d, Yanlis: %s, Duzeltme: %s (Otomatik)\n",
                                    fileName, 0, nextWrongWord, result.text));
                            incorrectTokenIndices.remove(j);
                        }
                    }
                    break;
                case APPLY_TO_ALL_FILES:
                    // Bu kelimeyi düzelt ve kayıtlı kelimelere ekle (zaten eklenmiş)
                    correctedTokens[tokenIndex] = result.text;
                    if (!result.text.equals(wrongWord)) {
                        fileCorrectionsLog.append(String.format("Dosya: %s, Satir: %d, Yanlis: %s, Duzeltme: %s (Tum Dosyalara Uygulandi)\n",
                                fileName, lineNumber, wrongWord, result.text));
                    }
                    // Bu dosyada aynı kelimeyi bul ve düzelt, sonra incorrectTokenIndices'den çıkar
                    for (int j = incorrectTokenIndices.size() - 1; j > i; j--) {
                        int nextTokenIndex = incorrectTokenIndices.get(j);
                        String nextWrongWord = tokensAsString.get(nextTokenIndex);
                        if (nextWrongWord.equals(wrongWord)) {
                            correctedTokens[nextTokenIndex] = result.text;
                            fileCorrectionsLog.append(String.format("Dosya: %s, Satir: %d, Yanlis: %s, Duzeltme: %s (Otomatik)\n",
                                    fileName, 0, nextWrongWord, result.text));
                            incorrectTokenIndices.remove(j);
                        }
                    }
                    break;
                case IGNORE:
                    // Değişiklik yapma, orijinal kelime kalır
                    break;
                case ELIMINATE:
                    eliminatedWords.add(wrongWord);
                    saveEliminatedWordsIfEnabled();
                    break;
                case PAUSE:
                    isPaused.set(true);
                    pausedFileIndex = currentFileIndex;
                    pausedIncorrectWordIndex = i; // Hatalı kelime listesindeki indeksi kaydet
                    return null; // Duraklatıldığını belirtmek için null dön
                case STOP:
                     isRunning.set(false);
                     return null;
            }
        }
        
        if (!isRunning.get() || isPaused.get()) {
            return null;
        }

        // 4. Düzeltilmiş metni birleştir
        return String.join("", correctedTokens);
    }
    
    private boolean isWordToken(String text) {
        return text.matches(".*[a-zA-Z].*") && !text.trim().isEmpty() && text.length() > 1;
    }
    
    private CorrectionResult askUserForCorrectionWithCustomInput(String wrongWord, List<String> suggestions, int lineNumber, String fileName, String lineContent, boolean canUndo) {
        final CorrectionResult[] resultWrapper = new CorrectionResult[1];
        resultWrapper[0] = new CorrectionResult(UserAction.IGNORE, wrongWord); // Varsayılan: Yoksay
        final CountDownLatch latch = new CountDownLatch(1);
        
        SwingUtilities.invokeLater(() -> {
            if (!isRunning.get()) {
                resultWrapper[0] = new CorrectionResult(UserAction.STOP, null);
                latch.countDown();
                return;
            }
            
            JDialog dialog = new JDialog(this, "Yazim Hatasi Duzeltme", true);
            dialog.setLayout(new BorderLayout(10, 10));
            
            Point outputLocation = outputArea.getLocationOnScreen();
            Dimension outputSize = outputArea.getSize();
            dialog.setLocation(outputLocation);
            dialog.setSize(outputSize);
            
            // Üst Panel - Bilgi ve Geri Al Butonu
            JPanel topPanel = new JPanel(new BorderLayout());
            
            JPanel infoPanel = new JPanel(new GridLayout(4, 1));
            infoPanel.setBorder(BorderFactory.createTitledBorder("Hata Bilgileri"));
            infoPanel.add(new JLabel("Dosya: " + fileName));
            infoPanel.add(new JLabel("Satir No: " + lineNumber));
            infoPanel.add(new JLabel("Yanlis: " + wrongWord));
            infoPanel.add(new JLabel("Satir: " + lineContent));
            
            JButton undoButton = new JButton("Geri Al");
            undoButton.setEnabled(canUndo);
            undoButton.addActionListener(e -> {
                resultWrapper[0] = new CorrectionResult(UserAction.UNDO, null);
                dialog.dispose();
                latch.countDown();
            });
            
            topPanel.add(infoPanel, BorderLayout.CENTER);
            topPanel.add(undoButton, BorderLayout.EAST);

            JPanel suggestionsPanel = new JPanel(new BorderLayout());
            suggestionsPanel.setBorder(BorderFactory.createTitledBorder("Oneriler"));
            
            JList<String> suggestionsList = new JList<>(suggestions.toArray(new String[0]));
            suggestionsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            if (!suggestions.isEmpty()) {
                suggestionsList.setSelectedIndex(0);
            }
            JScrollPane suggestionsScrollPane = new JScrollPane(suggestionsList);
            suggestionsPanel.add(suggestionsScrollPane, BorderLayout.CENTER);
            
            JPanel customPanel = new JPanel(new BorderLayout(5, 0));
            customPanel.setBorder(BorderFactory.createTitledBorder("Ozel Duzeltme"));
            JTextField customField = new JTextField();
            JButton useCustomButton = new JButton("Ozel Duzeltmeyi Kullan");
            customPanel.add(customField, BorderLayout.CENTER);
            customPanel.add(useCustomButton, BorderLayout.EAST);
            
            // Yeni butonlar - "Bu Dosyaya Uygula" ve "Tüm Dosyalara Uygula"
            JPanel applyButtonsPanel = new JPanel(new FlowLayout());
            JButton applyToThisFileButton = new JButton("Bu Dosyaya Uygula");
            JButton applyToAllFilesButton = new JButton("Tum Dosyalara Uygula");
            
            applyToThisFileButton.addActionListener(e -> {
                String customText = customField.getText().trim();
                if (!customText.isEmpty()) {
                    resultWrapper[0] = new CorrectionResult(UserAction.APPLY_TO_FILE, customText);
                    dialog.dispose();
                    latch.countDown();
                } else {
                    JOptionPane.showMessageDialog(dialog, "Lutfen bir duzeltme girin.", "Uyari", JOptionPane.WARNING_MESSAGE);
                }
            });
            
            applyToAllFilesButton.addActionListener(e -> {
                String customText = customField.getText().trim();
                if (!customText.isEmpty()) {
                    // Kayıtlı kelimelere ekle
                    savedWords.put(wrongWord, customText);
                    saveSavedWordsIfEnabled();
                    resultWrapper[0] = new CorrectionResult(UserAction.APPLY_TO_ALL_FILES, customText);
                    dialog.dispose();
                    latch.countDown();
                } else {
                    JOptionPane.showMessageDialog(dialog, "Lutfen bir duzeltme girin.", "Uyari", JOptionPane.WARNING_MESSAGE);
                }
            });
            
            applyButtonsPanel.add(applyToThisFileButton);
            applyButtonsPanel.add(applyToAllFilesButton);
            
            JPanel buttonPanel = new JPanel(new FlowLayout());
            JButton acceptButton = new JButton("Oneriyi Kabul Et");
            JButton ignoreButton = new JButton("Gormezden Gel");
            JButton eliminateButton = new JButton("Ele");
            
            acceptButton.setEnabled(!suggestions.isEmpty());
            
            buttonPanel.add(acceptButton);
            buttonPanel.add(ignoreButton);
            buttonPanel.add(eliminateButton);
            
            acceptButton.addActionListener(e -> {
                if (!suggestions.isEmpty() && suggestionsList.getSelectedIndex() >= 0) {
                    resultWrapper[0] = new CorrectionResult(UserAction.ACCEPT, suggestions.get(suggestionsList.getSelectedIndex()));
                }
                dialog.dispose();
                latch.countDown();
            });
            
            ignoreButton.addActionListener(e -> {
                resultWrapper[0] = new CorrectionResult(UserAction.IGNORE, wrongWord);
                dialog.dispose();
                latch.countDown();
            });
            
            eliminateButton.addActionListener(e -> {
                resultWrapper[0] = new CorrectionResult(UserAction.ELIMINATE, wrongWord);
                dialog.dispose();
                latch.countDown();
            });
            
            useCustomButton.addActionListener(e -> {
                String customText = customField.getText().trim();
                if (!customText.isEmpty()) {
                    resultWrapper[0] = new CorrectionResult(UserAction.CUSTOM, customText);
                    dialog.dispose();
                    latch.countDown();
                } else {
                    JOptionPane.showMessageDialog(dialog, "Lutfen bir duzeltme girin.", "Uyari", JOptionPane.WARNING_MESSAGE);
                }
            });
            
            customField.addActionListener(e -> useCustomButton.doClick());
            
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    // Çarpıya basınca duraklat işlevi gör
                resultWrapper[0] = new CorrectionResult(UserAction.PAUSE, null);
                dialog.dispose();
                latch.countDown();
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Duraklatildi - Devam Et butonuna basin");
                    startButton.setEnabled(false);
                    continueButton.setEnabled(true);
                });
                }
            });
            
            JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
            centerPanel.add(topPanel, BorderLayout.NORTH); // Bilgi ve Geri Al butonu
            centerPanel.add(suggestionsPanel, BorderLayout.CENTER);
            
            // Alt panel - Yeni butonlar ve özel düzeltme
            JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
            bottomPanel.add(applyButtonsPanel, BorderLayout.NORTH); // Yeni butonlar
            bottomPanel.add(customPanel, BorderLayout.SOUTH); // Özel düzeltme
            
            centerPanel.add(bottomPanel, BorderLayout.SOUTH);
            
            dialog.add(centerPanel, BorderLayout.CENTER);
            dialog.add(buttonPanel, BorderLayout.SOUTH);
            
            dialog.setVisible(true);
        });
        
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CorrectionResult(UserAction.STOP, null);
        }
        
        return resultWrapper[0];
    }
    
    private void continueSpellChecking() {
        if (isPaused.get()) {
            // Kullanıcıya nereden devam etmek istediğini sor
            String[] options = {"Kaldigin yerden devam et", "Secili dosyadan basla", "Iptal"};
            int choice = JOptionPane.showOptionDialog(this,
                "Nereden devam etmek istiyorsunuz?",
                "Devam Et",
                JOptionPane.YES_NO_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]);
            
            if (choice == 0) { // Kaldığı yerden devam et
                isPaused.set(false);
                // pausedFileIndex zaten doğru ayarlı
                // pausedIncorrectWordIndex de doğru ayarlı
                statusLabel.setText("Devam ediliyor...");
                startButton.setEnabled(false);
                continueButton.setEnabled(false);
                // Kaldığı yerden devam etmek için processAllFiles'ı tekrar çağır
                new Thread(() -> {
                    processAllFiles();
                }).start();
            } else if (choice == 1) { // Seçili dosyadan başla
                isPaused.set(false);
                currentIncorrectWordIndex = 0;
                pausedIncorrectWordIndex = 0; // Sıfırla
                statusLabel.setText("Secili dosyadan basliyor...");
                startButton.setEnabled(false);
                continueButton.setEnabled(false);
                new Thread(() -> {
                    processAllFiles();
                }).start();
            }
            // choice == 2 ise iptal, hiçbir şey yapma
        }
    }
    
    private void showEliminatedWordsDialog() {
        JDialog dialog = new JDialog(this, "Elenen Kelimeler", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(500, 400);
        dialog.setLocationRelativeTo(this);

        DefaultListModel<String> listModel = new DefaultListModel<>();
        // En son eklenen en başta olacak şekilde listele
        List<String> wordsList = new ArrayList<>(eliminatedWords);
        Collections.reverse(wordsList);
        for (String word : wordsList) {
            listModel.addElement(word);
        }

        JList<String> list = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(list);

        JButton addButton = new JButton("Kelime Ekle");
        addButton.addActionListener(e -> {
            String newWord = JOptionPane.showInputDialog(dialog, "Eklenecek kelimeyi girin:", "Kelime Ekle", JOptionPane.PLAIN_MESSAGE);
            if (newWord != null && !newWord.trim().isEmpty()) {
                String trimmedWord = newWord.trim();
                if (eliminatedWords.add(trimmedWord)) {
                    listModel.clear();
                    // En son eklenen en başta olacak şekilde listele
                    List<String> newWordsList = new ArrayList<>(eliminatedWords);
                    Collections.reverse(newWordsList);
                    for (String word : newWordsList) {
                        listModel.addElement(word);
                    }
                    saveEliminatedWordsIfEnabled();
                } else {
                    JOptionPane.showMessageDialog(dialog, "Bu kelime zaten listede mevcut.", "Uyari", JOptionPane.WARNING_MESSAGE);
                }
            }
        });

        JButton removeButton = new JButton("Listeden Cikar");
        removeButton.addActionListener(e -> {
            String selectedWord = list.getSelectedValue();
            if (selectedWord != null) {
                eliminatedWords.remove(selectedWord);
                listModel.removeElement(selectedWord);
                saveEliminatedWordsIfEnabled();
            } else {
                JOptionPane.showMessageDialog(dialog, "Lutfen listeden bir kelime secin.", "Uyari", JOptionPane.WARNING_MESSAGE);
            }
        });

        JButton clearButton = new JButton("Listeyi Temizle");
        clearButton.addActionListener(e -> {
            int result = JOptionPane.showConfirmDialog(dialog, 
                "Tum elenen kelimeleri silmek istediginizden emin misiniz?", 
                "Listeyi Temizle", 
                JOptionPane.YES_NO_OPTION, 
                JOptionPane.WARNING_MESSAGE);
            
            if (result == JOptionPane.YES_OPTION) {
                eliminatedWords.clear();
                listModel.clear();
                saveEliminatedWordsIfEnabled();
                JOptionPane.showMessageDialog(dialog, "Liste basariyla temizlendi.", "Basarili", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        JButton exportButton = new JButton("Export");
        exportButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser(new File("Elenenler"));
            fileChooser.setDialogTitle("Elenen Kelimeleri Disa Aktar");
            fileChooser.setFileFilter(new FileNameExtensionFilter("Metin Dosyalari (*.txt)", "txt"));
            
            int userSelection = fileChooser.showSaveDialog(dialog);
            if (userSelection == JFileChooser.APPROVE_OPTION) {
                File fileToSave = fileChooser.getSelectedFile();
                if (!fileToSave.getName().toLowerCase().endsWith(".txt")) {
                    fileToSave = new File(fileToSave.getParentFile(), fileToSave.getName() + ".txt");
                }

                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fileToSave), "UTF-8"))) {
                    // En son eklenen en başta olacak şekilde export et
                    List<String> exportWordsList = new ArrayList<>(eliminatedWords);
                    Collections.reverse(exportWordsList);
                    for (String word : exportWordsList) {
                        writer.write(word);
                        writer.newLine();
                    }
                    JOptionPane.showMessageDialog(dialog, "Elenen kelimeler basariyla disari aktarildi: " + fileToSave.getName(), "Basarili", JOptionPane.INFORMATION_MESSAGE);
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(dialog, "Dosya yazilirken hata olustu: " + ex.getMessage(), "Hata", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JButton importButton = new JButton("Import");
        importButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser(new File("Elenenler"));
            fileChooser.setDialogTitle("Elenen Kelimeleri Ice Aktar");
            fileChooser.setFileFilter(new FileNameExtensionFilter("Metin Dosyalari (*.txt)", "txt"));

            int userSelection = fileChooser.showOpenDialog(dialog);
            if (userSelection == JFileChooser.APPROVE_OPTION) {
                File fileToOpen = fileChooser.getSelectedFile();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(fileToOpen), "UTF-8"))) {
                    String line;
                    int count = 0;
                    while ((line = reader.readLine()) != null) {
                        String word = line.trim();
                        if (!word.isEmpty() && eliminatedWords.add(word)) {
                           count++;
                        }
                    }
                    
                    listModel.clear();
                    // En son eklenen en başta olacak şekilde listele
                    List<String> importWordsList = new ArrayList<>(eliminatedWords);
                    Collections.reverse(importWordsList);
                    for (String word : importWordsList) {
                        listModel.addElement(word);
                    }
                    saveEliminatedWordsIfEnabled();
                    JOptionPane.showMessageDialog(dialog, count + " yeni kelime iceri aktarildi.", "Basarili", JOptionPane.INFORMATION_MESSAGE);

                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(dialog, "Dosya okunurken hata olustu: " + ex.getMessage(), "Hata", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(addButton);
        buttonPanel.add(importButton);
        buttonPanel.add(exportButton);
        buttonPanel.add(removeButton);
        buttonPanel.add(clearButton);

        dialog.add(scrollPane, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }
    
    private void showSavedWordsDialog() {
        JDialog dialog = new JDialog(this, "Kayitli Kelimeler", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(600, 400);
        dialog.setLocationRelativeTo(this);

        // Tablo modeli oluştur
        String[] columnNames = {"Yanlis Kelime", "Dogru Kelime"};
        Object[][] data = new Object[savedWords.size()][2];
        int i = 0;
        // En son eklenen en üstte olacak şekilde listele
        List<java.util.Map.Entry<String, String>> entries = new ArrayList<>(savedWords.entrySet());
        Collections.reverse(entries);
        for (java.util.Map.Entry<String, String> entry : entries) {
            data[i][0] = entry.getKey();
            data[i][1] = entry.getValue();
            i++;
        }
        
        javax.swing.table.DefaultTableModel tableModel = new javax.swing.table.DefaultTableModel(data, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }
        };
        
        JTable table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(table);

        JButton addButton = new JButton("Kelime Ekle");
        addButton.addActionListener(e -> {
            String wrongWord = JOptionPane.showInputDialog(dialog, "Yanlis kelimeyi girin:", "Kelime Ekle", JOptionPane.PLAIN_MESSAGE);
            if (wrongWord != null && !wrongWord.trim().isEmpty()) {
                String correctWord = JOptionPane.showInputDialog(dialog, "Dogru kelimeyi girin:", "Kelime Ekle", JOptionPane.PLAIN_MESSAGE);
                if (correctWord != null && !correctWord.trim().isEmpty()) {
                    String trimmedWrong = wrongWord.trim();
                    String trimmedCorrect = correctWord.trim();
                    savedWords.put(trimmedWrong, trimmedCorrect);
                    
                    // Tabloyu yeniden oluştur
                    tableModel.setRowCount(0);
                    List<java.util.Map.Entry<String, String>> newEntries = new ArrayList<>(savedWords.entrySet());
                    Collections.reverse(newEntries);
                    for (java.util.Map.Entry<String, String> entry : newEntries) {
                        tableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
                    }
                    
                    saveSavedWordsIfEnabled();
                }
            }
        });

        JButton removeButton = new JButton("Listeden Cikar");
        removeButton.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0) {
                String wrongWord = (String) table.getValueAt(selectedRow, 0);
                savedWords.remove(wrongWord);
                tableModel.removeRow(selectedRow);
                saveSavedWordsIfEnabled();
            } else {
                JOptionPane.showMessageDialog(dialog, "Lutfen listeden bir kelime secin.", "Uyari", JOptionPane.WARNING_MESSAGE);
            }
        });

        JButton clearButton = new JButton("Listeyi Temizle");
        clearButton.addActionListener(e -> {
            int result = JOptionPane.showConfirmDialog(dialog, 
                "Tum kayitli kelimeleri silmek istediginizden emin misiniz?", 
                "Listeyi Temizle", 
                JOptionPane.YES_NO_OPTION, 
                JOptionPane.WARNING_MESSAGE);
            
            if (result == JOptionPane.YES_OPTION) {
                savedWords.clear();
                tableModel.setRowCount(0);
                saveSavedWordsIfEnabled();
                JOptionPane.showMessageDialog(dialog, "Liste basariyla temizlendi.", "Basarili", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        JButton exportButton = new JButton("Export");
        exportButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser(new File("KayitliKelimeler"));
            fileChooser.setDialogTitle("Kayitli Kelimeleri Disa Aktar");
            fileChooser.setFileFilter(new FileNameExtensionFilter("Metin Dosyalari (*.txt)", "txt"));
            
            int userSelection = fileChooser.showSaveDialog(dialog);
            if (userSelection == JFileChooser.APPROVE_OPTION) {
                File fileToSave = fileChooser.getSelectedFile();
                if (!fileToSave.getName().toLowerCase().endsWith(".txt")) {
                    fileToSave = new File(fileToSave.getParentFile(), fileToSave.getName() + ".txt");
                }

                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(fileToSave), "UTF-8"))) {
                    for (java.util.Map.Entry<String, String> entry : savedWords.entrySet()) {
                        writer.write(entry.getKey() + "=" + entry.getValue());
                        writer.newLine();
                    }
                    JOptionPane.showMessageDialog(dialog, "Kayitli kelimeler basariyla disari aktarildi: " + fileToSave.getName(), "Basarili", JOptionPane.INFORMATION_MESSAGE);
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(dialog, "Dosya yazilirken hata olustu: " + ex.getMessage(), "Hata", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JButton importButton = new JButton("Import");
        importButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser(new File("KayitliKelimeler"));
            fileChooser.setDialogTitle("Kayitli Kelimeleri Ice Aktar");
            fileChooser.setFileFilter(new FileNameExtensionFilter("Metin Dosyalari (*.txt)", "txt"));

            int userSelection = fileChooser.showOpenDialog(dialog);
            if (userSelection == JFileChooser.APPROVE_OPTION) {
                File fileToOpen = fileChooser.getSelectedFile();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(fileToOpen), "UTF-8"))) {
                    String line;
                    int count = 0;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty() && line.contains("=")) {
                            String[] parts = line.split("=", 2);
                            if (parts.length == 2) {
                                String wrongWord = parts[0].trim();
                                String correctWord = parts[1].trim();
                                if (!wrongWord.isEmpty() && !correctWord.isEmpty()) {
                                    savedWords.put(wrongWord, correctWord);
                                    count++;
                                }
                            }
                        }
                    }
                    
                    // Tabloyu güncelle
                    tableModel.setRowCount(0);
                    List<java.util.Map.Entry<String, String>> newEntries = new ArrayList<>(savedWords.entrySet());
                    Collections.reverse(newEntries);
                    for (java.util.Map.Entry<String, String> entry : newEntries) {
                        tableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
                    }
                    saveSavedWordsIfEnabled();
                    JOptionPane.showMessageDialog(dialog, count + " yeni kelime iceri aktarildi.", "Basarili", JOptionPane.INFORMATION_MESSAGE);

                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(dialog, "Dosya okunurken hata olustu: " + ex.getMessage(), "Hata", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(addButton);
        buttonPanel.add(importButton);
        buttonPanel.add(exportButton);
        buttonPanel.add(removeButton);
        buttonPanel.add(clearButton);

        dialog.add(scrollPane, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }
    
    private void showWorkspaceDialog() {
        JDialog dialog = new JDialog(this, "Calisma Alanlari", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(500, 400);
        dialog.setLocationRelativeTo(this);

        // Ana panel
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));

        // Üst panel - Yeni çalışma alanı oluştur
        JPanel createPanel = new JPanel(new BorderLayout(5, 5));
        createPanel.setBorder(BorderFactory.createTitledBorder("Yeni Calisma Alani Olustur"));
        
        JTextField workspaceNameField = new JTextField();
        JButton createButton = new JButton("Olustur");
        
        createPanel.add(new JLabel("Calisma Alani Adi:"), BorderLayout.NORTH);
        createPanel.add(workspaceNameField, BorderLayout.CENTER);
        createPanel.add(createButton, BorderLayout.EAST);

        // Orta panel - Mevcut çalışma alanları
        JPanel existingPanel = new JPanel(new BorderLayout(5, 5));
        existingPanel.setBorder(BorderFactory.createTitledBorder("Mevcut Calisma Alanlari"));
        
        // Çalışma alanları listesi
        File calismalarimDir = new File("Calismalarim");
        DefaultListModel<String> workspaceListModel = new DefaultListModel<>();
        JList<String> workspaceList = new JList<>(workspaceListModel);
        JScrollPane workspaceScrollPane = new JScrollPane(workspaceList);
        
        // Çalışma alanlarını listele
        if (calismalarimDir.exists() && calismalarimDir.isDirectory()) {
            File[] workspaceDirs = calismalarimDir.listFiles(File::isDirectory);
            if (workspaceDirs != null) {
                for (File workspaceDir : workspaceDirs) {
                    workspaceListModel.addElement(workspaceDir.getName());
                }
            }
        }
        
        existingPanel.add(workspaceScrollPane, BorderLayout.CENTER);
        
        // Alt panel - Butonlar
        JPanel buttonPanel = new JPanel(new FlowLayout());
        JButton openButton = new JButton("Ac");
        JButton deleteButton = new JButton("Sil");
        JButton closeButton = new JButton("Kapat");
        
        buttonPanel.add(openButton);
        buttonPanel.add(deleteButton);
        buttonPanel.add(closeButton);

        mainPanel.add(createPanel, BorderLayout.NORTH);
        mainPanel.add(existingPanel, BorderLayout.CENTER);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Event listeners
        createButton.addActionListener(e -> {
            String workspaceName = workspaceNameField.getText().trim();
            if (!workspaceName.isEmpty()) {
                createWorkspace(workspaceName, dialog);
                workspaceNameField.setText("");
                // Listeyi yenile
                workspaceListModel.clear();
                if (calismalarimDir.exists() && calismalarimDir.isDirectory()) {
                    File[] workspaceDirs = calismalarimDir.listFiles(File::isDirectory);
                    if (workspaceDirs != null) {
                        for (File workspaceDir : workspaceDirs) {
                            workspaceListModel.addElement(workspaceDir.getName());
                        }
                    }
                }
            } else {
                JOptionPane.showMessageDialog(dialog, "Lutfen bir calisma alani adi giriniz.", "Uyari", JOptionPane.WARNING_MESSAGE);
            }
        });

        openButton.addActionListener(e -> {
            String selectedWorkspace = workspaceList.getSelectedValue();
            if (selectedWorkspace != null) {
                openWorkspace(selectedWorkspace);
                dialog.dispose();
            } else {
                JOptionPane.showMessageDialog(dialog, "Lutfen bir calisma alani seciniz.", "Uyari", JOptionPane.WARNING_MESSAGE);
            }
        });

        deleteButton.addActionListener(e -> {
            String selectedWorkspace = workspaceList.getSelectedValue();
            if (selectedWorkspace != null) {
                int result = JOptionPane.showConfirmDialog(dialog, 
                    "Çalışma alanı '" + selectedWorkspace + "' silinecek. Emin misiniz?", 
                    "Çalışma Alanı Sil", 
                    JOptionPane.YES_NO_OPTION, 
                    JOptionPane.WARNING_MESSAGE);
                
                if (result == JOptionPane.YES_OPTION) {
                    deleteWorkspace(selectedWorkspace);
                    workspaceListModel.removeElement(selectedWorkspace);
                }
            } else {
                JOptionPane.showMessageDialog(dialog, "Lütfen bir çalışma alanı seçin.", "Uyarı", JOptionPane.WARNING_MESSAGE);
            }
        });

        closeButton.addActionListener(e -> dialog.dispose());

        dialog.add(mainPanel);
        dialog.setVisible(true);
    }
    
    private void createWorkspace(String workspaceName, JDialog dialog) {
        JFileChooser fileChooser = new JFileChooser(lastFileChooserDirectory);
        fileChooser.setFileFilter(new FileNameExtensionFilter("Metin Dosyalari", "txt"));
        fileChooser.setMultiSelectionEnabled(true);
        fileChooser.setDialogTitle("Calisma Alani Icin Dosya Secin: " + workspaceName);

        int result = fileChooser.showOpenDialog(this);

        if (result == JFileChooser.APPROVE_OPTION) {
            File[] files = fileChooser.getSelectedFiles();

            if (files.length > 0) {
                lastFileChooserDirectory = files[0].getParentFile();
                saveSettings();
            }

            try {
                // 1. Klasörleri oluştur
                File calismalarimDir = new File("Calismalarim");
                if (!calismalarimDir.exists()) {
                    calismalarimDir.mkdir();
                }
                
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                String workspaceDirName = workspaceName + "_" + timestamp;
                File workspaceDir = new File(calismalarimDir, workspaceDirName);
                workspaceDir.mkdir();
                
                File taranacakDir = new File(workspaceDir, "Taranacak");
                File tarandiDir = new File(workspaceDir, "Tarandi");
                taranacakDir.mkdir();
                tarandiDir.mkdir();

                // 2. Uygulama durumunu yeni çalışma alanına ayarla
                selectedFiles.clear();
                fileListModel.clear();
                currentWorkspaceName = workspaceDirName;
                currentWorkspaceDir = workspaceDir;
                
                // 3. Dosyaları kopyala ve listeleri doldur
                for (int i = 0; i < files.length; i++) {
                    File sourceFile = files[i];
                    File destFile = new File(taranacakDir, sourceFile.getName());
                    Files.copy(sourceFile.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    selectedFiles.add(destFile);
                    fileListModel.addElement((i + 1) + ". " + destFile.getName());
                }

                // 4. Arayüzü güncelle
                updateLeftPanelTitle();
                statusLabel.setText(selectedFiles.size() + " dosya calisma alanina eklendi.");
                
                if (!selectedFiles.isEmpty()) {
                    loadFile(0);
                }

                currentFileIndex = 0;
                currentIncorrectWordIndex = 0;
                pausedIncorrectWordIndex = 0;
                isRunning.set(false);
                isPaused.set(false);
                startButton.setEnabled(true);
                continueButton.setEnabled(false);

                // 5. Çalışma alanı dialogunu kapat
                dialog.dispose();

            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, 
                    "Calisma alani olusturulurken bir hata olustu: " + e.getMessage(), 
                    "Hata", JOptionPane.ERROR_MESSAGE);
            }
        }
        // Eğer kullanıcı iptal ederse, hiçbir işlem yapma. Dialog açık kalır.
    }
    
    private void openWorkspace(String workspaceName) {
        try {
            File workspaceDir = new File("Calismalarim", workspaceName);
            File taranacakDir = new File(workspaceDir, "Taranacak");
            File tarandiDir = new File(workspaceDir, "Tarandi");
            
            if (!taranacakDir.exists()) {
                JOptionPane.showMessageDialog(this, 
                    "Taranacak klasoru bulunamadi.", 
                    "Hata", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            selectedFiles.clear();
            fileListModel.clear();

            java.util.Set<String> fileNames = new java.util.LinkedHashSet<>();
            // Add files from Taranacak
            if (taranacakDir.exists()) {
                File[] files = taranacakDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".txt"));
                if (files != null) {
                    for(File f : files) {
                        fileNames.add(f.getName());
                    }
                }
            }
            // Add files from Tarandi (without duplicates)
            if (tarandiDir.exists()) {
                File[] files = tarandiDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".txt"));
                if (files != null) {
                    for(File f : files) {
                        fileNames.add(f.getName());
                    }
                }
            }

            int fileCounter = 0;
            for (String fileName : fileNames) {
                selectedFiles.add(new File(taranacakDir, fileName)); 
                fileListModel.addElement((fileCounter + 1) + ". " + fileName);
                fileCounter++;
            }
            
            if (!fileNames.isEmpty()) {
                currentWorkspaceName = workspaceName;
                currentWorkspaceDir = workspaceDir;
                
                if (!selectedFiles.isEmpty()) {
                    loadFile(0);
                }
                
                statusLabel.setText(selectedFiles.size() + " dosya yuklendi (Calisma Alani: " + workspaceName + ")");
                updateLeftPanelTitle();
                
                // Reset state for the new workspace
                currentFileIndex = 0;
                currentIncorrectWordIndex = 0;
                pausedIncorrectWordIndex = 0;
                isRunning.set(false);
                isPaused.set(false);
                startButton.setEnabled(true);
                continueButton.setEnabled(false);

                JOptionPane.showMessageDialog(this, 
                    "Calisma alani '" + workspaceName + "' basariyla acildi.", 
                    "Basarili", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, 
                    "Calisma alaninda hic .txt dosyasi bulunamadi.", 
                    "Uyari", JOptionPane.WARNING_MESSAGE);
            }
            
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, 
                "Calisma alani acilirken hata: " + e.getMessage(), 
                "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void deleteWorkspace(String workspaceName) {
        try {
            File workspaceDir = new File("Calismalarim", workspaceName);
            if (workspaceDir.exists()) {
                // Klasörü ve içindekileri sil
                deleteDirectory(workspaceDir);
                JOptionPane.showMessageDialog(this, 
                    "Calisma alani '" + workspaceName + "' basariyla silindi.", 
                    "Basarili", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, 
                "Calisma alani silinirken hata: " + e.getMessage(), 
                "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
    
    private void showSettingsDialog() {
        initializeSettings(); // Ayarları dosyadan yeniden yükleyerek her zaman taze veri göster

        JDialog dialog = new JDialog(this, "Ayarlar", true);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.setSize(600, 400);
        dialog.setLocationRelativeTo(this);

        // Checkbox değişkenlerini final array'lerde tut (lambda expression için)
        final JCheckBox[] autoSaveCheckBoxRef = new JCheckBox[1];
        final JCheckBox[] saveOnlyOnExitCheckBoxRef = new JCheckBox[1];
        final JCheckBox[] autoSaveSavedCheckBoxRef = new JCheckBox[1];
        final JCheckBox[] saveSavedWordsOnlyOnExitCheckBoxRef = new JCheckBox[1];

        // Ana panel - iki border alt alta
        JPanel mainSettingsPanel = new JPanel(new GridLayout(2, 1, 0, 15));

        // Sol panel - Elenenler Listesi Ayarları
        JPanel eliminatedWordsPanel = new JPanel(new GridLayout(3, 1, 10, 10));
        eliminatedWordsPanel.setBorder(BorderFactory.createTitledBorder("Elenenler Listesi Ayarlari"));

        // Auto Load ayari (Elenenler)
        JPanel autoLoadPanel = new JPanel(new BorderLayout(5, 0));
        JLabel autoLoadLabel = new JLabel("Elenenler listesini otomatik yukle:");
        JCheckBox autoLoadCheckBox = new JCheckBox("Uygulama acilirken 'Elenenler/elenenler.txt' dosyasini otomatik yukle");
        autoLoadCheckBox.setSelected(autoLoadEliminatedWords);
        autoLoadCheckBox.addActionListener(e -> autoLoadEliminatedWords = autoLoadCheckBox.isSelected());
        autoLoadPanel.add(autoLoadLabel, BorderLayout.NORTH);
        autoLoadPanel.add(autoLoadCheckBox, BorderLayout.CENTER);

        // Auto Save ayari (Elenenler)
        JPanel autoSavePanel = new JPanel(new BorderLayout(5, 0));
        JLabel autoSaveLabel = new JLabel("Elenenler listesini otomatik kaydet:");
        autoSaveCheckBoxRef[0] = new JCheckBox("Elenenler listesi degistiginde 'Elenenler/elenenler.txt' dosyasina otomatik kaydet");
        autoSaveCheckBoxRef[0].setSelected(autoSaveEliminatedWords);
        autoSaveCheckBoxRef[0].addActionListener(e -> {
            autoSaveEliminatedWords = autoSaveCheckBoxRef[0].isSelected();
            if (autoSaveEliminatedWords) {
                saveOnlyOnExitCheckBoxRef[0].setSelected(false);
                saveOnlyOnExit = false;
            }
        });
        autoSavePanel.add(autoSaveLabel, BorderLayout.NORTH);
        autoSavePanel.add(autoSaveCheckBoxRef[0], BorderLayout.CENTER);

        // Save Only On Exit ayari (Elenenler)
        JPanel saveOnlyOnExitPanel = new JPanel(new BorderLayout(5, 0));
        JLabel saveOnlyOnExitLabel = new JLabel("Sadece uygulama kapanirken kaydet:");
        saveOnlyOnExitCheckBoxRef[0] = new JCheckBox("Elenenler listesi sadece uygulama kapanirken kaydedilsin");
        saveOnlyOnExitCheckBoxRef[0].setSelected(saveOnlyOnExit);
        saveOnlyOnExitCheckBoxRef[0].addActionListener(e -> {
            saveOnlyOnExit = saveOnlyOnExitCheckBoxRef[0].isSelected();
            if (saveOnlyOnExit) {
                autoSaveCheckBoxRef[0].setSelected(false);
                autoSaveEliminatedWords = false;
            }
        });
        saveOnlyOnExitPanel.add(saveOnlyOnExitLabel, BorderLayout.NORTH);
        saveOnlyOnExitPanel.add(saveOnlyOnExitCheckBoxRef[0], BorderLayout.CENTER);

        eliminatedWordsPanel.add(autoLoadPanel);
        eliminatedWordsPanel.add(autoSavePanel);
        eliminatedWordsPanel.add(saveOnlyOnExitPanel);

        // Sağ panel - Kayıtlı Kelimeler Ayarları
        JPanel savedWordsPanel = new JPanel(new GridLayout(3, 1, 10, 10));
        savedWordsPanel.setBorder(BorderFactory.createTitledBorder("Kayitli Kelimeler Ayarlari"));

        // Auto Load ayari (Kayıtlı Kelimeler)
        JPanel autoLoadSavedPanel = new JPanel(new BorderLayout(5, 0));
        JLabel autoLoadSavedLabel = new JLabel("Kayitli kelimeleri otomatik yukle:");
        JCheckBox autoLoadSavedCheckBox = new JCheckBox("Uygulama acilirken 'KayitliKelimeler/kayitlikelimeler.txt' dosyasini otomatik yukle");
        autoLoadSavedCheckBox.setSelected(autoLoadSavedWords);
        autoLoadSavedCheckBox.addActionListener(e -> autoLoadSavedWords = autoLoadSavedCheckBox.isSelected());
        autoLoadSavedPanel.add(autoLoadSavedLabel, BorderLayout.NORTH);
        autoLoadSavedPanel.add(autoLoadSavedCheckBox, BorderLayout.CENTER);

        // Auto Save ayari (Kayıtlı Kelimeler)
        JPanel autoSaveSavedPanel = new JPanel(new BorderLayout(5, 0));
        JLabel autoSaveSavedLabel = new JLabel("Kayitli kelimeleri otomatik kaydet:");
        autoSaveSavedCheckBoxRef[0] = new JCheckBox("Kayitli kelimeler degistiginde 'KayitliKelimeler/kayitlikelimeler.txt' dosyasina otomatik kaydet");
        autoSaveSavedCheckBoxRef[0].setSelected(autoSaveSavedWords);
        autoSaveSavedCheckBoxRef[0].addActionListener(e -> {
            autoSaveSavedWords = autoSaveSavedCheckBoxRef[0].isSelected();
            if (autoSaveSavedWords) {
                saveSavedWordsOnlyOnExitCheckBoxRef[0].setSelected(false);
                saveSavedWordsOnlyOnExit = false;
            }
        });
        autoSaveSavedPanel.add(autoSaveSavedLabel, BorderLayout.NORTH);
        autoSaveSavedPanel.add(autoSaveSavedCheckBoxRef[0], BorderLayout.CENTER);

        // Save Only On Exit ayari (Kayıtlı Kelimeler)
        JPanel saveSavedWordsOnlyOnExitPanel = new JPanel(new BorderLayout(5, 0));
        JLabel saveSavedWordsOnlyOnExitLabel = new JLabel("Sadece uygulama kapanirken kaydet:");
        saveSavedWordsOnlyOnExitCheckBoxRef[0] = new JCheckBox("Kayitli kelimeler sadece uygulama kapanirken kaydedilsin");
        saveSavedWordsOnlyOnExitCheckBoxRef[0].setSelected(saveSavedWordsOnlyOnExit);
        saveSavedWordsOnlyOnExitCheckBoxRef[0].addActionListener(e -> {
            saveSavedWordsOnlyOnExit = saveSavedWordsOnlyOnExitCheckBoxRef[0].isSelected();
            if (saveSavedWordsOnlyOnExit) {
                autoSaveSavedCheckBoxRef[0].setSelected(false);
                autoSaveSavedWords = false;
            }
        });
        saveSavedWordsOnlyOnExitPanel.add(saveSavedWordsOnlyOnExitLabel, BorderLayout.NORTH);
        saveSavedWordsOnlyOnExitPanel.add(saveSavedWordsOnlyOnExitCheckBoxRef[0], BorderLayout.CENTER);

        savedWordsPanel.add(autoLoadSavedPanel);
        savedWordsPanel.add(autoSaveSavedPanel);
        savedWordsPanel.add(saveSavedWordsOnlyOnExitPanel);

        mainSettingsPanel.add(eliminatedWordsPanel);
        mainSettingsPanel.add(savedWordsPanel);

        JButton saveButton = new JButton("Kaydet");
        saveButton.addActionListener(e -> {
            settings.setProperty("autoLoadEliminatedWords", String.valueOf(autoLoadEliminatedWords));
            settings.setProperty("autoSaveEliminatedWords", String.valueOf(autoSaveEliminatedWords));
            settings.setProperty("saveOnlyOnExit", String.valueOf(saveOnlyOnExit));
            settings.setProperty("autoLoadSavedWords", String.valueOf(autoLoadSavedWords));
            settings.setProperty("autoSaveSavedWords", String.valueOf(autoSaveSavedWords));
            settings.setProperty("saveSavedWordsOnlyOnExit", String.valueOf(saveSavedWordsOnlyOnExit));
            settings.setProperty("lastFileChooserDirectory", lastFileChooserDirectory.getAbsolutePath());
            
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(settingsFile), "UTF-8"))) {
                // Manuel olarak ayarları istenen sırayla yaz
                writer.write("#Turkish Spell Checker Settings");
                writer.newLine();
                writer.write("#" + new java.util.Date().toString());
                writer.newLine();
                writer.write("autoLoadEliminatedWords=" + String.valueOf(autoLoadEliminatedWords));
                writer.newLine();
                writer.write("autoSaveEliminatedWords=" + String.valueOf(autoSaveEliminatedWords));
                writer.newLine();
                writer.write("saveOnlyOnExit=" + String.valueOf(saveOnlyOnExit));
                writer.newLine();
                writer.write("autoLoadSavedWords=" + String.valueOf(autoLoadSavedWords));
                writer.newLine();
                writer.write("autoSaveSavedWords=" + String.valueOf(autoSaveSavedWords));
                writer.newLine();
                writer.write("saveSavedWordsOnlyOnExit=" + String.valueOf(saveSavedWordsOnlyOnExit));
                writer.newLine();
                writer.write("lastFileChooserDirectory=" + lastFileChooserDirectory.getAbsolutePath());
                writer.newLine();
                
                JOptionPane.showMessageDialog(dialog, "Ayarlar basariyla kaydedildi!", "Basarili", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(dialog, "Ayarlar kaydedilirken hata olustu: " + ex.getMessage(), "Hata", JOptionPane.ERROR_MESSAGE);
            }
            dialog.dispose();
        });

        JButton closeButton = new JButton("Iptal");
        closeButton.addActionListener(e -> dialog.dispose());

        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(saveButton);
        buttonPanel.add(closeButton);

        dialog.add(mainSettingsPanel, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }
    
    private void showGuideDialog(boolean isStartupCall) {
        JDialog dialog = new JDialog(this, "Kullanim Klavuzu", true);
        dialog.setSize(600, 500);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));

        String guideText = "<html>"
                + "<body>"
                + "<h1>Uygulama Kullanim Klavuzu</h1>"
                + "<p>Bu klavuz, uygulamanin ozelliklerini ve butonlarin islevlerini aciklamaktadir.</p>"
                + "<h2>Ana Butonlar</h2>"
                + "<ul>"
                + "<li><b>Dosya Sec:</b> Bilgisayarinizdan yazim denetimi yapilacak `.txt` uzantili dosyalari secmek icin kullanilir. Eger bir calisma alani aktif ise, bu butona basmak calisma alanini kapatir ve normal moda donulmesini saglar.</li>"
                + "<li><b>Calismalarim:</b> Projelerinizi yonetmek icin calisma alanlari olusturmanizi, acmanizi ve silmenizi saglar. Her calisma alani kendi dosya yapisina sahiptir.</li>"
                + "<li><b>Yazim Denetimi Baslat:</b> Secilen dosyalar icin yazim denetimi surecini baslatir.</li>"
                + "<li><b>Devam Et:</b> Duraklatilmis bir yazim denetimi islemine kaldigi yerden devam eder.</li>"
                + "<li><b>Elenenler:</b> Yazim denetimi sirasinda 'Ele' dediginiz kelimelerin listesini yonetmenizi saglar. Bu kelimeler hatali olarak isaretlenmez.</li>"
                + "<li><b>Kayitli Kelimeler:</b> Yanlis bir kelime icin yaptiginiz duzeltmeleri kaydeder. Ayni yanlis kelime tekrar bulundugunda otomatik olarak duzeltilir.</li>"
                + "<li><b>Ayarlar:</b> Elenen ve kayitli kelime listelerinin otomatik yuklenmesi/kaydedilmesi gibi uygulama ayarlarini yapilandirir.</li>"
                + "<li><b>Klavuz:</b> Bu yardim penceresini acar.</li>"
                + "</ul>"
                + "<h2>Yazim Hatasi Duzeltme Ekrani</h2>"
                + "<p>Bir yazim hatasi bulundugunda acilan penceredeki butonlarin islevleri:</p>"
                + "<ul>"
                + "<li><b>Oneriyi Kabul Et:</b> Listeden secilen oneriyi hatali kelimeye uygular.</li>"
                + "<li><b>Gormezden Gel:</b> Kelimeyi o an icin dogru kabul eder ve bir sonraki hataya gecer.</li>"
                + "<li><b>Ele:</b> Kelimeyi 'Elenenler' listesine ekler. Bu kelime uygulama boyunca bir daha hata olarak gosterilmez.</li>"
                + "<li><b>Ozel Duzeltmeyi Kullan:</b> Metin kutusuna yazdiginiz yeni kelimeyi duzeltme olarak uygular.</li>"
                + "<li><b>Bu Dosyaya Uygula:</b> Duzeltmeyi, sadece o an acik olan dosyadaki tum ayni hatali kelimeler icin uygular.</li>"
                + "<li><b>Tum Dosyalara Uygula:</b> Duzeltmeyi tum secili dosyalardaki ayni hatali kelimeler icin uygular ve bu duzeltmeyi gelecekteki kullanimlar icin 'Kayitli Kelimeler' listesine ekler.</li>"
                + "<li><b>Geri Al:</b> Bir onceki hatali kelimeye doner ve o kelime icin yapilan duzeltmeyi geri alir.</li>"
                + "<li><b>Pencereyi Kapatmak (Duraklat):</b> Islemi duraklatir. Ana ekrandaki 'Devam Et' butonu ile devam edebilirsiniz.</li>"
                + "</ul>"
                + "<h2>Dosya ve Klasor Yapisi</h2>"
                + "<h3>Normal Mod</h3>"
                + "<p>Bir calisma alani aktif degilken, sonuclar ana uygulama dizinindeki klasorlere kaydedilir:</p>"
                + "<ul>"
                + "<li><b>Sonuc:</b> Duzeltilmis metinlerin gecici olarak tutuldugu yerdir.</li>"
                + "<li><b>Duzeltmeler:</b> Her dosya icin yapilan degisikliklerin ayrintili loglarini icerir.</li>"
                + "</ul>"
                + "<h3>Calisma Alani Modu</h3>"
                + "<p>Bir calisma alani actiginizda veya olusturdugunuzda, tum dosyalariniz o alana ozel klasorlerde tutulur:</p>"
                + "<ul>"
                + "<li><b>Taranacak:</b> Yazim denetimi yapilacak dosyalarin kopyalandigi yerdir.</li>"
                + "<li><b>Sonuc:</b> Duzeltilmis metinlerin gecici olarak tutuldugu yerdir.</li>"
                + "<li><b>Tarandi:</b> Islemi tamamen biten dosyalarin `Taranacak` klasorunden tasindigi son konumdur.</li>"
                + "<li><b>Duzeltmeler:</b> O calisma alanina ozel duzeltme loglarini icerir.</li>"
                + "</ul>"
                + "</body>"
                + "</html>";

        JEditorPane editorPane = new JEditorPane("text/html", guideText);
        editorPane.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(editorPane);
        
        // Ensure the scroll pane starts at the top
        SwingUtilities.invokeLater(() -> scrollPane.getViewport().setViewPosition(new Point(0, 0)));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton closeButton = new JButton("Kapat");
        closeButton.addActionListener(e -> dialog.dispose());

        if (isStartupCall) {
            JButton dontShowAgainButton = new JButton("Anladim, Bir Daha Gosterme");
            dontShowAgainButton.addActionListener(e -> {
                showGuideOnStartup = false;
                saveSettings();
                dialog.dispose();
            });
            buttonPanel.add(dontShowAgainButton);
        }

        buttonPanel.add(closeButton);

        dialog.add(scrollPane, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }
    
    private void saveSettings() {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(settingsFile), "UTF-8"))) {
            // Update settings object before writing
            settings.setProperty("autoLoadEliminatedWords", String.valueOf(autoLoadEliminatedWords));
            settings.setProperty("autoSaveEliminatedWords", String.valueOf(autoSaveEliminatedWords));
            settings.setProperty("saveOnlyOnExit", String.valueOf(saveOnlyOnExit));
            settings.setProperty("autoLoadSavedWords", String.valueOf(autoLoadSavedWords));
            settings.setProperty("autoSaveSavedWords", String.valueOf(autoSaveSavedWords));
            settings.setProperty("saveSavedWordsOnlyOnExit", String.valueOf(saveSavedWordsOnlyOnExit));
            settings.setProperty("showGuideOnStartup", String.valueOf(showGuideOnStartup));
            settings.setProperty("lastFileChooserDirectory", lastFileChooserDirectory.getAbsolutePath());

            // Manuel olarak ayarları istenen sırayla yaz
            writer.write("# Turkish Spell Checker Settings");
            writer.newLine();
            writer.write("# " + new java.util.Date().toString());
            writer.newLine();
            writer.write("autoLoadEliminatedWords=" + settings.getProperty("autoLoadEliminatedWords"));
            writer.newLine();
            writer.write("autoSaveEliminatedWords=" + settings.getProperty("autoSaveEliminatedWords"));
            writer.newLine();
            writer.write("saveOnlyOnExit=" + settings.getProperty("saveOnlyOnExit"));
            writer.newLine();
            writer.write("autoLoadSavedWords=" + settings.getProperty("autoLoadSavedWords"));
            writer.newLine();
            writer.write("autoSaveSavedWords=" + settings.getProperty("autoSaveSavedWords"));
            writer.newLine();
            writer.write("saveSavedWordsOnlyOnExit=" + settings.getProperty("saveSavedWordsOnlyOnExit"));
            writer.newLine();
            writer.write("showGuideOnStartup=" + settings.getProperty("showGuideOnStartup"));
            writer.newLine();
            writer.write("lastFileChooserDirectory=" + lastFileChooserDirectory.getAbsolutePath());
            writer.newLine();
        } catch (IOException e) {
            System.err.println("Ayarlar kaydedilirken hata: " + e.getMessage());
        }
    }
    
    public static void main(String[] args) {
        // UTF-8 encoding ayarları
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.jnu.encoding", "UTF-8");
        
        // Swing uygulamasını EDT'de çalıştır
        SwingUtilities.invokeLater(() -> {
            try {
                // Türkçe karakterler için uygun font ayarları
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                
                // Font ayarları
                UIManager.put("Button.font", new Font("Arial", Font.PLAIN, 12));
                UIManager.put("Label.font", new Font("Arial", Font.PLAIN, 12));
                UIManager.put("TextField.font", new Font("Arial", Font.PLAIN, 12));
                UIManager.put("TextArea.font", new Font("Arial", Font.PLAIN, 12));
                UIManager.put("List.font", new Font("Arial", Font.PLAIN, 12));
            } catch (Exception e) {
                e.printStackTrace();
            }
            
            new TurkishSpellCheckerSimple().setVisible(true);
        });
    }
} 