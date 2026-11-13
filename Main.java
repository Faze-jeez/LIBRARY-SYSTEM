import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import javax.swing.event.*;
import java.security.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.Base64;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

// ABSTRACT PARENT CLASS
abstract class LibraryItem {
    protected String category;
    protected int id;
    protected String title;
    protected String author;
    protected boolean isBorrowed;

    public LibraryItem() {
        this("None", 0, "Untitled", "Unknown", false);
    }

    public LibraryItem(String category, int id, String title,
                       String author, boolean isBorrowed) {
        this.category = category;
        this.id = id;
        this.title = title;
        this.author = author;
        this.isBorrowed = isBorrowed;
    }

    public int getId() { return id; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public String getCategory() { return category; }
    public boolean isBorrowed() { return isBorrowed; }

    public void borrowBook() { isBorrowed = true; }
    public void returnBook() { isBorrowed = false; }

    public abstract void getDetails();
}

// BOOK CLASS
class Book extends LibraryItem {
    public Book() { super(); }

    public Book(String category, int id, String title,
                String author, boolean isBorrowed) {
        super(category, id, title, author, isBorrowed);
    }

    public void updateBook(String category, String title, String author) {
        this.category = category;
        this.title = title;
        this.author = author;
    }

    @Override
    public void getDetails() {
        System.out.println(id + " | " + title + " | " + author + " | "
                + category + " | " + (isBorrowed ? "Borrowed" : "Available"));
    }
}

// EBOOK CLASS - LOADS AND SAVES Book.txt
class EBook extends Book {
    public EBook(String category, int id, String title,
                 String author, boolean isBorrowed) {
        super(category, id, title, author, isBorrowed);
    }

    public static ArrayList<Book> loadBook(String filename) {
        ArrayList<Book> books = new ArrayList<>();
        Path path = Paths.get(filename);

        System.out.println("Reading: " + path.toAbsolutePath());

        try (BufferedReader reader = Files.newBufferedReader(
                path, StandardCharsets.UTF_8)) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                String[] data = line.split(",", -1);
                if (data.length < 5) continue;

                try {
                    String category = data[0].trim();
                    int id = Integer.parseInt(data[1].trim());
                    String title = data[2].trim();
                    String author = data[3].trim();
                    boolean borrowed = Boolean.parseBoolean(data[4].trim());

                    books.add(new Book(category, id, title, author, borrowed));
                } catch (NumberFormatException ex) {
                    System.out.println("Skipping invalid book: " + line);
                }
            }
            System.out.println("Books loaded: " + books.size());
        } catch (IOException ex) {
            System.out.println("Could not read " + path.toAbsolutePath());
            System.out.println(ex.getMessage());
        }
        return books;
    }

    public static void saveBooks(String filename, ArrayList<Book> books) {
        try (BufferedWriter writer = Files.newBufferedWriter(
                Paths.get(filename), StandardCharsets.UTF_8)) {
            for (Book book : books) {
                writer.write(book.getCategory() + ","
                        + book.getId() + ","
                        + book.getTitle() + ","
                        + book.getAuthor() + ","
                        + book.isBorrowed());
                writer.newLine();
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(null,
                    "Unable to save Book.txt:\n" + ex.getMessage(),
                    "File Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public void getDetails() { super.getDetails(); }
}

// MAIN CLASS
public class Main {
    private static final Color SIDEBAR = new Color(31, 43, 57);
    private static final Color BACKGROUND = new Color(247, 245, 239);
    private static final Color CARD = new Color(255, 253, 248);
    private static final Color GREEN = new Color(174, 91, 57);
    private static final Color TEXT = new Color(48, 47, 42);
    private static final Color MUTED = new Color(125, 119, 107);
    private static final Color BORDER = new Color(226, 219, 205);

    private static final String DATABASE_URL = "jdbc:sqlite:library.db";
    private static final String LEGACY_BOOK_FILE = "Book.txt";
    private static final String LEGACY_USERS_FILE = "Users.txt";
    private static ArrayList<Book> libro;

    private static JFrame frame;
    private static JPanel contentPanel;
    private static CardLayout cardLayout;

    private static JLabel totalLabel;
    private static JLabel availableLabel;
    private static JLabel borrowedLabel;
    private static final Map<Integer, JLabel> dashboardStatusLabels = new HashMap<>();

    private static JTable bookTable;
    private static DefaultTableModel tableModel;
    private static JTextField searchField;
    private static JLabel statusLabel;

    public static void main(String[] args) {
        try {
            initializeDatabase();
            libro = loadBooksFromDatabase();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null,
                    "Could not open the SQLite database.\n" + ex.getMessage()
                    + "\n\nMake sure the SQLite JDBC driver is added to your project.",
                    "Database Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        SwingUtilities.invokeLater(Main::showLogin);
    }

    // SQLite stores the whole library in a local file beside the project.
    private static Connection connectDatabase() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL);
    }

    private static void initializeDatabase() throws Exception {
        try (Connection connection = connectDatabase();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("CREATE TABLE IF NOT EXISTS books ("
                    + "id INTEGER PRIMARY KEY, category TEXT NOT NULL, title TEXT NOT NULL, "
                    + "author TEXT NOT NULL, is_borrowed INTEGER NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "username TEXT NOT NULL COLLATE NOCASE UNIQUE, "
                    + "email TEXT NOT NULL COLLATE NOCASE UNIQUE, "
                    + "salt TEXT NOT NULL, password_hash TEXT NOT NULL)");
        }
        migrateLegacyBooks();
        migrateLegacyUsers();
    }

    // Import existing text-file books only when the new database is empty.
    private static void migrateLegacyBooks() throws Exception {
        int count = 0;
        try (Connection c = connectDatabase(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM books")) {
            if (rs.next()) count = rs.getInt(1);
        }
        if (count == 0 && Files.exists(Paths.get(LEGACY_BOOK_FILE))) {
            ArrayList<Book> oldBooks = EBook.loadBook(LEGACY_BOOK_FILE);
            saveBooksToDatabase(oldBooks);
        }
    }

    // Keep existing accounts by importing Users.txt once; passwords remain hashed.
    private static void migrateLegacyUsers() throws Exception {
        try (Connection c = connectDatabase(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
            if (rs.next() && rs.getInt(1) > 0) return;
        }
        Path path = Paths.get(LEGACY_USERS_FILE);
        if (!Files.exists(path)) return;
        try (Connection c = connectDatabase();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR IGNORE INTO users(username,email,salt,password_hash) VALUES(?,?,?,?)")) {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String[] data = line.split(",", -1);
                if (data.length != 4) continue;
                try {
                    String username = new String(Base64.getDecoder().decode(data[0]), StandardCharsets.UTF_8);
                    String email = new String(Base64.getDecoder().decode(data[1]), StandardCharsets.UTF_8);
                    ps.setString(1, username); ps.setString(2, email);
                    ps.setString(3, data[2]); ps.setString(4, data[3]);
                    ps.executeUpdate();
                } catch (IllegalArgumentException ignored) { }
            }
        }
    }

    private static ArrayList<Book> loadBooksFromDatabase() throws SQLException {
        ArrayList<Book> books = new ArrayList<>();
        try (Connection c = connectDatabase(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT category,id,title,author,is_borrowed FROM books ORDER BY id")) {
            while (rs.next()) books.add(new Book(rs.getString("category"), rs.getInt("id"),
                    rs.getString("title"), rs.getString("author"), rs.getInt("is_borrowed") == 1));
        }
        return books;
    }

    private static void saveBooksToDatabase(ArrayList<Book> books) throws SQLException {
        try (Connection c = connectDatabase()) {
            c.setAutoCommit(false);
            try (Statement clear = c.createStatement()) {
                clear.executeUpdate("DELETE FROM books");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO books(id,category,title,author,is_borrowed) VALUES(?,?,?,?,?)")) {
                for (Book book : books) {
                    ps.setInt(1, book.getId()); ps.setString(2, book.getCategory());
                    ps.setString(3, book.getTitle()); ps.setString(4, book.getAuthor());
                    ps.setInt(5, book.isBorrowed() ? 1 : 0); ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        } catch (SQLException ex) {
            throw ex;
        }
    }


    // ---------------- LOGIN / SIGN UP / FORGOT PASSWORD ----------------
    private static JFrame authFrame;
    private static final Color AUTH_BG = new Color(239, 243, 247);

    private static void showLogin() {
        authFrame = new JFrame("Library Management System - Login");
        authFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        authFrame.setSize(470, 650);
        authFrame.setMinimumSize(new Dimension(430, 600));
        authFrame.setLocationRelativeTo(null);
        authFrame.setContentPane(createLoginPanel());
        authFrame.setVisible(true);
    }

    // Replace the authentication screen and force Swing to refresh the window.
    private static void showAuthPanel(JPanel panel) {
        authFrame.setContentPane(panel);
        authFrame.revalidate();
        authFrame.repaint();
        authFrame.setEnabled(true);
        authFrame.toFront();
        authFrame.requestFocus();
    }

    private static JPanel authBase(String heading, String subtitle) {
        JPanel panel = new JPanel();
        panel.setBackground(AUTH_BG);
        panel.setBorder(new EmptyBorder(35, 45, 30, 45));
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel brand = new JLabel("BOOKS  •  LIBRARY");
        brand.setAlignmentX(Component.CENTER_ALIGNMENT);
        brand.setForeground(GREEN);
        brand.setFont(new Font("SansSerif", Font.BOLD, 12));
        JLabel title = new JLabel(heading);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        title.setForeground(TEXT);
        title.setFont(new Font("SansSerif", Font.BOLD, 30));
        JLabel desc = new JLabel(subtitle);
        desc.setAlignmentX(Component.CENTER_ALIGNMENT);
        desc.setForeground(MUTED);
        desc.setFont(new Font("SansSerif", Font.PLAIN, 12));
        panel.add(brand);
        panel.add(Box.createVerticalStrut(18));
        panel.add(title);
        panel.add(Box.createVerticalStrut(7));
        panel.add(desc);
        panel.add(Box.createVerticalStrut(30));
        return panel;
    }

    private static JTextField authTextField() {
        JTextField field = new JTextField();
        field.setFont(new Font("SansSerif", Font.PLAIN, 14));
        field.setHorizontalAlignment(SwingConstants.CENTER);
        field.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        field.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(BORDER, 1, true), new EmptyBorder(8, 10, 8, 10)));
        return field;
    }

    private static JPasswordField authPasswordField() {
        JPasswordField field = new JPasswordField();
        field.setFont(new Font("SansSerif", Font.PLAIN, 14));
        field.setHorizontalAlignment(SwingConstants.CENTER);
        field.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        field.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(BORDER, 1, true), new EmptyBorder(8, 10, 8, 10)));
        return field;
    }

    private static void authLabel(JPanel panel, String text) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setForeground(TEXT);
        label.setFont(new Font("SansSerif", Font.BOLD, 12));
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, label.getPreferredSize().height));
        panel.add(label);
        panel.add(Box.createVerticalStrut(6));
    }

    private static JPanel createLoginPanel() {
        JPanel panel = authBase("Welcome Back", "Log in to manage your library collection.");
        JTextField username = authTextField();
        JPasswordField password = authPasswordField();
        authLabel(panel, "USERNAME"); panel.add(username);
        panel.add(Box.createVerticalStrut(16));
        authLabel(panel, "PASSWORD"); panel.add(password);
        panel.add(Box.createVerticalStrut(22));
        JButton login = styledButton("Log In", GREEN);
        login.setAlignmentX(Component.CENTER_ALIGNMENT);
        login.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        panel.add(login);
        panel.add(Box.createVerticalStrut(13));
        JButton signup = authLink("Create an account  •  Sign Up");
        JButton forgot = authLink("Forgot Password?");
        panel.add(signup); panel.add(forgot);

        panel.add(Box.createVerticalStrut(18));
        JSeparator quitDivider = new JSeparator();
        quitDivider.setForeground(BORDER);
        quitDivider.setMaximumSize(new Dimension(220, 1));
        quitDivider.setAlignmentX(Component.CENTER_ALIGNMENT);
        panel.add(quitDivider);
        panel.add(Box.createVerticalStrut(14));
        JButton quit = styledButton("Quit", new Color(174, 91, 57));
        quit.setAlignmentX(Component.CENTER_ALIGNMENT);
        quit.setPreferredSize(new Dimension(150, 36));
        quit.setMaximumSize(new Dimension(150, 36));
        panel.add(quit);
        quit.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(authFrame,
                    "Close the Library Management System?", "QUIT",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                authFrame.dispose();
                System.exit(0);
            }
        });

        login.addActionListener(e -> {
            String user = username.getText().trim();
            char[] pass = password.getPassword();
            if (authenticate(user, pass)) {
                authFrame.dispose();
                createUI();
            } else {
                JOptionPane.showMessageDialog(authFrame, "Incorrect username or password.",
                        "Login Failed", JOptionPane.ERROR_MESSAGE);
                password.setText("");
            }
            Arrays.fill(pass, '\0');
        });
        signup.addActionListener(e -> showAuthPanel(createSignupPanel()));
        forgot.addActionListener(e -> showAuthPanel(createForgotPanel()));
        return panel;
    }

    private static JPanel createSignupPanel() {
        JPanel panel = authBase("Create Account", "Register to access the library system.");
        JTextField username = authTextField();
        JTextField email = authTextField();
        JPasswordField password = authPasswordField();
        JPasswordField confirm = authPasswordField();
        authLabel(panel, "USERNAME"); panel.add(username); panel.add(Box.createVerticalStrut(10));
        authLabel(panel, "EMAIL ADDRESS"); panel.add(email); panel.add(Box.createVerticalStrut(10));
        authLabel(panel, "PASSWORD (at least 6 characters)"); panel.add(password); panel.add(Box.createVerticalStrut(10));
        authLabel(panel, "CONFIRM PASSWORD"); panel.add(confirm); panel.add(Box.createVerticalStrut(18));
        JButton create = styledButton("Create Account", GREEN);
        create.setAlignmentX(Component.CENTER_ALIGNMENT);
        create.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        panel.add(create); panel.add(Box.createVerticalStrut(10));
        JButton back = authLink("← Back to Login");
        panel.add(back);
        back.addActionListener(e -> showAuthPanel(createLoginPanel()));
        create.addActionListener(e -> {
            String u = username.getText().trim(), em = email.getText().trim();
            char[] pw = password.getPassword(), cpw = confirm.getPassword();
            if (u.isEmpty() || em.isEmpty() || !em.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                JOptionPane.showMessageDialog(authFrame, "Enter a username and valid email address.");
            } else if (pw.length < 6) {
                JOptionPane.showMessageDialog(authFrame, "Password must be at least 6 characters.");
            } else if (!Arrays.equals(pw, cpw)) {
                JOptionPane.showMessageDialog(authFrame, "Passwords do not match.");
            } else if (userExists(u, em)) {
                JOptionPane.showMessageDialog(authFrame, "Username or email is already registered.");
            } else {
                saveUser(u, em, pw);
                JOptionPane.showMessageDialog(authFrame, "Account created! You can now log in.");
                showAuthPanel(createLoginPanel());
            }
            Arrays.fill(pw, '\0'); Arrays.fill(cpw, '\0');
        });
        return panel;
    }

    private static JPanel createForgotPanel() {
        JPanel panel = authBase("Reset Password", "Verify your account using your registered email.");
        JTextField username = authTextField();
        JTextField email = authTextField();
        JPasswordField newPassword = authPasswordField();
        JPasswordField confirm = authPasswordField();
        authLabel(panel, "USERNAME"); panel.add(username); panel.add(Box.createVerticalStrut(12));
        authLabel(panel, "REGISTERED EMAIL"); panel.add(email); panel.add(Box.createVerticalStrut(12));
        authLabel(panel, "NEW PASSWORD"); panel.add(newPassword); panel.add(Box.createVerticalStrut(12));
        authLabel(panel, "CONFIRM NEW PASSWORD"); panel.add(confirm); panel.add(Box.createVerticalStrut(18));
        JButton reset = styledButton("Reset Password", GREEN);
        reset.setAlignmentX(Component.CENTER_ALIGNMENT);
        reset.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        panel.add(reset); panel.add(Box.createVerticalStrut(10));
        JButton back = authLink("← Back to Login");
        panel.add(back);
        back.addActionListener(e -> showAuthPanel(createLoginPanel()));
        reset.addActionListener(e -> {
            String u = username.getText().trim(), em = email.getText().trim();
            char[] pw = newPassword.getPassword(), cpw = confirm.getPassword();
            if (pw.length < 6 || !Arrays.equals(pw, cpw)) {
                JOptionPane.showMessageDialog(authFrame, "Use matching passwords with at least 6 characters.");
            } else if (!resetUserPassword(u, em, pw)) {
                JOptionPane.showMessageDialog(authFrame, "Username and email do not match an account.",
                        "Account Not Found", JOptionPane.ERROR_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(authFrame, "Password reset successfully. Please log in.");
                showAuthPanel(createLoginPanel());
            }
            Arrays.fill(pw, '\0'); Arrays.fill(cpw, '\0');
        });
        return panel;
    }

    private static JButton authLink(String text) {
        JButton button = new JButton(text);
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        button.setForeground(GREEN); button.setBackground(AUTH_BG);
        button.setFont(new Font("SansSerif", Font.PLAIN, 12));
        button.setBorderPainted(false); button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return button;
    }

    // Users.txt stores salted PBKDF2 password hashes, not plain-text passwords.
    private static String passwordHash(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, 120000, 256);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } finally { spec.clearPassword(); }
    }

    private static boolean authenticate(String username, char[] password) {
        for (String[] user : readUsers()) {
            if (user[0].equalsIgnoreCase(username)) {
                try {
                    String actual = passwordHash(password, Base64.getDecoder().decode(user[2]));
                    return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8),
                            user[3].getBytes(StandardCharsets.UTF_8));
                } catch (Exception ex) { return false; }
            }
        }
        return false;
    }

    private static boolean userExists(String username, String email) {
        String sql = "SELECT 1 FROM users WHERE username = ? COLLATE NOCASE OR email = ? COLLATE NOCASE LIMIT 1";
        try (Connection c = connectDatabase(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username); ps.setString(2, email);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException ex) {
            showDatabaseError("Could not check account", ex); return true;
        }
    }

    private static void saveUser(String username, String email, char[] password) {
        try {
            byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
            String hash = passwordHash(password, salt);
            try (Connection c = connectDatabase(); PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users(username,email,salt,password_hash) VALUES(?,?,?,?)")) {
                ps.setString(1, username); ps.setString(2, email);
                ps.setString(3, Base64.getEncoder().encodeToString(salt));
                ps.setString(4, hash); ps.executeUpdate();
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(authFrame, "Could not save account: " + ex.getMessage());
        }
    }

    private static boolean resetUserPassword(String username, String email, char[] password) {
        try {
            byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
            String hash = passwordHash(password, salt);
            try (Connection c = connectDatabase(); PreparedStatement ps = c.prepareStatement(
                    "UPDATE users SET salt=?, password_hash=? WHERE username=? COLLATE NOCASE AND email=? COLLATE NOCASE")) {
                ps.setString(1, Base64.getEncoder().encodeToString(salt));
                ps.setString(2, hash); ps.setString(3, username); ps.setString(4, email);
                return ps.executeUpdate() == 1;
            }
        } catch (Exception ex) {
            showDatabaseError("Could not reset password", ex); return false;
        }
    }

    private static ArrayList<String[]> readUsers() {
        ArrayList<String[]> users = new ArrayList<>();
        try (Connection c = connectDatabase(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT username,email,salt,password_hash FROM users")) {
            while (rs.next()) users.add(new String[] { rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4) });
        } catch (SQLException ex) { showDatabaseError("Could not read accounts", ex); }
        return users;
    }

    private static void showDatabaseError(String action, Exception ex) {
        JOptionPane.showMessageDialog(authFrame != null ? authFrame : frame,
                action + ":\n" + ex.getMessage(), "SQLite Error", JOptionPane.ERROR_MESSAGE);
    }



    private static void createUI() {
        frame = new JFrame("The Reading Room | Library Archive");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1240, 800);
        frame.setMinimumSize(new Dimension(980, 650));
        frame.setLocationRelativeTo(null);
        frame.setLayout(new BorderLayout());
        frame.getContentPane().setBackground(BACKGROUND);

        cardLayout = new CardLayout();
        contentPanel = new JPanel(cardLayout);
        contentPanel.setBackground(BACKGROUND);
        contentPanel.add(createDashboard(), "Dashboard");
        contentPanel.add(createBooksPage(), "Books");
        contentPanel.add(createTransactionsPage(), "Transactions");

        frame.add(createTopNavigation(), BorderLayout.NORTH);
        frame.add(contentPanel, BorderLayout.CENTER);
        refreshAll();
        frame.setVisible(true);
    }

    // A horizontal editorial-style navigation replaces the common left sidebar.
    private static JPanel createTopNavigation() {
        JPanel header = new JPanel(new BorderLayout(20, 0));
        header.setBackground(new Color(29, 39, 51));
        header.setBorder(new CompoundBorder(new MatteBorder(0, 0, 3, 0, GREEN),
                new EmptyBorder(15, 30, 15, 30)));

        JPanel identity = new JPanel();
        identity.setOpaque(false);
        identity.setLayout(new BoxLayout(identity, BoxLayout.Y_AXIS));
        JLabel name = new JLabel("THE READING ROOM");
        name.setFont(new Font("Serif", Font.BOLD, 21));
        name.setForeground(new Color(250, 247, 239));
        JLabel descriptor = new JLabel("LIBRARY MANAGEMENT  /  EST. 2026");
        descriptor.setFont(new Font("SansSerif", Font.BOLD, 9));
        descriptor.setForeground(new Color(190, 184, 169));
        identity.add(name);
        identity.add(Box.createVerticalStrut(4));
        identity.add(descriptor);

        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.CENTER, 7, 4));
        navigation.setOpaque(false);
        navigation.add(topNavButton("Discover", "Dashboard"));
        navigation.add(topNavButton("All Books", "Books"));
        navigation.add(topNavButton("Borrow / Return", "Transactions"));

        JPanel account = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 1));
        account.setOpaque(false);
        JButton logout = new JButton("Log out");
        logout.setFont(new Font("SansSerif", Font.BOLD, 11));
        logout.setForeground(new Color(235, 229, 215));
        logout.setBackground(new Color(29, 39, 51));
        logout.setBorder(new EmptyBorder(9, 10, 9, 10));
        logout.setFocusPainted(false);
        logout.addActionListener(e -> {
            if (themedConfirm("Return to the sign-in screen?", "LOG OUT") == JOptionPane.YES_OPTION) {
                frame.dispose(); frame = null; showLogin();
            }
        });
        JButton quit = styledButton("Quit", new Color(174, 91, 57));
        quit.setPreferredSize(new Dimension(76, 35));
        quit.addActionListener(e -> {
            if (themedConfirm("Close the Library Management System?", "QUIT") == JOptionPane.YES_OPTION) {
                frame.dispose(); System.exit(0);
            }
        });
        account.add(logout);
        account.add(quit);
        header.add(identity, BorderLayout.WEST);
        header.add(navigation, BorderLayout.CENTER);
        header.add(account, BorderLayout.EAST);
        return header;
    }

    private static JButton topNavButton(String label, String page) {
        JButton button = new JButton(label);
        button.setFont(new Font("SansSerif", Font.BOLD, 11));
        button.setForeground(new Color(226, 222, 211));
        button.setBackground(new Color(42, 54, 68));
        button.setBorder(new CompoundBorder(new LineBorder(new Color(67, 79, 91), 1, true),
                new EmptyBorder(9, 13, 9, 13)));
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.addActionListener(e -> cardLayout.show(contentPanel, page));
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent e) {
                button.setForeground(Color.WHITE);
                button.setBackground(GREEN);
                button.setBorder(new CompoundBorder(new LineBorder(GREEN, 1, true),
                        new EmptyBorder(9, 13, 9, 13)));
            }
            public void mouseExited(java.awt.event.MouseEvent e) {
                button.setForeground(new Color(226, 222, 211));
                button.setBackground(new Color(42, 54, 68));
                button.setBorder(new CompoundBorder(new LineBorder(new Color(67, 79, 91), 1, true),
                        new EmptyBorder(9, 13, 9, 13)));
            }
        });
        return button;
    }

    private static JPanel createDashboard() {
        JPanel page = new JPanel(new BorderLayout(0, 22));
        page.setBackground(BACKGROUND);
        page.setBorder(new EmptyBorder(25, 36, 26, 36));

        int available = 0, borrowed = 0;
        for (Book b : libro) { if (b.isBorrowed()) borrowed++; else available++; }

        JPanel hero = new JPanel(new BorderLayout(20, 0));
        hero.setBackground(new Color(35, 48, 62));
        hero.setBorder(new CompoundBorder(new MatteBorder(0, 5, 0, 0, GREEN),
                new EmptyBorder(25, 27, 24, 25)));
        JPanel words = new JPanel();
        words.setOpaque(false);
        words.setLayout(new BoxLayout(words, BoxLayout.Y_AXIS));
        JLabel eyebrow = new JLabel("YOUR LIBRARY  /  YOUR NEXT DISCOVERY");
        eyebrow.setFont(new Font("SansSerif", Font.BOLD, 10));
        eyebrow.setForeground(new Color(213, 157, 119));
        JLabel heading = new JLabel("A world of stories.");
        heading.setFont(new Font("Serif", Font.BOLD, 39));
        heading.setForeground(new Color(250, 247, 239));
        JLabel subtitle = new JLabel("Keep your collection organised, and every story within reach.");
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 12));
        subtitle.setForeground(new Color(201, 203, 199));
        JButton browse = new JButton("EXPLORE THE CATALOGUE  →");
        browse.setFont(new Font("SansSerif", Font.BOLD, 10));
        browse.setForeground(Color.WHITE);
        browse.setBackground(GREEN);
        browse.setBorder(new EmptyBorder(11, 15, 11, 15));
        browse.setFocusPainted(false);
        browse.setCursor(new Cursor(Cursor.HAND_CURSOR));
        browse.setAlignmentX(Component.LEFT_ALIGNMENT);
        browse.addActionListener(e -> cardLayout.show(contentPanel, "Books"));
        words.add(eyebrow); words.add(Box.createVerticalStrut(10));
        words.add(heading); words.add(Box.createVerticalStrut(7));
        words.add(subtitle); words.add(Box.createVerticalStrut(18)); words.add(browse);
        hero.add(words, BorderLayout.CENTER);

        JPanel heroMark = new JPanel(new BorderLayout());
        heroMark.setOpaque(false);
        heroMark.setPreferredSize(new Dimension(205, 145));
        JPanel bookStack = new JPanel(new GridLayout(1, 3, 7, 0));
        bookStack.setOpaque(false);
        Color[] spine = {new Color(174, 91, 57), new Color(184, 158, 112), new Color(91, 119, 111)};
        for (int i = 0; i < 3; i++) {
            JPanel book = new JPanel(new BorderLayout());
            book.setBackground(spine[i]);
            book.setBorder(new CompoundBorder(new LineBorder(new Color(255,255,255,75), 1),
                    new EmptyBorder(9, 5, 9, 5)));
            JLabel mark = new JLabel(i == 0 ? "R" : (i == 1 ? "R" : "R"), SwingConstants.CENTER);
            mark.setFont(new Font("Serif", Font.BOLD, 27));
            mark.setForeground(new Color(250, 239, 218));
            JLabel line = new JLabel("────────", SwingConstants.CENTER);
            line.setFont(new Font("SansSerif", Font.PLAIN, 7));
            line.setForeground(new Color(250, 239, 218));
            book.add(mark, BorderLayout.CENTER); book.add(line, BorderLayout.SOUTH);
            bookStack.add(book);
        }
        JLabel shelfLabel = new JLabel("THE COLLECTION  •  VOL. 01", SwingConstants.CENTER);
        shelfLabel.setForeground(new Color(197, 193, 181));
        shelfLabel.setFont(new Font("SansSerif", Font.BOLD, 9));
        heroMark.add(bookStack, BorderLayout.CENTER);
        heroMark.add(shelfLabel, BorderLayout.SOUTH);
        hero.add(heroMark, BorderLayout.EAST);

        JPanel stats = new JPanel(new GridLayout(1, 3, 12, 0));
        stats.setOpaque(false);
        stats.add(statBlock(String.valueOf(libro.size()), "TOTAL TITLES", "IN YOUR COLLECTION", new Color(174, 91, 57)));
        stats.add(statBlock(String.valueOf(available), "AVAILABLE", "READY TO BORROW", new Color(82, 119, 100)));
        stats.add(statBlock(String.valueOf(borrowed), "CURRENTLY ON LOAN", "CHECKED OUT TITLES", new Color(83, 105, 132)));

        JPanel top = new JPanel(new BorderLayout(0, 13));
        top.setOpaque(false);
        top.add(hero, BorderLayout.CENTER);
        top.add(stats, BorderLayout.SOUTH);

        JPanel shelfHeading = new JPanel(new BorderLayout()); shelfHeading.setOpaque(false);
        JLabel shelfTitle = new JLabel("Recently on the shelf");
        shelfTitle.setFont(new Font("Serif", Font.BOLD, 24)); shelfTitle.setForeground(TEXT);
        JLabel shelfNote = new JLabel("A quick look through the collection");
        shelfNote.setFont(new Font("SansSerif", Font.PLAIN, 11)); shelfNote.setForeground(MUTED);
        JPanel shelfLabels = new JPanel(); shelfLabels.setOpaque(false);
        shelfLabels.setLayout(new BoxLayout(shelfLabels, BoxLayout.Y_AXIS));
        shelfLabels.add(shelfTitle); shelfLabels.add(Box.createVerticalStrut(3)); shelfLabels.add(shelfNote);
        JButton allBooks = new JButton("VIEW ALL  →"); styleTextButton(allBooks);
        allBooks.addActionListener(e -> cardLayout.show(contentPanel, "Books"));
        shelfHeading.add(shelfLabels, BorderLayout.WEST); shelfHeading.add(allBooks, BorderLayout.EAST);

        JPanel shelf = new JPanel(new GridLayout(0, 4, 14, 15));
        shelf.setOpaque(false);
        Color[] coverColors = {new Color(68, 91, 86), new Color(139, 89, 67), new Color(78, 83, 112), new Color(166, 132, 82)};
        if (libro.isEmpty()) {
            JLabel empty = new JLabel("No titles yet — add your first book from the catalogue.");
            empty.setForeground(MUTED); empty.setFont(new Font("SansSerif", Font.PLAIN, 14));
            shelf.add(empty);
        } else {
            int count = Math.min(libro.size(), 12);
            for (int i = 0; i < count; i++) {
                Book book = libro.get(i);
                JPanel tile = new JPanel(new BorderLayout(0, 9));
                tile.setBackground(CARD);
                tile.setBorder(new CompoundBorder(new MatteBorder(0, 0, 3, 0, coverColors[i % coverColors.length]),
                        new CompoundBorder(new LineBorder(new Color(220, 213, 199), 1), new EmptyBorder(10, 10, 12, 10))));
                tile.setToolTipText(book.getTitle() + " — " + book.getAuthor());
                JPanel cover = new JPanel(new BorderLayout());
                cover.setBackground(coverColors[i % coverColors.length]);
                cover.setBorder(new CompoundBorder(new MatteBorder(0, 6, 0, 0, new Color(239, 220, 190)),
                        new EmptyBorder(10, 8, 10, 8)));
                cover.setPreferredSize(new Dimension(130, 118));
                JLabel series = new JLabel("READING ROOM", SwingConstants.CENTER);
                series.setForeground(new Color(240, 226, 201)); series.setFont(new Font("SansSerif", Font.BOLD, 8));
                JLabel number = new JLabel("NO. " + String.format("%03d", book.getId()), SwingConstants.CENTER);
                number.setForeground(new Color(250, 242, 225)); number.setFont(new Font("Serif", Font.BOLD, 17));
                JLabel rule = new JLabel("━━━━━━━━━━", SwingConstants.CENTER); rule.setForeground(new Color(235, 222, 198));
                JPanel coverText = new JPanel(new BorderLayout(0, 13)); coverText.setOpaque(false);
                coverText.add(series, BorderLayout.NORTH); coverText.add(number, BorderLayout.CENTER); coverText.add(rule, BorderLayout.SOUTH);
                cover.add(coverText, BorderLayout.CENTER);
                JLabel title = new JLabel("<html><b>" + html(book.getTitle()) + "</b></html>");
                title.setForeground(TEXT); title.setFont(new Font("Serif", Font.BOLD, 14));
                JLabel author = new JLabel(html(book.getAuthor())); author.setForeground(MUTED); author.setFont(new Font("SansSerif", Font.PLAIN, 10));
                JLabel state = new JLabel(book.isBorrowed() ? "● ON LOAN" : "● AVAILABLE");
                dashboardStatusLabels.put(book.getId(), state);
                state.setForeground(book.isBorrowed() ? new Color(153, 87, 67) : new Color(75, 116, 91));
                state.setFont(new Font("SansSerif", Font.BOLD, 9));
                JPanel info = new JPanel(); info.setOpaque(false); info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
                info.add(title); info.add(Box.createVerticalStrut(4)); info.add(author); info.add(Box.createVerticalStrut(7)); info.add(state);
                tile.add(cover, BorderLayout.NORTH); tile.add(info, BorderLayout.CENTER);
                shelf.add(tile);
            }
        }
        JScrollPane scroll = new JScrollPane(shelf);
        scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.getViewport().setBackground(BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        JPanel center = new JPanel(new BorderLayout(0, 14)); center.setOpaque(false);
        center.add(shelfHeading, BorderLayout.NORTH); center.add(scroll, BorderLayout.CENTER);
        page.add(top, BorderLayout.NORTH); page.add(center, BorderLayout.CENTER);
        return page;
    }

    private static JPanel statBlock(String value, String label, String note, Color accent) {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setBackground(new Color(252, 250, 245));
        panel.setBorder(new CompoundBorder(new LineBorder(new Color(222, 215, 201), 1),
                new EmptyBorder(10, 14, 10, 14)));
        JPanel stripe = new JPanel(); stripe.setBackground(accent); stripe.setPreferredSize(new Dimension(4, 35));
        JLabel number = new JLabel(value); number.setForeground(TEXT); number.setFont(new Font("Serif", Font.BOLD, 25));
        if ("TOTAL TITLES".equals(label)) totalLabel = number;
        else if ("AVAILABLE".equals(label)) availableLabel = number;
        else if ("CURRENTLY ON LOAN".equals(label)) borrowedLabel = number;
        JLabel heading = new JLabel(label); heading.setForeground(TEXT); heading.setFont(new Font("SansSerif", Font.BOLD, 10));
        JLabel caption = new JLabel(note); caption.setForeground(MUTED); caption.setFont(new Font("SansSerif", Font.PLAIN, 8));
        JPanel labels = new JPanel(); labels.setOpaque(false); labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        labels.add(heading); labels.add(Box.createVerticalStrut(3)); labels.add(caption);
        panel.add(stripe, BorderLayout.WEST); panel.add(number, BorderLayout.CENTER); panel.add(labels, BorderLayout.EAST);
        return panel;
    }

    private static JPanel summaryText(String value, String caption) {
        JPanel p = new JPanel();
        p.setBackground(new Color(250, 249, 244));
        p.setBorder(new CompoundBorder(new LineBorder(new Color(218, 220, 207), 1, true),
                new EmptyBorder(8, 12, 8, 12)));
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel n = new JLabel(value); n.setForeground(TEXT); n.setFont(new Font("Serif", Font.BOLD, 23));
        JLabel c = new JLabel(caption); c.setForeground(MUTED); c.setFont(new Font("SansSerif", Font.BOLD, 8));
        p.add(n); p.add(Box.createVerticalStrut(2)); p.add(c); return p;
    }

    private static String html(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static JPanel createBooksPage() {
        JPanel page = new JPanel(new BorderLayout(0, 18)); page.setBackground(BACKGROUND);
        page.setBorder(new EmptyBorder(28, 30, 28, 30));
        JLabel eyebrow = new JLabel("LIBRARY TOOLS  /  INVENTORY"); eyebrow.setFont(new Font("SansSerif", Font.BOLD, 10)); eyebrow.setForeground(GREEN);
        JLabel heading = new JLabel("Catalogue"); heading.setFont(new Font("SansSerif", Font.BOLD, 29)); heading.setForeground(TEXT);
        JLabel subtitle = new JLabel("Add, update, find, or remove titles from your collection."); subtitle.setForeground(MUTED);
        JPanel titlePanel = new JPanel(); titlePanel.setOpaque(false); titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));
        titlePanel.add(eyebrow); titlePanel.add(Box.createVerticalStrut(7)); titlePanel.add(heading); titlePanel.add(Box.createVerticalStrut(4)); titlePanel.add(subtitle);

        searchField = new JTextField(18); searchField.setFont(new Font("SansSerif", Font.PLAIN, 13));
        searchField.setPreferredSize(new Dimension(230, 38)); searchField.setBorder(BorderFactory.createCompoundBorder(new LineBorder(BORDER,1,true), new EmptyBorder(7,11,7,11)));
        JButton add = styledButton("+  Add a title", GREEN); add.setPreferredSize(new Dimension(135, 38));
        JButton edit = styledButton("Edit selected", new Color(56, 75, 94)); edit.setPreferredSize(new Dimension(125, 38));
        JButton delete = styledButton("Remove", new Color(153, 75, 67)); delete.setPreferredSize(new Dimension(95, 38));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0)); controls.setOpaque(false);
        controls.add(searchField); controls.add(add); controls.add(edit); controls.add(delete);
        JPanel top = new JPanel(new BorderLayout(0, 16)); top.setOpaque(false); top.add(titlePanel, BorderLayout.NORTH); top.add(controls, BorderLayout.SOUTH);

        bookTable = createTable(); tableModel = (DefaultTableModel) bookTable.getModel();
        JScrollPane scroll = new JScrollPane(bookTable); scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.getViewport().setBackground(CARD);
        JPanel tableCard = createCard(); tableCard.setLayout(new BorderLayout());
        tableCard.setBorder(new CompoundBorder(new LineBorder(new Color(211, 204, 188), 1, true), new EmptyBorder(17, 17, 17, 17)));
        JLabel tableCaption = new JLabel("  YOUR BOOKS"); tableCaption.setForeground(MUTED); tableCaption.setFont(new Font("SansSerif", Font.BOLD, 10));
        tableCaption.setBorder(new EmptyBorder(3, 0, 12, 0)); tableCard.add(tableCaption, BorderLayout.NORTH); tableCard.add(scroll, BorderLayout.CENTER);
        add.addActionListener(e -> addBook()); edit.addActionListener(e -> editBook()); delete.addActionListener(e -> deleteBook());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filterBooks(); }
            public void removeUpdate(DocumentEvent e) { filterBooks(); }
            public void changedUpdate(DocumentEvent e) { filterBooks(); }
        });
        page.add(top, BorderLayout.NORTH); page.add(tableCard, BorderLayout.CENTER); return page;
    }

    private static JPanel createTransactionsPage() {
        JPanel page = new JPanel(new BorderLayout(0, 22)); page.setBackground(BACKGROUND);
        page.setBorder(new EmptyBorder(28, 30, 28, 30));
        JLabel eyebrow = new JLabel("LIBRARY TOOLS  /  CIRCULATION"); eyebrow.setFont(new Font("SansSerif", Font.BOLD, 10)); eyebrow.setForeground(GREEN);
        JLabel heading = new JLabel("Borrow & return"); heading.setFont(new Font("SansSerif", Font.BOLD, 29)); heading.setForeground(TEXT);
        JLabel description = new JLabel("Record a loan or check a returned title back into the collection."); description.setForeground(MUTED);
        JPanel title = new JPanel(); title.setOpaque(false); title.setLayout(new BoxLayout(title, BoxLayout.Y_AXIS));
        title.add(eyebrow); title.add(Box.createVerticalStrut(7)); title.add(heading); title.add(Box.createVerticalStrut(4)); title.add(description);

        JPanel workspace = new JPanel(new GridLayout(1, 2, 16, 0)); workspace.setOpaque(false);
        JPanel borrowCard = createCard(); borrowCard.setBorder(new CompoundBorder(new MatteBorder(4, 0, 0, 0, GREEN), new CompoundBorder(new LineBorder(new Color(218, 207, 188), 1, true), new EmptyBorder(20, 20, 20, 20)))); borrowCard.setLayout(new BoxLayout(borrowCard, BoxLayout.Y_AXIS));
        JLabel borrowTag = new JLabel("01  /  CHECK OUT"); borrowTag.setForeground(GREEN); borrowTag.setFont(new Font("SansSerif", Font.BOLD, 10));
        JLabel borrowTitle = new JLabel("Borrow a book"); borrowTitle.setForeground(TEXT); borrowTitle.setFont(new Font("SansSerif", Font.BOLD, 21));
        JLabel borrowHelp = new JLabel("Enter the ID of an available title."); borrowHelp.setForeground(MUTED); borrowHelp.setFont(new Font("SansSerif", Font.PLAIN, 12));
        JTextField borrowId = transactionField(); JButton borrow = styledButton("Confirm Borrow  →", GREEN); borrow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42)); borrow.setAlignmentX(Component.LEFT_ALIGNMENT);
        borrowCard.add(borrowTag); borrowCard.add(Box.createVerticalStrut(18)); borrowCard.add(borrowTitle); borrowCard.add(Box.createVerticalStrut(6)); borrowCard.add(borrowHelp); borrowCard.add(Box.createVerticalStrut(22));
        borrowCard.add(fieldCaption("BOOK ID")); borrowCard.add(Box.createVerticalStrut(7)); borrowCard.add(borrowId); borrowCard.add(Box.createVerticalStrut(18)); borrowCard.add(borrow);

        JPanel returnCard = createCard(); returnCard.setBorder(new CompoundBorder(new MatteBorder(4, 0, 0, 0, new Color(153, 75, 67)), new CompoundBorder(new LineBorder(new Color(218, 207, 188), 1, true), new EmptyBorder(20, 20, 20, 20)))); returnCard.setLayout(new BoxLayout(returnCard, BoxLayout.Y_AXIS));
        JLabel returnTag = new JLabel("02  /  CHECK IN"); returnTag.setForeground(new Color(153, 75, 67)); returnTag.setFont(new Font("SansSerif", Font.BOLD, 10));
        JLabel returnTitle = new JLabel("Return a book"); returnTitle.setForeground(TEXT); returnTitle.setFont(new Font("SansSerif", Font.BOLD, 21));
        JLabel returnHelp = new JLabel("Enter the ID of a currently borrowed title."); returnHelp.setForeground(MUTED); returnHelp.setFont(new Font("SansSerif", Font.PLAIN, 12));
        JTextField returnId = transactionField(); JButton returned = styledButton("Confirm Return  →", new Color(56, 75, 94)); returned.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42)); returned.setAlignmentX(Component.LEFT_ALIGNMENT);
        returnCard.add(returnTag); returnCard.add(Box.createVerticalStrut(18)); returnCard.add(returnTitle); returnCard.add(Box.createVerticalStrut(6)); returnCard.add(returnHelp); returnCard.add(Box.createVerticalStrut(22));
        returnCard.add(fieldCaption("BOOK ID")); returnCard.add(Box.createVerticalStrut(7)); returnCard.add(returnId); returnCard.add(Box.createVerticalStrut(18)); returnCard.add(returned);

        statusLabel = new JLabel("  Ready for your next transaction."); statusLabel.setFont(new Font("SansSerif", Font.BOLD, 12)); statusLabel.setForeground(MUTED);
        JPanel status = new JPanel(new BorderLayout()); status.setBackground(new Color(226, 234, 241)); status.setBorder(new EmptyBorder(14, 16, 14, 16)); status.add(statusLabel, BorderLayout.CENTER);
        workspace.add(borrowCard); workspace.add(returnCard);
        borrow.addActionListener(e -> processTransaction(borrowId.getText(), true));
        returned.addActionListener(e -> processTransaction(returnId.getText(), false));
        JPanel center = new JPanel(new BorderLayout(0, 16)); center.setOpaque(false); center.add(workspace, BorderLayout.NORTH); center.add(status, BorderLayout.SOUTH);
        page.add(title, BorderLayout.NORTH); page.add(center, BorderLayout.CENTER); return page;
    }

    private static JTextField transactionField() {
        JTextField field = new JTextField(); field.setFont(new Font("SansSerif", Font.PLAIN, 15));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        field.setBorder(BorderFactory.createCompoundBorder(new LineBorder(BORDER, 1, true), new EmptyBorder(8, 11, 8, 11)));
        return field;
    }

    private static JLabel fieldCaption(String text) {
        JLabel label = new JLabel(text); label.setForeground(MUTED); label.setFont(new Font("SansSerif", Font.BOLD, 10));
        label.setAlignmentX(Component.LEFT_ALIGNMENT); return label;
    }

    private static void processTransaction(String input, boolean borrow) {
        String id = input.trim();

        if (id.isEmpty()) {
            statusLabel.setText("Status: Please enter a book ID.");
            return;
        }

        try {
            int bookId = Integer.parseInt(id);

            for (Book book : libro) {
                if (book.getId() == bookId) {
                    if (borrow) {
                        if (book.isBorrowed()) {
                            statusLabel.setText("Already borrowed: " + book.getTitle());
                        } else {
                            book.borrowBook();
                            if (saveAndRefresh()) statusLabel.setText("Successfully borrowed: " + book.getTitle());
                            else { book.returnBook(); refreshAll(); }
                        }
                    } else {
                        if (!book.isBorrowed()) {
                            statusLabel.setText("Already available: " + book.getTitle());
                        } else {
                            book.returnBook();
                            if (saveAndRefresh()) statusLabel.setText("Successfully returned: " + book.getTitle());
                            else { book.borrowBook(); refreshAll(); }
                        }
                    }
                    return;
                }
            }
            statusLabel.setText("Status: Book ID not found.");
        } catch (NumberFormatException ex) {
            statusLabel.setText("Status: Enter a valid numeric ID.");
        }
    }

    private static void addBook() {
        JTextField id = new JTextField();
        JTextField title = new JTextField();
        JTextField author = new JTextField();
        JTextField category = new JTextField();

        Object[] fields = {
            "Book ID:", id,
            "Book Title:", title,
            "Author:", author,
            "Category:", category
        };

        int result = JOptionPane.showConfirmDialog(frame, fields,
                "Add New Book", JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return;

        try {
            int bookId = Integer.parseInt(id.getText().trim());

            if (title.getText().trim().isEmpty()
                    || author.getText().trim().isEmpty()
                    || category.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Please complete all fields.");
                return;
            }

            for (Book book : libro) {
                if (book.getId() == bookId) {
                    JOptionPane.showMessageDialog(frame, "That book ID already exists.");
                    return;
                }
            }

            libro.add(new Book(category.getText().trim(), bookId,
                    title.getText().trim(), author.getText().trim(), false));
            saveAndRefresh();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(frame, "Book ID must be a number.");
        }
    }

    private static void editBook() {
        Book book = selectedBook();
        if (book == null) return;

        JTextField title = new JTextField(book.getTitle());
        JTextField author = new JTextField(book.getAuthor());
        JTextField category = new JTextField(book.getCategory());

        Object[] fields = {
            "Book Title:", title,
            "Author:", author,
            "Category:", category
        };

        int result = JOptionPane.showConfirmDialog(frame, fields,
                "Edit Book", JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return;

        if (title.getText().trim().isEmpty()
                || author.getText().trim().isEmpty()
                || category.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Please complete all fields.");
            return;
        }

        book.updateBook(category.getText().trim(),
                title.getText().trim(), author.getText().trim());
        saveAndRefresh();
    }

    private static void deleteBook() {
        Book book = selectedBook();
        if (book == null) return;

        int confirm = JOptionPane.showConfirmDialog(frame,
                "Delete \"" + book.getTitle() + "\"?",
                "Confirm Deletion", JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            libro.remove(book);
            saveAndRefresh();
        }
    }

    private static Book selectedBook() {
        int row = bookTable.getSelectedRow();
        if (row == -1) {
            JOptionPane.showMessageDialog(frame,
                    "Please select a book from the table.");
            return null;
        }

        int modelRow = bookTable.convertRowIndexToModel(row);
        int id = (Integer) tableModel.getValueAt(modelRow, 0);

        for (Book book : libro) {
            if (book.getId() == id) return book;
        }
        return null;
    }

    private static JTable createTable() {
        String[] columns = {"ID", "Title", "Author", "Category", "Status"};

        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int column) {
                return column == 0 ? Integer.class : String.class;
            }
        };

        JTable table = new JTable(model);
        table.setFont(new Font("SansSerif", Font.PLAIN, 13));
        table.setRowHeight(43);
        table.setForeground(TEXT);
        table.setBackground(CARD);
        table.setGridColor(BORDER);
        table.setSelectionBackground(new Color(238, 226, 207));
        table.setSelectionForeground(TEXT);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(8, 0));

        JTableHeader header = table.getTableHeader();
        header.setBackground(new Color(232, 225, 211));
        header.setForeground(TEXT);
        header.setFont(new Font("SansSerif", Font.BOLD, 11));
        header.setPreferredSize(new Dimension(0, 44));
        header.setReorderingAllowed(false);

        // Make the Status column visually obvious instead of showing plain text.
        table.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel cell = (JLabel) super.getTableCellRendererComponent(
                        t, value, isSelected, hasFocus, row, column);
                boolean onLoan = "Borrowed".equals(String.valueOf(value));
                cell.setText(onLoan ? "●  BORROWED" : "●  AVAILABLE");
                cell.setFont(new Font("SansSerif", Font.BOLD, 10));
                cell.setHorizontalAlignment(SwingConstants.CENTER);
                cell.setForeground(onLoan ? new Color(151, 75, 62) : new Color(55, 112, 79));
                cell.setBackground(isSelected ? t.getSelectionBackground() :
                        (onLoan ? new Color(250, 235, 229) : new Color(233, 243, 235)));
                cell.setBorder(new EmptyBorder(5, 7, 5, 7));
                return cell;
            }
        });
        return table;
    }

    private static void filterBooks() {
        if (bookTable == null || tableModel == null || searchField == null) return;

        String search = searchField.getText().trim();
        TableRowSorter<DefaultTableModel> sorter =
                new TableRowSorter<>(tableModel);
        bookTable.setRowSorter(sorter);

        if (search.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.regexFilter(
                    "(?i)" + java.util.regex.Pattern.quote(search)));
        }
    }

    private static void refreshAll() {
        int borrowed = 0;
        for (Book book : libro) {
            if (book.isBorrowed()) borrowed++;
        }

        if (totalLabel != null) totalLabel.setText(String.valueOf(libro.size()));
        if (availableLabel != null)
            availableLabel.setText(String.valueOf(libro.size() - borrowed));
        if (borrowedLabel != null)
            borrowedLabel.setText(String.valueOf(borrowed));

        if (tableModel != null) updateTable(tableModel);

        // Keep the dashboard book badges in sync after every borrow/return.
        for (Book book : libro) {
            JLabel state = dashboardStatusLabels.get(book.getId());
            if (state != null) {
                state.setText(book.isBorrowed() ? "● ON LOAN" : "● AVAILABLE");
                state.setForeground(book.isBorrowed()
                        ? new Color(153, 87, 67) : new Color(75, 116, 91));
            }
        }

        if (contentPanel != null) {
            for (Component component : contentPanel.getComponents()) {
                if (component instanceof JPanel) {
                    Object model = ((JPanel) component)
                            .getClientProperty("previewModel");
                    if (model instanceof DefaultTableModel) {
                        updateTable((DefaultTableModel) model);
                    }
                }
            }
        }
        if (bookTable != null) filterBooks();
    }

    private static void updateTable(DefaultTableModel model) {
        model.setRowCount(0);
        for (Book book : libro) {
            model.addRow(new Object[] {
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                book.getCategory(),
                book.isBorrowed() ? "Borrowed" : "Available"
            });
        }
    }

    private static boolean saveAndRefresh() {
        try {
            saveBooksToDatabase(libro);
            refreshAll();
            return true;
        } catch (SQLException ex) {
            showDatabaseError("Could not save library changes", ex);
            return false;
        }
    }

    private static int themedConfirm(String message, String title) {
        UIManager.put("OptionPane.background", CARD);
        UIManager.put("Panel.background", CARD);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("OptionPane.messageFont", new Font("SansSerif", Font.BOLD, 13));
        UIManager.put("Button.background", new Color(31, 48, 66));
        UIManager.put("Button.foreground", Color.WHITE);
        UIManager.put("Button.font", new Font("SansSerif", Font.BOLD, 12));
        return JOptionPane.showConfirmDialog(frame, message, title, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
    }

    private static JPanel createCard() {
        JPanel panel = new JPanel();
        panel.setBackground(CARD);
        panel.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(218, 207, 188), 1, true),
                new EmptyBorder(20, 20, 20, 20)));
        return panel;
    }

    private static JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Serif", Font.BOLD, 19));
        label.setForeground(TEXT);
        return label;
    }

    private static JButton styledButton(String text, Color color) {
        JButton button = new JButton(text);
        button.setFont(new Font("SansSerif", Font.BOLD, 12));
        button.setForeground(Color.WHITE);
        button.setBackground(color);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setPreferredSize(new Dimension(115, 36));
        button.setBorder(new CompoundBorder(new LineBorder(new Color(0, 0, 0, 18), 1, true), new EmptyBorder(8, 13, 8, 13)));
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent e) {
                button.setBackground(color.brighter());
                button.setBorder(new CompoundBorder(new LineBorder(color.darker(), 1, true), new EmptyBorder(8, 13, 8, 13)));
            }
            public void mouseExited(java.awt.event.MouseEvent e) {
                button.setBackground(color);
                button.setBorder(new CompoundBorder(new LineBorder(new Color(0, 0, 0, 18), 1, true), new EmptyBorder(8, 13, 8, 13)));
            }
        });
        return button;
    }

    private static void styleTextButton(JButton button) {
        button.setFont(new Font("SansSerif", Font.BOLD, 12));
        button.setForeground(GREEN);
        button.setBackground(CARD);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent e) { button.setForeground(new Color(139, 70, 43)); }
            public void mouseExited(java.awt.event.MouseEvent e) { button.setForeground(GREEN); }
        });
    }
}
