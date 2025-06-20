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
    private JButton startButton;
    private JButton continueButton;
    private JButton elenenlerButton;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JList<String> fileList;
    private DefaultListModel<String> fileListModel;
    
    private java.util.Set<String> eliminatedWords = new java.util.HashSet<>();
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
    
    // Geri Al özelliği için eylem tanımları
    private enum UserAction { ACCEPT, IGNORE, PAUSE, ELIMINATE, UNDO, CUSTOM, STOP }
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
        
        initializeComponents();
        initializeZemberek();
        createDirectories();
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
        selectFilesButton = new JButton("Dosyalar Sec");
        startButton = new JButton("Yazim Denetimi Baslat");
        continueButton = new JButton("Devam Et");
        elenenlerButton = new JButton("Elenenler");
        
        topPanel.add(selectFilesButton);
        topPanel.add(startButton);
        topPanel.add(continueButton);
        topPanel.add(elenenlerButton);
        
        // Sol panel - Dosya listesi
        JPanel leftPanel = new JPanel(new BorderLayout());
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
        selectFilesButton.addActionListener(e -> selectFiles());
        startButton.addActionListener(e -> startSpellChecking());
        continueButton.addActionListener(e -> continueSpellChecking());
        elenenlerButton.addActionListener(e -> showEliminatedWordsDialog());
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
            Files.createDirectories(Paths.get("Tarananlar"));
            Files.createDirectories(Paths.get("Duzeltmeler"));
            Files.createDirectories(Paths.get("Elenenler"));
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, 
                "Klasorler olusturulurken hata: " + e.getMessage(),
                "Hata", JOptionPane.ERROR_MESSAGE);
        }
    }
    
    private void selectFiles() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("Metin Dosyalari", "txt"));
        fileChooser.setMultiSelectionEnabled(true);
        
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File[] files = fileChooser.getSelectedFiles();
            selectedFiles.clear();
            fileListModel.clear();
            
            for (int i = 0; i < files.length; i++) {
                selectedFiles.add(files[i]);
                // Numaralı dosya listesi: "1. dosyaadi.txt"
                fileListModel.addElement((i + 1) + ". " + files[i].getName());
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
        }
    }
    
    private void loadFile(int index) {
        if (index >= 0 && index < selectedFiles.size()) {
            currentFileIndex = index;
            File file = selectedFiles.get(index);
            
            try {
                String content = new String(Files.readAllBytes(file.toPath()), "UTF-8");
                inputArea.setText(content);
                
                // Düzeltilmiş dosyayı kontrol et ve yükle
                String correctedFilePath = "Tarananlar/" + file.getName();
                File correctedFile = new File(correctedFilePath);
                if (correctedFile.exists()) {
                    String correctedContent = new String(Files.readAllBytes(correctedFile.toPath()), "UTF-8");
                    outputArea.setText(correctedContent);
                } else {
                    outputArea.setText("");
                }
                
                fileList.setSelectedIndex(index);
                fileList.ensureIndexIsVisible(index);
                statusLabel.setText("Dosya yuklendi: " + file.getName() + " (" + (index + 1) + "/" + selectedFiles.size() + ")");
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
        for (int i = currentFileIndex; i < selectedFiles.size() && isRunning.get(); i++) {
            final int fileIndex = i; // Final variable for lambda
            currentFileIndex = i;
            File currentFile = selectedFiles.get(i);
            
            SwingUtilities.invokeLater(() -> {
                loadFile(fileIndex);
                statusLabel.setText("Isleniyor: " + currentFile.getName() + " (" + (fileIndex + 1) + "/" + selectedFiles.size() + ")");
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
                String correctedFileName = "Tarananlar/" + currentFile.getName();
                Files.write(Paths.get(correctedFileName), correctedContent.getBytes("UTF-8"));
                
                // Bu dosya için düzeltmeleri ayrı dosyaya kaydet (sadece düzeltme varsa)
                if (fileCorrectionsLog.length() > 0) {
                    try {
                        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                        String baseFileName = currentFile.getName().replaceFirst("[.][^.]+$", ""); // Uzantıyı kaldır
                        String correctionsFileName = "Duzeltmeler/" + baseFileName + "_duzeltmesi_" + timestamp + ".txt";
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
                incorrectTokenIndices.add(i);
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
                case IGNORE:
                    // Değişiklik yapma, orijinal kelime kalır
                    break;
                case ELIMINATE:
                    eliminatedWords.add(wrongWord);
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
            
            JPanel buttonPanel = new JPanel(new FlowLayout());
            JButton acceptButton = new JButton("Oneriyi Kabul Et");
            JButton ignoreButton = new JButton("Gormezden Gel");
            JButton pauseButton = new JButton("Duraklat");
            JButton eliminateButton = new JButton("Ele");
            
            acceptButton.setEnabled(!suggestions.isEmpty());
            
            buttonPanel.add(acceptButton);
            buttonPanel.add(ignoreButton);
            buttonPanel.add(pauseButton);
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
            
            pauseButton.addActionListener(e -> {
                resultWrapper[0] = new CorrectionResult(UserAction.PAUSE, null);
                dialog.dispose();
                latch.countDown();
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Duraklatildi - Devam Et butonuna basin");
                    startButton.setEnabled(false);
                    continueButton.setEnabled(true);
                });
            });

            eliminateButton.addActionListener(e -> {
                resultWrapper[0] = new CorrectionResult(UserAction.ELIMINATE, wrongWord);
                dialog.dispose();
                latch.countDown();
            });
            
            customField.addActionListener(e -> useCustomButton.doClick());
            
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    // Pencereyi kapatmak da yoksaymakla aynı anlama gelsin
                    resultWrapper[0] = new CorrectionResult(UserAction.IGNORE, wrongWord);
                    dialog.dispose();
                    latch.countDown();
                }
            });
            
            JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
            centerPanel.add(topPanel, BorderLayout.NORTH); // Bilgi ve Geri Al butonu
            centerPanel.add(suggestionsPanel, BorderLayout.CENTER);
            centerPanel.add(customPanel, BorderLayout.SOUTH);
            
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
        java.util.List<String> sortedWords = new ArrayList<>(eliminatedWords);
        java.util.Collections.sort(sortedWords);
        for (String word : sortedWords) {
            listModel.addElement(word);
        }

        JList<String> list = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(list);

        JButton removeButton = new JButton("Listeden Cikar");
        removeButton.addActionListener(e -> {
            String selectedWord = list.getSelectedValue();
            if (selectedWord != null) {
                eliminatedWords.remove(selectedWord);
                listModel.removeElement(selectedWord);
            } else {
                JOptionPane.showMessageDialog(dialog, "Lutfen listeden bir kelime secin.", "Uyari", JOptionPane.WARNING_MESSAGE);
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
                    for (String word : eliminatedWords) {
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
                    java.util.List<String> newSortedWords = new ArrayList<>(eliminatedWords);
                    java.util.Collections.sort(newSortedWords);
                    for (String word : newSortedWords) {
                        listModel.addElement(word);
                    }
                    JOptionPane.showMessageDialog(dialog, count + " yeni kelime iceri aktarildi.", "Basarili", JOptionPane.INFORMATION_MESSAGE);

                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(dialog, "Dosya okunurken hata olustu: " + ex.getMessage(), "Hata", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JButton closeButton = new JButton("Kapat");
        closeButton.addActionListener(e -> dialog.dispose());

        JPanel buttonPanel = new JPanel(new FlowLayout());
        buttonPanel.add(importButton);
        buttonPanel.add(exportButton);
        buttonPanel.add(removeButton);
        buttonPanel.add(closeButton);

        dialog.add(scrollPane, BorderLayout.CENTER);
        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
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