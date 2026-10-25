package dev.vapor.gui;

import dev.vapor.Main;
import dev.vapor.Obfuscator;
import dev.vapor.config.VaporConfig;
import dev.vapor.pipeline.PipelineListener;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Vapor desktop front-end.
 *
 * <p>The GUI intentionally reuses {@link Obfuscator}, the same entry point as
 * the CLI. The worker runs in-process, captures the pipeline's console output
 * into the log tab, and writes to temporary files which are moved into place
 * only after a successful run, so a failed or cancelled job cannot clobber an
 * existing output jar.</p>
 */
public final class VaporGui extends JFrame {

    private static final Color ACCENT = new Color(92, 76, 210);
    private static final Color MUTED = new Color(105, 109, 124);
    private static final Color LINE = new Color(214, 216, 228);
    private static final Color LOG_BG = new Color(23, 25, 34);
    private static final Color LOG_FG = new Color(226, 230, 241);
    private static final int MAX_LOG_CHARS = 1_500_000;

    private final JTextField inputField = new JTextField();
    private final JTextField outputField = new JTextField();
    private final JTextField mappingField = new JTextField();
    private final JTextField seedField = new JTextField("0");
    private final JTextField watermarkField = new JTextField();
    private final JTextArea keepArea = monospacedArea(4);
    private final JTextArea logArea = new JTextArea();
    private final JCheckBox autoScroll = new JCheckBox("Auto-scroll", true);
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel("Ready");
    private final JLabel configSourceLabel = new JLabel("Defaults");
    private final JButton runButton = new JButton("Obfuscate");
    private final JButton cancelButton = new JButton("Cancel");
    private final JButton openOutputButton = new JButton("Open output folder");
    private final DefaultListModel<Path> libraryModel = new DefaultListModel<>();
    private final JList<Path> libraryList = new JList<>(libraryModel);

    private final JCheckBox renameClassesBox = new JCheckBox("Rename classes", true);
    private final JCheckBox renameMethodsBox = new JCheckBox("Rename methods", true);
    private final JCheckBox renameFieldsBox = new JCheckBox("Rename fields", true);
    private final JCheckBox stripLineNumbersBox = new JCheckBox("Strip line numbers", true);
    private final JCheckBox stripLocalVariablesBox = new JCheckBox("Strip local variable tables", true);
    private final JCheckBox stripMethodParametersBox = new JCheckBox("Strip method parameters", true);
    private final JCheckBox stripSignaturesBox = new JCheckBox("Strip generic signatures", true);
    private final JCheckBox stripSourceFileBox = new JCheckBox("Strip source file / debug info", true);
    private final JCheckBox encryptStringsBox = new JCheckBox("Encrypt strings", true);
    private final JCheckBox deadCodeBox = new JCheckBox("Dead-code injection", true);
    private final JCheckBox intObfuscationBox = new JCheckBox("Numeric constant obfuscation", true);
    private final JCheckBox flowShuffleBox = new JCheckBox("Control-flow noise", true);
    private final JCheckBox flowFlattenBox = new JCheckBox("Control-flow flattening", true);
    private final JCheckBox virtualizeBox = new JCheckBox("Int-only method virtualization", false);
    private final JCheckBox obfuscateRecordsBox = new JCheckBox("Obfuscate record components", false);
    private final JCheckBox stripAnnotationsBox = new JCheckBox("Strip annotations", false);
    private final JCheckBox failsafeBox = new JCheckBox("Failsafe: skip a failing transform", true);
    private final JCheckBox polymorphicBox = new JCheckBox("Polymorphic decryptors", true);
    private final JCheckBox stringPoolBox = new JCheckBox("Per-class string pool", true);
    private final JCheckBox constantValuesBox = new JCheckBox("Encrypt ConstantValue strings", true);
    private final JCheckBox splitSingleBlockBox = new JCheckBox("Split single-block methods", true);
    private final JCheckBox encryptClassesBox = new JCheckBox("Encrypt classes (executable jars)", false);
    private final JCheckBox verifyIntegrityBox = new JCheckBox("Verify class integrity", true);

    private final JSpinner minLengthSpinner = intSpinner(0, 0, 100_000, 1);
    private final JSpinner maxOverheadSpinner = doubleSpinner(2.0, 0.0, 1_000.0, 0.1);
    private final JSpinner deadBranchesSpinner = intSpinner(3, 0, 10_000, 1);
    private final JSpinner flowSplitsSpinner = intSpinner(6, 0, 100_000, 1);
    private final JSpinner flattenBlocksSpinner = intSpinner(128, 3, 1_000_000, 1);

    private final AtomicBoolean cancelFlag = new AtomicBoolean();
    private final Consumer<String> workerLog =
            line -> SwingUtilities.invokeLater(() -> appendLog(line));
    private final PipelineListener pipelineListener = new PipelineListener() {
        @Override
        public void stageStarted(String stage, String name, int index, int total) {
            SwingUtilities.invokeLater(() -> {
                progress.setIndeterminate(false);
                progress.setMaximum(Math.max(1, total));
                progress.setValue(Math.max(0, index - 1));
                progress.setString((index - 1) + "/" + total);
                statusLabel.setText(stageLabel(stage) + " — " + name + " (" + index + "/" + total + ")");
            });
        }

        @Override
        public void stageFinished(String stage, String name, int index, int total, double millis) {
            SwingUtilities.invokeLater(() -> {
                progress.setMaximum(Math.max(1, total));
                progress.setValue(index);
                progress.setString(index + "/" + total);
            });
        }
    };

    private JTabbedPane tabs;
    private JPanel jobPanel;
    private JPanel optionsPanel;
    private SwingWorker<Obfuscator.Result, Void> worker;
    private Path lastDir = Path.of("").toAbsolutePath();
    private Path lastOutput;
    private Path tempOutput;
    private Path tempMapping;
    private Path pendingOutput;
    private Path pendingMapping;
    private boolean busy;
    private boolean closingAfterCancel;

    private VaporGui() {
        super("Vapor " + Main.VERSION + " — Java Obfuscator");
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setIconImage(createAppIcon(64));
        setLayout(new BorderLayout());
        setMinimumSize(new Dimension(880, 680));

        jobPanel = buildJobPanel();
        optionsPanel = buildOptionsPanel();
        tabs = new JTabbedPane();
        tabs.addTab("Job", wrapScroll(jobPanel));
        tabs.addTab("Transforms", wrapScroll(optionsPanel));
        tabs.addTab("Log", buildLogPanel());

        add(buildHeader(), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(buildBottomBar(), BorderLayout.SOUTH);
        setJMenuBar(buildMenuBar());
        installShortcuts();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                requestClose();
            }
        });

        appendLog("Vapor " + Main.VERSION + " — desktop");
        appendLog("Choose an input jar, adjust the options, then press Obfuscate.");
        pack();
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(new Dimension(
                Math.max(880, Math.min(1020, screen.width - 80)),
                Math.max(680, Math.min(860, screen.height - 100))));
        setLocationRelativeTo(null);
        getRootPane().setDefaultButton(runButton);
    }

    /** Launch the desktop UI on the Swing event thread. */
    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Keep the cross-platform default if the native L&F is unavailable.
            }
            tuneUiDefaults();
            new VaporGui().setVisible(true);
        });
    }

    private static void tuneUiDefaults() {
        String os = System.getProperty("os.name", "");
        String family = os.contains("Windows") ? "Segoe UI" : Font.SANS_SERIF;
        Font ui = new Font(family, Font.PLAIN, 13);
        UIManager.getLookAndFeelDefaults().put("defaultFont", ui);
        for (String key : List.of("Label.font", "Button.font", "CheckBox.font", "RadioButton.font",
                "TabbedPane.font", "TitledBorder.font", "TextField.font", "TextArea.font",
                "ComboBox.font", "Spinner.font", "Menu.font", "MenuItem.font")) {
            UIManager.put(key, ui);
        }
        UIManager.put("TabbedPane.tabInsets", new Insets(5, 12, 5, 12));
        UIManager.put("TabbedPane.contentBorderInsets", new Insets(1, 1, 1, 1));
    }

    private JComponent buildHeader() {
        HeaderPanel header = new HeaderPanel();
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        left.setOpaque(false);
        left.add(new JLabel(new ImageIcon(createAppIcon(48))));

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Vapor");
        title.setForeground(Color.WHITE);
        title.setFont(new Font("Segoe UI Semibold", Font.BOLD, 27));
        JLabel subtitle = new JLabel("Java bytecode obfuscator  ·  deterministic by default");
        subtitle.setForeground(new Color(220, 221, 244));
        subtitle.setFont(subtitle.getFont().deriveFont(Font.PLAIN, 12f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        titles.add(title);
        titles.add(Box.createVerticalStrut(2));
        titles.add(subtitle);
        left.add(titles);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        JLabel version = new JLabel("v" + Main.VERSION);
        version.setForeground(new Color(240, 240, 255));
        version.setFont(version.getFont().deriveFont(Font.BOLD, 12f));
        version.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(255, 255, 255, 70), 1, true),
                new EmptyBorder(4, 10, 4, 10)));
        right.add(version);

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JPanel buildJobPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(16, 18, 16, 18));
        GridBagConstraints c = new GridBagConstraints();
        int row = 0;

        addRow(panel, c, row++, "Input jar",
                textWithButton(inputField, button("Browse…", this::chooseInput)));
        addRow(panel, c, row++, "Output jar",
                textWithButton(outputField, button("Browse…", this::chooseOutput)));
        addRow(panel, c, row++, "Mapping file",
                textWithButton(mappingField, button("Browse…", this::chooseMapping)));

        JPanel configActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        configActions.setOpaque(false);
        configActions.add(button("Load config…", this::chooseConfigLoad));
        configActions.add(button("Save config…", this::chooseConfigSave));
        configActions.add(button("Reset defaults", this::resetDefaults));
        configSourceLabel.setForeground(MUTED);
        configSourceLabel.setFont(configSourceLabel.getFont().deriveFont(Font.PLAIN, 11f));
        configSourceLabel.setBorder(new EmptyBorder(0, 8, 0, 0));
        configActions.add(configSourceLabel);
        addRow(panel, c, row++, "Config", configActions);

        addRow(panel, c, row++, "Libraries", buildLibrariesContent());
        addRow(panel, c, row++, "Keep rules", buildKeepContent());

        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 2;
        c.weighty = 1.0;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 0, 0);
        panel.add(new JPanel(), c);
        return panel;
    }

    private JComponent buildLibrariesContent() {
        libraryList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        libraryList.setVisibleRowCount(4);
        libraryList.setFixedCellHeight(22);
        libraryList.setToolTipText("Optional jars or class directories used for hierarchy/override checks.");
        JScrollPane scroll = new JScrollPane(libraryList);
        scroll.setPreferredSize(new Dimension(420, 104));

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.Y_AXIS));
        JButton addJars = button("Add jars…", this::chooseLibraries);
        JButton addFolder = button("Add folder…", this::chooseLibraryFolder);
        JButton remove = button("Remove", this::removeLibraries);
        for (JButton b : List.of(addJars, addFolder, remove)) {
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(128, 28));
            buttons.add(b);
            buttons.add(Box.createVerticalStrut(5));
        }

        JPanel content = new JPanel(new BorderLayout(8, 4));
        content.setOpaque(false);
        content.add(scroll, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.EAST);
        JLabel hint = muted("Optional. Libraries are read for hierarchy checks, never written.");
        content.add(hint, BorderLayout.SOUTH);
        return content;
    }

    private JComponent buildKeepContent() {
        keepArea.setToolTipText("One dotted class glob per line, for example com.example.api.*");
        JScrollPane scroll = new JScrollPane(keepArea);
        scroll.setPreferredSize(new Dimension(420, 92));

        JPanel content = new JPanel(new BorderLayout(0, 4));
        content.setOpaque(false);
        content.add(scroll, BorderLayout.CENTER);
        content.add(muted("One glob per line. Kept classes keep their names and all member names."),
                BorderLayout.SOUTH);
        return content;
    }

    private static JComponent textWithButton(JTextField field, JButton button) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(field, BorderLayout.CENTER);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(button);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    private static void addRow(JPanel panel, GridBagConstraints c, int row,
                               String label, JComponent content) {
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 1;
        c.weightx = 0;
        c.weighty = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(5, 0, 5, 10);
        JLabel name = new JLabel(label);
        name.setPreferredSize(new Dimension(92, 24));
        name.setVerticalAlignment(SwingConstants.TOP);
        panel.add(name, c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(5, 0, 5, 0);
        panel.add(content, c);
    }

    private static JScrollPane wrapScroll(JComponent view) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getViewport().setBackground(view.getBackground());
        return scroll;
    }

    private static JTextArea monospacedArea(int rows) {
        JTextArea area = new JTextArea(rows, 40);
        area.setFont(new Font("Consolas", Font.PLAIN, 12));
        area.setLineWrap(false);
        area.setTabSize(4);
        return area;
    }

    private static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(e -> action.run());
        return button;
    }

    private static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(MUTED);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        return label;
    }

    private JPanel buildOptionsPanel() {
        configureTooltips();
        JPanel root = new JPanel(new GridBagLayout());
        root.setBorder(new EmptyBorder(14, 16, 14, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 0.5;
        c.weighty = 0;
        c.gridx = 0;
        c.gridy = 0;
        c.insets = new Insets(0, 0, 10, 8);
        root.add(section("Renaming",
                checkColumn(renameClassesBox, renameMethodsBox, renameFieldsBox,
                        obfuscateRecordsBox)), c);
        c.gridx = 1;
        c.insets = new Insets(0, 8, 10, 0);
        root.add(section("Code transforms",
                checkColumn(encryptStringsBox, deadCodeBox, intObfuscationBox,
                        flowShuffleBox, flowFlattenBox, virtualizeBox)), c);

        c.gridx = 0;
        c.gridy = 1;
        c.insets = new Insets(0, 0, 10, 8);
        root.add(section("Metadata hygiene",
                checkColumn(stripLineNumbersBox, stripLocalVariablesBox, stripMethodParametersBox,
                        stripSignaturesBox, stripSourceFileBox, stripAnnotationsBox)), c);
        c.gridx = 1;
        c.insets = new Insets(0, 8, 10, 0);
        root.add(section("String encryption", buildStringsContent()), c);

        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.weightx = 1;
        c.insets = new Insets(0, 0, 8, 0);
        root.add(section("Hardening & determinism", buildHardeningContent()), c);

        c.gridy = 3;
        c.weighty = 0;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 8, 0);
        root.add(section("Protection", buildProtectionContent()), c);

        c.gridy = 4;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 0, 0);
        root.add(new JPanel(), c);
        return root;
    }

    private JComponent buildStringsContent() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(new JLabel("Minimum length"));
        minLengthSpinner.setPreferredSize(new Dimension(82, 24));
        row.add(minLengthSpinner);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        panel.add(row);

        polymorphicBox.setOpaque(false);
        polymorphicBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        polymorphicBox.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                polymorphicBox.getPreferredSize().height));
        panel.add(Box.createVerticalStrut(4));
        panel.add(polymorphicBox);

        stringPoolBox.setOpaque(false);
        stringPoolBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        stringPoolBox.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                stringPoolBox.getPreferredSize().height));
        panel.add(Box.createVerticalStrut(2));
        panel.add(stringPoolBox);

        constantValuesBox.setOpaque(false);
        constantValuesBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        constantValuesBox.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                constantValuesBox.getPreferredSize().height));
        panel.add(Box.createVerticalStrut(2));
        panel.add(constantValuesBox);

        JLabel hint = muted("Shorter strings stay in plaintext to avoid tiny call-site overhead.");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, hint.getPreferredSize().height));
        panel.add(Box.createVerticalStrut(5));
        panel.add(hint);
        return panel;
    }

    private JComponent buildHardeningContent() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JPanel numeric = new JPanel(new GridLayout(2, 2, 18, 4));
        numeric.setOpaque(false);
        numeric.add(setting("Max overhead", maxOverheadSpinner));
        numeric.add(setting("Dead branches / method", deadBranchesSpinner));
        numeric.add(setting("Flow edits / method", flowSplitsSpinner));
        numeric.add(setting("Flatten max blocks", flattenBlocksSpinner));
        numeric.setAlignmentX(Component.LEFT_ALIGNMENT);
        numeric.setMaximumSize(new Dimension(760, numeric.getPreferredSize().height));
        panel.add(numeric);
        panel.add(Box.createVerticalStrut(6));

        JPanel textRows = new JPanel(new GridBagLayout());
        textRows.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 10);
        textRows.add(new JLabel("Seed"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 0, 3, 0);
        seedField.setToolTipText("Long dictionary seed; 0 = sequential names, non-zero = deterministic shuffle.");
        textRows.add(seedField, c);

        c.gridx = 0;
        c.gridy = 1;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(3, 0, 3, 10);
        textRows.add(new JLabel("Watermark"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 0, 3, 0);
        watermarkField.setToolTipText("Optional string embedded as a private constant (left readable on purpose).");
        textRows.add(watermarkField, c);
        textRows.setAlignmentX(Component.LEFT_ALIGNMENT);
        textRows.setMaximumSize(new Dimension(Integer.MAX_VALUE, textRows.getPreferredSize().height));
        panel.add(textRows);
        panel.add(Box.createVerticalStrut(6));

        splitSingleBlockBox.setOpaque(false);
        splitSingleBlockBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        splitSingleBlockBox.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                splitSingleBlockBox.getPreferredSize().height));
        panel.add(splitSingleBlockBox);

        failsafeBox.setOpaque(false);
        failsafeBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        failsafeBox.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                failsafeBox.getPreferredSize().height));
        panel.add(Box.createVerticalStrut(2));
        panel.add(failsafeBox);

        JLabel hint = muted("Same input + same config + same seed = byte-identical output.");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, hint.getPreferredSize().height));
        panel.add(Box.createVerticalStrut(4));
        panel.add(hint);
        return panel;
    }

    private JComponent buildProtectionContent() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        encryptClassesBox.setOpaque(false);
        verifyIntegrityBox.setOpaque(false);

        JPanel boxes = new JPanel(new GridLayout(1, 2, 18, 4));
        boxes.setOpaque(false);
        boxes.add(encryptClassesBox);
        boxes.add(verifyIntegrityBox);
        boxes.setAlignmentX(Component.LEFT_ALIGNMENT);
        boxes.setMaximumSize(new Dimension(760, boxes.getPreferredSize().height));
        panel.add(boxes);

        JLabel hint = muted("Class encryption needs an executable jar (Main-Class); "
                + "integrity checks are stored in the encrypted class header.");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, hint.getPreferredSize().height));
        panel.add(Box.createVerticalStrut(5));
        panel.add(hint);
        return panel;
    }

    private static JPanel setting(String label, JSpinner field) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(new JLabel(label), BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        field.setPreferredSize(new Dimension(82, 24));
        right.add(field);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    private static JPanel section(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        TitledBorder border = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(LINE), " " + title + " ");
        border.setTitleFont(new Font("Segoe UI Semibold", Font.PLAIN, 12));
        border.setTitleColor(new Color(76, 70, 128));
        panel.setBorder(BorderFactory.createCompoundBorder(border, new EmptyBorder(8, 10, 10, 10)));
        content.setOpaque(false);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel checkColumn(JCheckBox... boxes) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        for (JCheckBox box : boxes) {
            box.setOpaque(false);
            box.setAlignmentX(Component.LEFT_ALIGNMENT);
            box.setMaximumSize(new Dimension(Integer.MAX_VALUE, box.getPreferredSize().height));
            panel.add(box);
        }
        return panel;
    }

    private void configureTooltips() {
        renameClassesBox.setToolTipText("Rename classes into the root package.");
        renameMethodsBox.setToolTipText("Rename methods through global name+descriptor keys.");
        renameFieldsBox.setToolTipText("Rename fields while preserving overrides and records.");
        stripLineNumbersBox.setToolTipText("Remove LineNumberTable attributes.");
        stripLocalVariablesBox.setToolTipText("Remove LocalVariableTable / LocalVariableTypeTable.");
        stripMethodParametersBox.setToolTipText("Remove MethodParameters attributes.");
        stripSignaturesBox.setToolTipText("Remove generic Signature attributes.");
        stripSourceFileBox.setToolTipText("Rewrite SourceFile to a and clear SourceDebugExtension.");
        encryptStringsBox.setToolTipText("Encrypt string constants and string-concat recipes.");
        deadCodeBox.setToolTipText("Inject side-effect-free decoy branches at stack-empty points.");
        intObfuscationBox.setToolTipText("Rewrite int/long/float/double constants as keyed expressions.");
        flowShuffleBox.setToolTipText("Split and perturb control flow within the hardening budget.");
        flowFlattenBox.setToolTipText("Reorder basic blocks behind a lookupswitch dispatcher.");
        virtualizeBox.setToolTipText("Translate eligible static int/void methods into a small custom VM. "
                + "Methods with objects, exceptions, calls or switches are left untouched.");
        obfuscateRecordsBox.setToolTipText("Rename record components and rewrite their ObjectMethods metadata "
                + "(requires field and method renaming; can break reflection by component name).");
        stripAnnotationsBox.setToolTipText("Drop runtime-visible/invisible annotations "
                + "(breaks frameworks that reflect on them).");
        failsafeBox.setToolTipText("Skip a transformer that fails instead of aborting the whole run.");
        polymorphicBox.setToolTipText("Vary decryptor math and loop shape per class.");
        stringPoolBox.setToolTipText("Store encoded strings in a per-class pool and decode by integer index.");
        constantValuesBox.setToolTipText("Move static final String ConstantValue fields into an encrypted "
                + "<clinit> assignment (pool mode only).");
        splitSingleBlockBox.setToolTipText("Add an opaque entry branch to straight-line methods so "
                + "control-flow flattening can process them.");
        encryptClassesBox.setToolTipText("Encrypt class entries behind a bootstrap loader. "
                + "Requires a jar with a Main-Class manifest entry.");
        verifyIntegrityBox.setToolTipText("Store a CRC32 of each plaintext class and verify it in the bootstrap "
                + "loader (class encryption only).");
        minLengthSpinner.setToolTipText("Strings shorter than this are left in plaintext.");
        maxOverheadSpinner.setToolTipText("Growth cap: added instructions ≤ (value − 1) × original size.");
        deadBranchesSpinner.setToolTipText("Maximum decoy branches injected per method.");
        flowSplitsSpinner.setToolTipText("Maximum flow-noise edits per method.");
        flattenBlocksSpinner.setToolTipText("Methods with more basic blocks than this stay unflattened.");
    }

    private static JSpinner intSpinner(int value, int min, int max, int step) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        ((JSpinner.NumberEditor) spinner.getEditor()).getTextField().setColumns(7);
        return spinner;
    }

    private static JSpinner doubleSpinner(double value, double min, double max, double step) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "0.0#");
        editor.getTextField().setColumns(7);
        spinner.setEditor(editor);
        return spinner;
    }

    private JComponent buildLogPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(12, 16, 12, 16));

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        toolbar.setOpaque(false);
        toolbar.add(button("Clear", this::clearLog));
        toolbar.add(button("Copy log", this::copyLog));
        autoScroll.setOpaque(false);
        toolbar.add(autoScroll);
        toolbar.add(muted("Pipeline timings and warnings are captured here."));

        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setBackground(LOG_BG);
        logArea.setForeground(LOG_FG);
        logArea.setCaretColor(new Color(180, 190, 255));
        logArea.setSelectionColor(new Color(68, 74, 110));
        logArea.setSelectedTextColor(Color.WHITE);
        logArea.setTabSize(4);
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(new LineBorder(new Color(52, 56, 72)));
        scroll.getViewport().setBackground(LOG_BG);

        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildBottomBar() {
        JPanel outer = new JPanel(new BorderLayout(12, 0));
        outer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, LINE),
                new EmptyBorder(10, 16, 12, 16)));

        statusLabel.setPreferredSize(new Dimension(330, 20));
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.PLAIN, 12f));
        progress.setStringPainted(true);
        progress.setString("");
        progress.setPreferredSize(new Dimension(240, 18));

        JPanel info = new JPanel(new BorderLayout(14, 0));
        info.setOpaque(false);
        info.add(statusLabel, BorderLayout.WEST);
        info.add(progress, BorderLayout.CENTER);
        outer.add(info, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        openOutputButton.setEnabled(false);
        openOutputButton.setToolTipText("Open the directory containing the last successful output.");
        openOutputButton.addActionListener(e -> openOutputFolder());
        cancelButton.setEnabled(false);
        cancelButton.setToolTipText("Stop at the next safe point; the temporary output is deleted.");
        cancelButton.addActionListener(e -> requestCancel());
        runButton.setFont(runButton.getFont().deriveFont(Font.BOLD, 13f));
        runButton.setPreferredSize(new Dimension(142, 34));
        runButton.setToolTipText("Run the same pipeline as the CLI.");
        runButton.addActionListener(e -> startRun());
        buttons.add(openOutputButton);
        buttons.add(cancelButton);
        buttons.add(runButton);
        outer.add(buttons, BorderLayout.EAST);
        return outer;
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();
        JMenu file = new JMenu("File");
        JMenuItem chooseInput = new JMenuItem("Choose input jar…");
        chooseInput.addActionListener(e -> chooseInput());
        JMenuItem chooseOutput = new JMenuItem("Choose output jar…");
        chooseOutput.addActionListener(e -> chooseOutput());
        JMenuItem loadConfig = new JMenuItem("Load config…");
        loadConfig.addActionListener(e -> chooseConfigLoad());
        JMenuItem saveConfig = new JMenuItem("Save config…");
        saveConfig.addActionListener(e -> chooseConfigSave());
        JMenuItem exit = new JMenuItem("Exit");
        exit.addActionListener(e -> requestClose());
        file.add(chooseInput);
        file.add(chooseOutput);
        file.addSeparator();
        file.add(loadConfig);
        file.add(saveConfig);
        file.addSeparator();
        file.add(exit);

        JMenu help = new JMenu("Help");
        JMenuItem about = new JMenuItem("About Vapor");
        about.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "<html><b>Vapor " + Main.VERSION + "</b><br>"
                        + "General-purpose Java bytecode obfuscator.<br><br>"
                        + "The desktop UI and CLI share one deterministic pipeline.<br>"
                        + "CLI: <code>java -jar vapor-" + Main.VERSION + ".jar -i input.jar -o output.jar</code></html>",
                "About Vapor", JOptionPane.INFORMATION_MESSAGE));
        help.add(about);

        bar.add(file);
        bar.add(help);
        return bar;
    }

    private void installShortcuts() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancelRun");
        getRootPane().getActionMap().put("cancelRun", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                requestCancel();
            }
        });
    }

    private void appendLog(String line) {
        if (line == null) {
            return;
        }
        String clean = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        logArea.append(clean);
        logArea.append("\n");
        Document document = logArea.getDocument();
        if (document.getLength() > MAX_LOG_CHARS) {
            try {
                document.remove(0, document.getLength() - MAX_LOG_CHARS);
            } catch (BadLocationException ignored) {
                // The edit races only with our own appends; dropping a log line is harmless.
            }
        }
        if (autoScroll.isSelected()) {
            logArea.setCaretPosition(logArea.getDocument().getLength());
        }
    }

    private void clearLog() {
        logArea.setText("");
    }

    private void copyLog() {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(logArea.getText()), null);
    }

    private void openOutputFolder() {
        if (lastOutput == null) {
            return;
        }
        Path dir = lastOutput.toAbsolutePath().normalize().getParent();
        if (dir == null) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir.toFile());
                return;
            }
        } catch (Exception ignored) {
            // Fall through to showing the path.
        }
        JOptionPane.showMessageDialog(this, "Output folder:\n" + dir,
                "Vapor", JOptionPane.INFORMATION_MESSAGE);
    }

    private static String stageLabel(String stage) {
        return switch (stage) {
            case "pre" -> "Loading";
            case "transform" -> "Transforming";
            case "post" -> "Writing";
            default -> stage;
        };
    }

    private static BufferedImage createAppIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(120, 96, 255),
                size, size, new Color(48, 32, 130)));
        g.fillRoundRect(0, 0, size, size, Math.max(8, size / 4), Math.max(8, size / 4));
        g.setColor(new Color(255, 255, 255, 42));
        g.drawRoundRect(1, 1, size - 3, size - 3, Math.max(8, size / 4), Math.max(8, size / 4));
        g.setFont(new Font("Segoe UI Semibold", Font.BOLD, Math.max(8, (int) (size * 0.55))));
        FontMetrics metrics = g.getFontMetrics();
        String mark = "V";
        int x = (size - metrics.stringWidth(mark)) / 2;
        int y = (size - metrics.getHeight()) / 2 + metrics.getAscent();
        g.setColor(Color.WHITE);
        g.drawString(mark, x, y);
        g.dispose();
        return image;
    }

    private static final class HeaderPanel extends JPanel {
        HeaderPanel() {
            setOpaque(false);
            setLayout(new BorderLayout());
            setPreferredSize(new Dimension(0, 86));
            setBorder(new EmptyBorder(14, 20, 14, 20));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(27, 33, 70),
                    getWidth(), getHeight(), new Color(76, 49, 128)));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(new Color(255, 255, 255, 13));
            g.fillOval(getWidth() - 190, -80, 260, 260);
            g.fillOval(getWidth() - 90, 30, 130, 130);
            g.setColor(new Color(255, 255, 255, 8));
            g.fillOval(getWidth() - 300, 40, 120, 120);
            g.dispose();
        }
    }

    private void chooseInput() {
        JFileChooser chooser = newChooser("Select input jar");
        chooser.setFileFilter(new FileNameExtensionFilter("Java archive (*.jar)", "jar"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path input = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        inputField.setText(input.toString());
        remember(input);
        if (outputField.getText().isBlank()) {
            outputField.setText(defaultOutput(input).toString());
        }
        if (mappingField.getText().isBlank()) {
            mappingField.setText(defaultMapping(input).toString());
        }
    }

    private void chooseOutput() {
        JFileChooser chooser = newChooser("Select output jar");
        chooser.setFileFilter(new FileNameExtensionFilter("Java archive (*.jar)", "jar"));
        Path suggested = tryPath(outputField.getText());
        if (suggested == null) {
            Path input = tryPath(inputField.getText());
            if (input != null) {
                suggested = defaultOutput(input);
            }
        }
        if (suggested != null) {
            chooser.setSelectedFile(suggested.toFile());
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path output = ensureExtension(chooser.getSelectedFile().toPath().toAbsolutePath().normalize(), ".jar");
        outputField.setText(output.toString());
        remember(output);
    }

    private void chooseMapping() {
        JFileChooser chooser = newChooser("Select mapping file");
        chooser.setFileFilter(new FileNameExtensionFilter("Text files (*.txt)", "txt"));
        Path suggested = tryPath(mappingField.getText());
        if (suggested == null) {
            Path input = tryPath(inputField.getText());
            if (input != null) {
                suggested = defaultMapping(input);
            }
        }
        if (suggested != null) {
            chooser.setSelectedFile(suggested.toFile());
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path mapping = ensureExtension(chooser.getSelectedFile().toPath().toAbsolutePath().normalize(), ".txt");
        mappingField.setText(mapping.toString());
        remember(mapping);
    }

    private void chooseLibraries() {
        JFileChooser chooser = newChooser("Add library jars");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Java archives (*.jar)", "jar"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        for (File file : chooser.getSelectedFiles()) {
            addLibrary(file.toPath().toAbsolutePath().normalize());
        }
        Path selected = chooser.getSelectedFile() == null ? null : chooser.getSelectedFile().toPath();
        if (selected != null) {
            remember(selected);
        }
    }

    private void chooseLibraryFolder() {
        JFileChooser chooser = newChooser("Add library class directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path folder = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        addLibrary(folder);
        remember(folder);
    }

    private void addLibrary(Path path) {
        if (!libraryModel.contains(path)) {
            libraryModel.addElement(path);
        }
    }

    private void removeLibraries() {
        int[] selected = libraryList.getSelectedIndices();
        for (int i = selected.length - 1; i >= 0; i--) {
            libraryModel.remove(selected[i]);
        }
    }

    private void chooseConfigLoad() {
        JFileChooser chooser = newChooser("Load Vapor config");
        chooser.setFileFilter(new FileNameExtensionFilter("JSON config (*.json)", "json"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        loadConfig(chooser.getSelectedFile().toPath().toAbsolutePath().normalize());
    }

    private void loadConfig(Path path) {
        try {
            VaporConfig config = VaporConfig.load(path);
            applyConfigToForm(config);
            configSourceLabel.setText("Loaded: " + path.getFileName());
            remember(path);
            appendLog("loaded config: " + path);
        } catch (Exception e) {
            showError("Could not load config:\n" + e.getMessage());
        }
    }

    private void chooseConfigSave() {
        JFileChooser chooser = newChooser("Save Vapor config");
        chooser.setFileFilter(new FileNameExtensionFilter("JSON config (*.json)", "json"));
        chooser.setSelectedFile(new File("vapor.json"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path path = ensureExtension(chooser.getSelectedFile().toPath().toAbsolutePath().normalize(), ".json");
        try {
            VaporConfig config = readConfigFromForm();
            config.save(path);
            configSourceLabel.setText("Saved: " + path.getFileName());
            remember(path);
            appendLog("saved config: " + path);
        } catch (Exception e) {
            showError("Could not save config:\n" + e.getMessage());
        }
    }

    private void resetDefaults() {
        int choice = JOptionPane.showConfirmDialog(this,
                "Reset all transform, string, hardening and protection options to defaults?\n"
                        + "Paths and libraries are kept.",
                "Reset options", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }
        applyConfigToForm(VaporConfig.defaults());
        configSourceLabel.setText("Defaults");
        appendLog("options reset to defaults");
    }

    private void applyConfigToForm(VaporConfig config) {
        VaporConfig.Transformers transformers = config.transformers == null
                ? new VaporConfig.Transformers() : config.transformers;
        VaporConfig.Strings strings = config.strings == null
                ? new VaporConfig.Strings() : config.strings;
        VaporConfig.Hardening hardening = config.hardening == null
                ? new VaporConfig.Hardening() : config.hardening;
        VaporConfig.Protection protection = config.protection == null
                ? new VaporConfig.Protection() : config.protection;

        renameClassesBox.setSelected(transformers.renameClasses);
        renameMethodsBox.setSelected(transformers.renameMethods);
        renameFieldsBox.setSelected(transformers.renameFields);
        stripLineNumbersBox.setSelected(transformers.stripLineNumbers);
        stripLocalVariablesBox.setSelected(transformers.stripLocalVariables);
        stripMethodParametersBox.setSelected(transformers.stripMethodParameters);
        stripSignaturesBox.setSelected(transformers.stripSignatures);
        stripSourceFileBox.setSelected(transformers.stripSourceFile);
        encryptStringsBox.setSelected(transformers.encryptStrings);
        deadCodeBox.setSelected(transformers.deadCode);
        intObfuscationBox.setSelected(transformers.integerObfuscation);
        flowShuffleBox.setSelected(transformers.flowShuffle);
        flowFlattenBox.setSelected(transformers.flowFlatten);
        virtualizeBox.setSelected(transformers.virtualize);
        obfuscateRecordsBox.setSelected(transformers.obfuscateRecords);
        stripAnnotationsBox.setSelected(transformers.stripAnnotations);
        failsafeBox.setSelected(config.failsafe);
        polymorphicBox.setSelected(strings.polymorphic);
        stringPoolBox.setSelected(strings.pool);
        constantValuesBox.setSelected(strings.encryptConstantValues);
        splitSingleBlockBox.setSelected(hardening.splitSingleBlock);
        encryptClassesBox.setSelected(protection.encryptClasses);
        verifyIntegrityBox.setSelected(protection.verifyIntegrity);
        setIntSpinner(minLengthSpinner, strings.minLength);
        setDoubleSpinner(maxOverheadSpinner, hardening.maxOverhead);
        setIntSpinner(deadBranchesSpinner, hardening.deadBranchesPerMethod);
        setIntSpinner(flowSplitsSpinner, hardening.flowSplitsPerMethod);
        setIntSpinner(flattenBlocksSpinner, hardening.flattenMaxBlocks);
        seedField.setText(Long.toString(config.seed));
        watermarkField.setText(config.watermark == null ? "" : config.watermark);
        keepArea.setText(config.keep == null ? "" : String.join("\n", config.keep));
        keepArea.setCaretPosition(0);
    }

    private VaporConfig readConfigFromForm() {
        VaporConfig config = VaporConfig.defaults();
        String seedText = seedField.getText().trim();
        try {
            config.seed = seedText.isEmpty() ? 0L : Long.decode(seedText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Seed must be a whole number (long).");
        }
        config.failsafe = failsafeBox.isSelected();
        config.keep = parseKeepRules();
        config.watermark = watermarkField.getText().isBlank() ? null : watermarkField.getText();

        config.transformers.renameClasses = renameClassesBox.isSelected();
        config.transformers.renameMethods = renameMethodsBox.isSelected();
        config.transformers.renameFields = renameFieldsBox.isSelected();
        config.transformers.stripLineNumbers = stripLineNumbersBox.isSelected();
        config.transformers.stripLocalVariables = stripLocalVariablesBox.isSelected();
        config.transformers.stripMethodParameters = stripMethodParametersBox.isSelected();
        config.transformers.stripSignatures = stripSignaturesBox.isSelected();
        config.transformers.stripSourceFile = stripSourceFileBox.isSelected();
        config.transformers.encryptStrings = encryptStringsBox.isSelected();
        config.transformers.deadCode = deadCodeBox.isSelected();
        config.transformers.integerObfuscation = intObfuscationBox.isSelected();
        config.transformers.flowShuffle = flowShuffleBox.isSelected();
        config.transformers.flowFlatten = flowFlattenBox.isSelected();
        config.transformers.virtualize = virtualizeBox.isSelected();
        config.transformers.obfuscateRecords = obfuscateRecordsBox.isSelected();
        config.transformers.stripAnnotations = stripAnnotationsBox.isSelected();

        config.strings.minLength = ((Number) minLengthSpinner.getValue()).intValue();
        config.strings.polymorphic = polymorphicBox.isSelected();
        config.strings.pool = stringPoolBox.isSelected();
        config.strings.encryptConstantValues = constantValuesBox.isSelected();
        config.hardening.maxOverhead = ((Number) maxOverheadSpinner.getValue()).doubleValue();
        config.hardening.deadBranchesPerMethod = ((Number) deadBranchesSpinner.getValue()).intValue();
        config.hardening.flowSplitsPerMethod = ((Number) flowSplitsSpinner.getValue()).intValue();
        config.hardening.flattenMaxBlocks = ((Number) flattenBlocksSpinner.getValue()).intValue();
        config.hardening.splitSingleBlock = splitSingleBlockBox.isSelected();
        config.protection.encryptClasses = encryptClassesBox.isSelected();
        config.protection.verifyIntegrity = verifyIntegrityBox.isSelected();
        return config;
    }

    private List<String> parseKeepRules() {
        List<String> keep = new ArrayList<>();
        for (String line : keepArea.getText().split("\\R", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                keep.add(trimmed);
            }
        }
        return keep;
    }

    private static void setIntSpinner(JSpinner spinner, int value) {
        SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
        int min = ((Number) model.getMinimum()).intValue();
        int max = ((Number) model.getMaximum()).intValue();
        spinner.setValue(Math.max(min, Math.min(max, value)));
    }

    private static void setDoubleSpinner(JSpinner spinner, double value) {
        SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
        double min = ((Number) model.getMinimum()).doubleValue();
        double max = ((Number) model.getMaximum()).doubleValue();
        spinner.setValue(Math.max(min, Math.min(max, value)));
    }

    private JFileChooser newChooser(String title) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        if (lastDir != null && Files.isDirectory(lastDir)) {
            chooser.setCurrentDirectory(lastDir.toFile());
        }
        return chooser;
    }

    private void remember(Path path) {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null && Files.isDirectory(parent)) {
            lastDir = parent;
        } else if (Files.isDirectory(path)) {
            lastDir = path;
        }
    }

    private static Path tryPath(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Path.of(text.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static Path pathOrDefault(String text, String label) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Path.of(text.trim()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(label + " path is invalid: " + e.getMessage());
        }
    }

    private static Path defaultOutput(Path input) {
        String name = input.getFileName() == null ? "output" : input.getFileName().toString();
        String base = name.toLowerCase().endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
        return input.toAbsolutePath().normalize().resolveSibling(base + "-obf.jar");
    }

    private static Path defaultMapping(Path input) {
        String name = input.getFileName() == null ? "output" : input.getFileName().toString();
        String base = name.toLowerCase().endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
        return input.toAbsolutePath().normalize().resolveSibling(base + "-mapping.txt");
    }

    private static Path ensureExtension(Path path, String extension) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        if (name.toLowerCase().endsWith(extension)) {
            return path;
        }
        return path.resolveSibling(name + extension);
    }

    private void startRun() {
        if (busy) {
            return;
        }
        Path input;
        Path output;
        Path mapping;
        VaporConfig config;
        List<Path> libraries = new ArrayList<>();
        try {
            input = pathOrDefault(inputField.getText(), "Input jar");
            output = pathOrDefault(outputField.getText(), "Output jar");
            mapping = pathOrDefault(mappingField.getText(), "Mapping file");
            if (input == null) {
                throw new IllegalArgumentException("Choose an input jar.");
            }
            if (output == null) {
                throw new IllegalArgumentException("Choose an output jar.");
            }
            config = readConfigFromForm();
            for (int i = 0; i < libraryModel.size(); i++) {
                libraries.add(libraryModel.get(i));
            }
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }

        if (!Files.isRegularFile(input)) {
            showError("Input jar not found:\n" + input);
            return;
        }
        if (input.equals(output)) {
            showError("Input and output must be different files.");
            return;
        }
        if (Files.isDirectory(output)) {
            showError("The output path is a directory:\n" + output);
            return;
        }
        for (Path library : libraries) {
            if (!Files.exists(library)) {
                showError("Library not found:\n" + library);
                return;
            }
        }
        if (mapping != null) {
            if (mapping.equals(input) || mapping.equals(output)) {
                showError("The mapping file must be different from the input and output jars.");
                return;
            }
            if (Files.isDirectory(mapping)) {
                showError("The mapping path is a directory:\n" + mapping);
                return;
            }
        }
        if (!confirmOverwrite(output, mapping)) {
            return;
        }

        Path createdOutput = null;
        Path createdMapping = null;
        try {
            createdOutput = createTemp(output, "out");
            if (mapping != null) {
                createdMapping = createTemp(mapping, "map");
            }
        } catch (IOException e) {
            deleteQuietly(createdOutput);
            deleteQuietly(createdMapping);
            showError("Could not prepare the output:\n" + e.getMessage());
            return;
        }

        pendingOutput = output;
        pendingMapping = mapping;
        tempOutput = createdOutput;
        tempMapping = createdMapping;
        lastOutput = null;
        cancelFlag.set(false);
        logArea.setText("");
        appendLog("Vapor " + Main.VERSION + " — desktop");
        appendLog("input:   " + input.toAbsolutePath());
        appendLog("output:  " + output.toAbsolutePath());
        if (mapping != null) {
            appendLog("mapping: " + mapping.toAbsolutePath());
        }
        appendLog("seed:    " + config.seed);
        appendLog("");
        progress.setIndeterminate(true);
        progress.setValue(0);
        progress.setString("starting…");
        statusLabel.setText("Starting…");
        setBusy(true);
        tabs.setSelectedIndex(2);

        Path tempOut = createdOutput;
        Path tempMap = createdMapping;
        List<Path> finalLibraries = List.copyOf(libraries);
        VaporConfig finalConfig = config;
        SwingWorker<Obfuscator.Result, Void> task = new SwingWorker<>() {
            @Override
            protected Obfuscator.Result doInBackground() throws Exception {
                PrintStream originalOut = System.out;
                PrintStream originalErr = System.err;
                LineOutputStream lineOut = new LineOutputStream(workerLog);
                PrintStream capture = new PrintStream(lineOut, true, StandardCharsets.UTF_8);
                System.setOut(capture);
                System.setErr(capture);
                try {
                    return Obfuscator.run(new Obfuscator.Request(
                            input, tempOut, finalConfig, tempMap, finalLibraries,
                            cancelFlag::get, pipelineListener));
                } finally {
                    capture.flush();
                    lineOut.finish();
                    System.setOut(originalOut);
                    System.setErr(originalErr);
                }
            }

            @Override
            protected void done() {
                finishRun(this);
            }
        };
        worker = task;
        try {
            task.execute();
        } catch (RuntimeException e) {
            worker = null;
            setBusy(false);
            cleanupTemps();
            showError("Could not start the run:\n" + e.getMessage());
        }
    }

    private void requestCancel() {
        if (!busy) {
            return;
        }
        if (!cancelFlag.compareAndSet(false, true)) {
            return;
        }
        cancelButton.setEnabled(false);
        statusLabel.setText("Cancelling at the next safe point…");
        appendLog("cancel requested; stopping at the next safe point");
    }

    private void requestClose() {
        if (busy) {
            if (!cancelFlag.get()) {
                int choice = JOptionPane.showConfirmDialog(this,
                        "An obfuscation is still running.\n"
                                + "Cancel it and exit when it reaches a safe point?",
                        "Vapor", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.YES_OPTION) {
                    return;
                }
            }
            closingAfterCancel = true;
            requestCancel();
            statusLabel.setText("Waiting for cancellation…");
            return;
        }
        dispose();
        System.exit(0);
    }

    private void setBusy(boolean value) {
        busy = value;
        runButton.setEnabled(!value);
        cancelButton.setEnabled(value);
        openOutputButton.setEnabled(!value && lastOutput != null);
        tabs.setEnabledAt(0, !value);
        tabs.setEnabledAt(1, !value);
        setEnabledDeep(jobPanel, !value);
        setEnabledDeep(optionsPanel, !value);
    }

    private static void setEnabledDeep(Component component, boolean enabled) {
        if (component instanceof JLabel) {
            return; // labels stay readable while a run is in flight
        }
        component.setEnabled(enabled);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                setEnabledDeep(child, enabled);
            }
        }
    }

    private boolean confirmOverwrite(Path output, Path mapping) {
        List<Path> existing = new ArrayList<>();
        if (Files.exists(output)) {
            existing.add(output);
        }
        if (mapping != null && Files.exists(mapping)) {
            existing.add(mapping);
        }
        if (existing.isEmpty()) {
            return true;
        }
        StringBuilder message = new StringBuilder("These files already exist and will be replaced:\n\n");
        for (Path path : existing) {
            message.append("  ").append(path.toAbsolutePath()).append('\n');
        }
        return JOptionPane.showConfirmDialog(this, message.toString(),
                "Overwrite existing files?", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private static Path createTemp(Path target, String kind) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("no parent directory for " + target);
        }
        Files.createDirectories(parent);
        String name = absolute.getFileName() == null ? "vapor" : absolute.getFileName().toString();
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "-");
        if (safe.length() > 32) {
            safe = safe.substring(0, 32);
        }
        String prefix = ".vapor-" + kind + "-" + safe + "-";
        if (prefix.length() < 3) {
            prefix = ".vapor-";
        }
        return Files.createTempFile(parent, prefix, ".tmp");
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup of a temporary file.
        }
    }

    private void finishRun(SwingWorker<Obfuscator.Result, Void> task) {
        boolean preserveTemps = false;
        try {
            Obfuscator.Result result = task.get();
            try {
                if (pendingMapping != null) {
                    moveReplacing(tempMapping, pendingMapping);
                    tempMapping = null;
                }
                moveReplacing(tempOutput, pendingOutput);
                tempOutput = null;
            } catch (IOException e) {
                preserveTemps = true;
                progress.setIndeterminate(false);
                progress.setString("move failed");
                statusLabel.setText("Could not replace output");
                appendLog("");
                appendLog("ERROR: could not put the output in place: " + e.getMessage());
                if (tempOutput != null) {
                    appendLog("temporary output: " + tempOutput);
                }
                if (tempMapping != null) {
                    appendLog("temporary mapping: " + tempMapping);
                }
                if (!closingAfterCancel) {
                    JOptionPane.showMessageDialog(this,
                            "The jar was created, but Vapor could not replace:\n"
                                    + pendingOutput + "\n\n" + e.getMessage()
                                    + (tempOutput == null ? "" : "\n\nIt is available at:\n" + tempOutput),
                            "Vapor", JOptionPane.ERROR_MESSAGE);
                }
                return;
            }

            lastOutput = pendingOutput;
            int total = Math.max(1, progress.getMaximum());
            progress.setIndeterminate(false);
            progress.setMaximum(total);
            progress.setValue(total);
            progress.setString("100%");
            statusLabel.setText(String.format("Done in %.0f ms", result.millis()));
            openOutputButton.setEnabled(true);
            appendLog("");
            appendLog("=== complete ===");
            appendLog(String.format("classes: %d, resources: %d",
                    result.classCount(), result.resourceCount()));
            appendLog(String.format("size: %,d -> %,d bytes",
                    result.inputSize(), result.outputSize()));
            appendLog(String.format("time: %.0f ms", result.millis()));
            appendLog("output: " + pendingOutput.toAbsolutePath());
            if (pendingMapping != null) {
                appendLog("mapping: " + pendingMapping.toAbsolutePath());
            }
        } catch (CancellationException e) {
            handleCancelled();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleFailure(new IllegalStateException("Interrupted while waiting for the run.", e));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof CancellationException) {
                handleCancelled();
            } else {
                handleFailure(cause);
            }
        } finally {
            if (!preserveTemps) {
                cleanupTemps();
            }
            setBusy(false);
            worker = null;
            if (closingAfterCancel) {
                dispose();
                System.exit(0);
            }
        }
    }

    private void handleCancelled() {
        cleanupTemps();
        progress.setIndeterminate(false);
        progress.setString("cancelled");
        statusLabel.setText("Cancelled");
        appendLog("");
        appendLog("=== cancelled ===");
        appendLog("no output was written");
    }

    private void handleFailure(Throwable cause) {
        cleanupTemps();
        progress.setIndeterminate(false);
        progress.setString("failed");
        statusLabel.setText("Failed");
        appendLog("");
        appendLog("=== failed ===");
        appendLog(cause.toString());
        StringWriter buffer = new StringWriter();
        cause.printStackTrace(new PrintWriter(buffer));
        for (String line : buffer.toString().split("\\R")) {
            appendLog(line);
        }
        if (!closingAfterCancel) {
            JOptionPane.showMessageDialog(this,
                    "Obfuscation failed:\n\n" + cause,
                    "Vapor", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void cleanupTemps() {
        deleteQuietly(tempOutput);
        deleteQuietly(tempMapping);
        tempOutput = null;
        tempMapping = null;
    }

    private static void moveReplacing(Path from, Path to) throws IOException {
        if (from == null || to == null) {
            throw new IOException("missing temporary or target path");
        }
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void showError(String message) {
        statusLabel.setText("Error");
        if (!closingAfterCancel) {
            JOptionPane.showMessageDialog(this, message, "Vapor", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Splits captured stdout/stderr into complete lines for the Swing log. */
    private static final class LineOutputStream extends OutputStream {
        private final Consumer<String> sink;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        LineOutputStream(Consumer<String> sink) {
            this.sink = sink;
        }

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                buffer.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            for (int i = offset; i < offset + length; i++) {
                write(bytes[i] & 0xFF);
            }
        }

        @Override
        public synchronized void flush() {
            // PrintStream flushes partial formatter chunks. Do not emit them as
            // lines; the caller invokes finish() when the run is over.
        }

        synchronized void finish() {
            if (buffer.size() > 0) {
                flushLine();
            }
        }

        private synchronized void flushLine() {
            String line = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            buffer.reset();
            sink.accept(line);
        }
    }

}






