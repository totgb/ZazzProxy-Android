package com.totgb.zazzproxy.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.totgb.zazzproxy.archive.ZazzArchive;
import com.totgb.zazzproxy.model.FileInfo;
import com.totgb.zazzproxy.model.Peer;
import com.totgb.zazzproxy.network.ZazzUdpNode;
import com.totgb.zazzproxy.network.HostedFileService;
import com.totgb.zazzproxy.service.SessionCoordinator;
import com.totgb.zazzproxy.service.ZazzBackgroundService;
import com.totgb.zazzproxy.settings.ProfileAvatarStore;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.io.ByteArrayInputStream;

/** Purpose-built LAN sharing dashboard. All layout is created in Java. */
public class MainActivity extends AppCompatActivity {
    private static final int EXPORT_CATALOG = 41, IMPORT_CATALOG = 42, EXPORT_SETTINGS = 43, IMPORT_SETTINGS = 44,
            PICK_CLIENT_AVATAR = 45, PICK_SERVER_AVATAR = 46;
    private FrameLayout content;
    private ZazzUdpNode node;
    private SessionCoordinator.Role role;
    private TextView connectionLabel, activityLog;
    private TextView connectionPageStatus;
    private TextView clientSelectedFiles;
    private MacMotionButton clientStopButton;
    private View clientCard;
    private View serverCard;
    private MacMotionButton resetButton;
    private TextView searchIndicator;
    private TextView clientProfileLabel;
    private LinearLayout discoveredServers;
    private LinearLayout clientFiles;
    private Peer connectedServer;
    private String lastManifestSignature = "";
    private final java.util.Map<String, Peer> discoveredServerPeers = new java.util.LinkedHashMap<>();
    private final java.util.List<File> pendingClientFiles = new java.util.ArrayList<>();
    private final java.util.Map<String, PendingUpload> pendingUploads = new java.util.LinkedHashMap<>();
    private final android.os.Handler incomingOfferHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable incomingOfferDialog = this::showIncomingOffers;
    private final java.util.Map<String, List<FileInfo>> receivedManifests = new java.util.LinkedHashMap<>();
    private final java.util.Set<String> requestedDownloads = new java.util.HashSet<>();
    private final java.util.Set<String> completedDownloads = new java.util.HashSet<>();
    private final java.util.List<ZazzArchive.DownloadRecord> downloadedRecords = new java.util.ArrayList<>();
    private boolean transferredInSession;
    private LinearLayout transferPanel;
    private final java.util.Map<String, ProgressBar> transferBars = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, TextView> transferLabels = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, TransferState> transferStates = new java.util.LinkedHashMap<>();
    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Set<String> promptedServers = new HashSet<>();
    private boolean searchPulse;
    private boolean navigationBusy;
    private WifiManager.MulticastLock multicastLock;
    private final java.util.Map<String, MaterialButton> navigationButtons = new java.util.LinkedHashMap<>();
    private String activePage = "HOME";
    private static final int STORAGE_PERMISSION_REQUEST = 901;
    private LinearLayout bottomNavigation;
    private View responsiveRoot;
    private int lastWidth;
    private int lastHeight;
    private final android.os.Handler notificationHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final int CONNECTION_NOTIFICATION_ID = 72;
    private static final String CONNECTION_CHANNEL = "zazzproxy_connection";
    private static final String ACTION_CLOSE_CONNECTION =
            "com.totgb.zazzproxy.action.CLOSE_CONNECTION";
    private boolean closeConnectionRequested;

    private static final class TransferState {
        final long current;
        final long total;
        final boolean upload;
        TransferState(long current, long total, boolean upload) {
            this.current = current;
            this.total = total;
            this.upload = upload;
        }
    }
    private String notificationTransferStatus = "";
    private final Runnable notificationCheck = new Runnable() {
        @Override public void run() {
            if (node == null || role == null) return;
            android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
            boolean present = false;
            if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications()) {
                    if (item.getId() == CONNECTION_NOTIFICATION_ID) {
                        present = true;
                        break;
                    }

                }
            }
            if (!present) ensureConnectionNotification();
            notificationHandler.postDelayed(this, 3000);
        }
    };

    @Override public void onCreate(Bundle state) {
        applyTheme(p().getString("theme", "system"));
        super.onCreate(state);
        closeConnectionRequested = ACTION_CLOSE_CONNECTION.equals(getIntent().getAction());
        setContentView(new SplashOverlay(this, this::showDashboard));
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_CLOSE_CONNECTION.equals(intent.getAction())) {
            returnHome();
        }
    }

    public void acceptAllUploads() {
    if (node == null || pendingUploads.isEmpty()) {
        MacToast.show(this, "No incoming files are waiting for approval", false);
        return;
    }
    for (PendingUpload upload : new java.util.ArrayList<>(pendingUploads.values())) {
        node.approveUpload(upload.peer, upload.transfer, upload.file, true);
        pendingUploads.remove(upload.transfer);
    }
    MacToast.show(this, "All incoming files accepted", true);
    }

    public void sendHostedFilesToClients() {
        if (node == null || role != SessionCoordinator.Role.SERVER) return;
        List<Peer> clients = node.connectedPeers();
        if (clients.isEmpty()) {
            MacToast.show(this, "No connected clients are available", false);
            return;
        }
        new Thread(() -> {
            try {
                for (Peer peer : clients) node.sendHostedFiles(peer);
                runOnUiThread(() -> MacToast.show(this, "Hosted files sent for client approval", true));
            } catch (Exception error) {
                runOnUiThread(() -> MacToast.show(this, error.getMessage(), false));
            }
        }).start();
    }

    private static final class PendingUpload {
    final Peer peer;
    final String transfer;
    final FileInfo file;
    PendingUpload(Peer peer, String transfer, FileInfo file) {
        this.peer = peer;
        this.transfer = transfer;
        this.file = file;
    }
    }

    private void showDashboard() {
        acquireWifi();
        requestStorageAccess();
        try {
            downloadedRecords.clear();
            downloadedRecords.addAll(ZazzArchive.loadDownloads(this));
        } catch (Exception error) {
            MacToast.show(this, "Could not load download history", false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 902);
        }
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.rgb(11, 17, 31));
        content = new FrameLayout(this);
        content.setId(View.generateViewId());
        shell.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        bottomNavigation = bottomBar();
        shell.addView(bottomNavigation, new LinearLayout.LayoutParams(-1, dp(78)));
        FrameLayout root = new FrameLayout(this);
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        responsiveRoot = root;
        configureSystemNavigation(root);
        BlockChaseView chase = new BlockChaseView(this);
        chase.setClickable(false);
        root.addView(chase, new FrameLayout.LayoutParams(-1, -1));
        scheduleChase(chase);
        setContentView(root);
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (responsiveRoot == null) return;
                int width = responsiveRoot.getWidth();
                int height = responsiveRoot.getHeight();
                if (width == 0 || height == 0 || (width == lastWidth && height == lastHeight)) return;
                lastWidth = width;
                lastHeight = height;
                applyResponsiveLayout(width, height);
            }
        });
        dashboard();
        if (closeConnectionRequested) {
            closeConnectionRequested = false;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(this::returnHome);
        }
    }

    private void applyResponsiveLayout(int width, int height) {
        if (bottomNavigation == null) return;
        float density = getResources().getDisplayMetrics().density;
        int widthDp = Math.round(width / density);
        int heightDp = Math.round(height / density);
        boolean compact = widthDp < 420 || heightDp < 600;

        bottomNavigation.setPadding(dp(compact ? 6 : 12), dp(6),
                dp(compact ? 6 : 12), bottomNavigation.getPaddingBottom());
        LinearLayout.LayoutParams navigationParams =
                (LinearLayout.LayoutParams) bottomNavigation.getLayoutParams();
        int inset = Math.max(0, bottomNavigation.getPaddingBottom() - dp(8));
        int desiredHeight = dp(compact ? 64 : 78) + inset;
        if (navigationParams.height != desiredHeight) {
            navigationParams.height = desiredHeight;
            bottomNavigation.setLayoutParams(navigationParams);
        }
        for (MaterialButton button : navigationButtons.values()) {
            button.setTextSize(compact ? 9 : 11);
            button.setPadding(dp(compact ? 2 : 6), 0, dp(compact ? 2 : 6), 0);
        }
        if (content != null && content.getChildCount() > 0) {
            View page = content.getChildAt(0);
            View scroll = page instanceof ScrollView ? page : null;
            if (scroll == null && page instanceof ViewGroup && ((ViewGroup) page).getChildCount() > 0) {
                scroll = ((ViewGroup) page).getChildAt(0);
            }
            if (scroll instanceof ScrollView && ((ViewGroup) scroll).getChildCount() > 0) {
                View body = ((ViewGroup) scroll).getChildAt(0);
                if (body instanceof ViewGroup) {
                    int horizontalPadding = dp(compact ? 14 : 24);
                    body.setPadding(horizontalPadding, dp(compact ? 8 : 12),
                            horizontalPadding, dp(compact ? 12 : 18));
                    body.setMinimumHeight(dp(Math.max(compact ? 760 : 900, heightDp + 180)));
                }
            }
        }
        if (responsiveRoot != null) responsiveRoot.requestLayout();
        if (content != null) {
            content.setClipToPadding(false);
            content.setPadding(0, 0, 0, dp(compact ? 8 : 12));
            content.requestLayout();
        }
    }

    private void configureSystemNavigation(View root) {
        getWindow().setNavigationBarColor(Color.rgb(11, 17, 31));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(true);
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottomInset = insets.getSystemWindowInsetBottom();
            if (bottomNavigation != null) {
                LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) bottomNavigation.getLayoutParams();
                int baseHeight = dp(78);
                if (params.height != baseHeight + bottomInset) {
                    params.height = baseHeight + bottomInset;
                    bottomNavigation.setLayoutParams(params);
                }
                bottomNavigation.setPadding(dp(12), dp(8), dp(12), bottomInset + dp(8));
            }
            return insets;
        });
        root.post(() -> root.requestApplyInsets());
    }

    private void scheduleChase(BlockChaseView chase) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing()) {
                chase.play();
                scheduleChase(chase);
            }
        }, 18000);
    }

    private void dashboard() {
        if (role != null || node != null) return;
        stopSession(false);
        updateActivePage("HOME");
        LinearLayout page = page();
        page.addView(eyebrow("ZAZZPROXY  /  LOCAL NETWORK"));
        page.addView(title("Share without a cloud."));
        page.addView(subtitle("A private room for the people and devices on your Wi-Fi."));
        connectionLabel = subtitle("●  Offline  ·  choose a mode to begin");
        page.addView(connectionLabel);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(sectionLabel("MODE CONTROL"), new LinearLayout.LayoutParams(0, -2, 1));
        resetButton = new MacMotionButton(this);
        resetButton.setText("RESET STATE");
        resetButton.setTextSize(11);
        resetButton.setOnClickListener(v -> resetCards());
        heading.addView(resetButton, new LinearLayout.LayoutParams(-2, dp(42)));
        page.addView(heading);
        page.addView(space(18));
        clientCard = modeCard("CLIENT", "Find files nearby", "Search your local network and pull files from a host.", "SEARCH NEARBY", false);
        serverCard = modeCard("SERVER", "Share from this device", "Choose files and make them available to trusted peers.", "START HOSTING", true);
        page.addView(clientCard);
        page.addView(serverCard);
        if (p().getBoolean("developer_mode", false)) {
            page.addView(action("OPEN SECOND WINDOW",
                    "Launch another ZazzProxy process like a desktop app window.",
                    v -> launchSecondaryWindow()));
        }
        clientStopButton = new MacMotionButton(this);
        clientStopButton.setText("STOP CURRENT SESSION");
        clientStopButton.setTextColor(Color.WHITE);
        clientStopButton.setBackgroundColor(Color.rgb(150, 61, 78));
        clientStopButton.setVisibility(View.GONE);
        clientStopButton.setOnClickListener(v -> {
            stopSession(true);
            dashboard();
        });
        searchIndicator = subtitle("◌  Client search ready · connection requests appear here");
        searchIndicator.setTextColor(Color.rgb(151, 190, 255));
        page.addView(searchIndicator);
        page.addView(space(18));
        put(page);
    }

    private void downloadedPage() {
        updateActivePage("DOWNLOADED");
        LinearLayout page = page();
        page.addView(eyebrow("DOWNLOADS"));
        page.addView(title("Files on this device."));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && !android.os.Environment.isExternalStorageManager()) {
            page.addView(subtitle("Direct file access is disabled. Enable it to save and manage downloads."));
            page.addView(action("ENABLE FILE ACCESS", "Allow ZazzProxy to write Download/zaZzProxy directly.",
                    v -> requestStorageAccess()));
        }
        File folder = currentDownloadFolder();
        page.addView(subtitle(folder == null
                ? "No server download folder exists yet."
                : folder.getAbsolutePath()));
        if (folder == null) {
            page.addView(subtitle("Accept a transfer to create a server folder."));
        } else {
            List<File> files = HostedFileService.filesIn(folder);
            if (files.isEmpty()) {
                page.addView(subtitle("This folder is empty."));
            } else {
                for (File file : files) {
                    page.addView(action(file.getName(),
                            readableBytes(file.length()) + "  •  " + file.getAbsolutePath(),
                            v -> showFileActions(file)));
                }
            }
        }
        page.addView(action("REFRESH DOWNLOADS", "Re-read this server folder from disk.",
                v -> downloadedPage()));
        page.addView(eyebrow("HISTORY"));
        for (ZazzArchive.DownloadRecord record : downloadedRecords) {
            page.addView(subtitle(record.name + "\n" + record.path));
        }
        put(page);
    }

    private File currentDownloadFolder() {
        String serverName = connectedServer == null ? null : connectedServer.name;
        if (serverName == null || serverName.trim().isEmpty()) {
            if (!downloadedRecords.isEmpty()) {
                File parent = new File(downloadedRecords.get(0).path).getParentFile();
                if (parent != null && parent.isDirectory()) return parent;
            }
            File root = HostedFileService.publicDownloadRoot();
            File[] folders = root.listFiles(File::isDirectory);
            return folders == null || folders.length == 0 ? null : folders[0];
        }
        File folder = new File(HostedFileService.publicDownloadRoot(),
                HostedFileService.safeName(serverName));
        return folder.isDirectory() ? folder : null;
    }

    private String readableBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }

    private void showFileActions(File file) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(24));
        card.setCardBackgroundColor(Color.rgb(29, 38, 61));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(20), dp(22), dp(20));
        body.addView(titleSmall(file.getName()));
        body.addView(subtitle(file.getAbsolutePath() + "\n" + readableBytes(file.length())));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        MacMotionButton close = new MacMotionButton(this);
        close.setText("CLOSE");
        close.setOnClickListener(v -> dialog.dismiss());
        MacMotionButton open = new MacMotionButton(this);
        open.setText("OPEN FILE");
        open.setTextColor(Color.WHITE);
        open.setBackgroundColor(Color.rgb(49, 103, 213));
        open.setOnClickListener(v -> {
            try {
                android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".files", file);
                Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, "Open with"));
            } catch (Exception error) {
                MacToast.show(this, "No application can open this file", false);
            }
        });
        MacMotionButton delete = new MacMotionButton(this);
        delete.setText("DELETE");
        delete.setTextColor(Color.WHITE);
        delete.setBackgroundColor(Color.rgb(150, 61, 78));
        delete.setOnClickListener(v -> {
            if (!file.delete()) MacToast.show(this, "Could not delete " + file.getName(), false);
            else {
                MacToast.show(this, "File deleted", true);
                dialog.dismiss();
                downloadedPage();
            }
        });
        actions.addView(close);
        actions.addView(open);
        actions.addView(delete);
        body.addView(actions);
        card.addView(body);
        dialog.setContentView(card);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dialog.show();
    }

    private View modeCard(String badge, String heading, String detail, String action, boolean server) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(26));
        card.setCardBackgroundColor(server ? Color.rgb(30, 66, 77) : Color.rgb(35, 43, 70));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(20), dp(22), dp(18));
        android.graphics.Bitmap avatar = android.graphics.BitmapFactory.decodeFile(ProfileAvatarStore.avatar(this, server).getAbsolutePath());
        if (avatar != null) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(avatar);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setClipToOutline(true);
            image.setBackground(new android.graphics.drawable.GradientDrawable());
            ((android.graphics.drawable.GradientDrawable) image.getBackground()).setShape(android.graphics.drawable.GradientDrawable.OVAL);
            image.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            body.addView(image, new LinearLayout.LayoutParams(dp(64), dp(64)));
        }
        TextView tag = eyebrow(badge);
        tag.setTextColor(server ? Color.rgb(137, 232, 197) : Color.rgb(151, 190, 255));
        body.addView(tag);
        TextView h = title(heading);
        h.setTextSize(22);
        body.addView(h);
        body.addView(subtitle(detail));
        MacMotionButton button = new MacMotionButton(this);
        button.setText(action);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(server ? Color.rgb(22, 145, 105) : Color.rgb(49, 103, 213));
        button.setOnClickListener(v -> {
            if (server) startServer();
            else startClient();
        });
        body.addView(button, new LinearLayout.LayoutParams(-1, dp(52)));
        card.addView(body);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(14);
        card.setLayoutParams(params);
        return card;
    }

    private void startClient() {
        if (!begin(SessionCoordinator.Role.CLIENT)) return;
        if (clientCard != null) clientCard.setVisibility(View.GONE);
        if (serverCard != null) serverCard.setVisibility(View.GONE);
        if (clientStopButton != null) clientStopButton.setVisibility(View.VISIBLE);
        if (resetButton != null) resetButton.setVisibility(View.GONE);
        startSearchPulse();
        connect(false);
        filesPage();
    }

    private void startServer() {
        if (!begin(SessionCoordinator.Role.SERVER)) return;
        updateActivePage("CONNECTION");
        if (clientCard != null) clientCard.setVisibility(View.GONE);
        if (serverCard != null) serverCard.setVisibility(View.GONE);
        if (resetButton != null) resetButton.setVisibility(View.GONE);
        connect(true);
        getSupportFragmentManager().beginTransaction()
                .replace(content.getId(), new ServerFragment())
                .runOnCommit(() -> showServerPage("CONNECTION"))
                .commit();
    }

    private void filesPage() {
        if (role == null && node == null) {
            MacToast.show(this, "Start client or server mode first", false);
            return;
        }
        updateActivePage("CONNECTION");
        if (role == SessionCoordinator.Role.SERVER) {
            // ServerFragment contains both the connection and transfer sections.
            // Keep the live fragment attached while only the navigation state changes.
            showServerPage("CONNECTION");
            return;
        }
        LinearLayout page = page();
        page.addView(profileHeader(false));
        page.addView(eyebrow("CONNECTION"));
        page.addView(title("Connect to a server."));
        connectionPageStatus = subtitle(connectedServer == null
                ? "Connection status: not connected"
                : "Connection successful: " + connectedServer.name);
        page.addView(connectionPageStatus);
        page.addView(subtitle("Servers nearby are listed below. Choose CONNECT to send a request."));
        page.addView(sectionLabel("SERVERS AVAILABLE"));
        discoveredServers = new LinearLayout(this);
        discoveredServers.setOrientation(LinearLayout.VERTICAL);
        page.addView(discoveredServers);
        refreshDiscoveredServers();
        page.addView(action("OPEN TRANSFERS", "View files, hosting, sending, receiving, and progress.",
                v -> transferPage()));
        page.addView(action("SHOW CONNECTION NOTIFICATION", "Restore the persistent connection status notification.",
                v -> ensureConnectionNotification()));
        put(page);
    }

    private void transferPage() {
        if (role == null && node == null) {
            MacToast.show(this, "Start client or server mode first", false);
            return;
        }

        updateActivePage("TRANSFER");
        if (role == SessionCoordinator.Role.SERVER) {
            // ServerFragment already contains the server transfer area. Reusing it
            // avoids replacing an active fragment while network callbacks are arriving.
            showServerPage("TRANSFER");
            return;
        }

        LinearLayout page = page();
        page.addView(profileHeader(false));
        page.addView(eyebrow("FILES"));
        page.addView(title("Your file room."));
        page.addView(subtitle("Discover a server, request access, then choose which files to download."));
        clientStopButton = new MacMotionButton(this);
        clientStopButton.setText("STOP CURRENT SESSION");
        clientStopButton.setTextColor(Color.WHITE);
        clientStopButton.setBackgroundColor(Color.rgb(150, 61, 78));
        clientStopButton.setOnClickListener(v -> {
            returnHome();
        });
        page.addView(clientStopButton, new LinearLayout.LayoutParams(-1, dp(52)));
        MacMotionButton chooseFiles = new MacMotionButton(this);
        chooseFiles.setText("CHOOSE FILES TO SEND");
        chooseFiles.setTextColor(Color.WHITE);
        chooseFiles.setBackgroundColor(Color.rgb(49, 103, 213));
        chooseFiles.setOnClickListener(v -> showFilePicker(true, files -> {
            pendingClientFiles.clear();
            StringBuilder names = new StringBuilder("Selected files:\n");
            for (File file : files) {
                File copy = copyClientFile(file);
                if (copy != null) {
                    pendingClientFiles.add(copy);
                    names.append("• ").append(copy.getName()).append('\n');
                }
            }
            if (clientSelectedFiles != null) clientSelectedFiles.setText(names.toString());
        }));
        page.addView(chooseFiles, new LinearLayout.LayoutParams(-1, dp(52)));
        clientSelectedFiles = subtitle("No files selected for sending.");
        page.addView(clientSelectedFiles);
        page.addView(action("SEND SELECTED FILES", "Ask the server to accept these files.",
                v -> sendSelectedFiles()));
        page.addView(sectionLabel("AVAILABLE FILES"));
        clientFiles = new LinearLayout(this);
        clientFiles.setOrientation(LinearLayout.VERTICAL);
        page.addView(clientFiles);
        transferPanel = transferPanel();
        page.addView(transferPanel);
        for (java.util.Map.Entry<String, TransferState> entry : transferStates.entrySet()) {
            TransferState state = entry.getValue();
            showTransferProgress(entry.getKey(), state.current, state.total, state.upload);
        }
        if (connectedServer != null) {
            lastManifestSignature = "";
            showFiles(connectedServer, receivedManifests.getOrDefault(
                    connectedServer.id, Collections.emptyList()));
        }
        page.addView(action("CLIENT SEARCH", "Listening for servers above. Select CONNECT to send a request.", v -> { }));
        page.addView(action("ACTIVITY", "Transfers and completed downloads appear in the activity feed.", v -> { }));
        put(page);
    }

    public void openTransferPage() {
        transferPage();
    }

    private void showServerPage(String page) {
        androidx.fragment.app.Fragment fragment =
                getSupportFragmentManager().findFragmentById(content.getId());
        if (fragment instanceof ServerFragment
                && fragment.getView() != null
                && fragment.getView().getParent() == content) {
            ((ServerFragment) fragment).showPage(page);
            return;
        }
        // The downloaded page is a normal activity view and temporarily replaces
        // the server fragment. Recreate the live server surface when navigating back.
        getSupportFragmentManager().beginTransaction()
                .replace(content.getId(), new ServerFragment())
                .runOnCommit(() -> showServerPage(page))
                .commit();
    }

    private View profileHeader(boolean server) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(4), 0, dp(12));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setClipToOutline(true);
        image.setBackground(new android.graphics.drawable.GradientDrawable());
        ((android.graphics.drawable.GradientDrawable) image.getBackground())
                .setShape(android.graphics.drawable.GradientDrawable.OVAL);
        image.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(
                ProfileAvatarStore.avatar(this, server).getAbsolutePath());
        image.setImageBitmap(bitmap);
        header.addView(image, new LinearLayout.LayoutParams(dp(58), dp(58)));
        TextView label = subtitle(server ? "SERVER PROFILE" : "CLIENT PROFILE");
        label.setTextColor(server ? Color.rgb(137, 232, 197) : Color.rgb(151, 190, 255));
        String name = p().getString(server ? "server_name" : "client_name", Build.MODEL);
        String endpoint = localHost().isEmpty() ? "Network starting..." : localHost() + ":" + localPort();
        label.setText((server ? "SERVER" : "CLIENT") + "\n" + name + "\n" + endpoint);
        if (!server) clientProfileLabel = label;
        label.setPadding(dp(12), 0, 0, 0);
        header.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        return header;
    }

    private void refreshDiscoveredServers() {
        if (discoveredServers == null) return;
        discoveredServers.removeAllViews();
        if (discoveredServerPeers.isEmpty()) {
            discoveredServers.addView(subtitle("No connection requests yet. Keep this page open while servers are hosting."));
            return;
        }
        for (Peer peer : discoveredServerPeers.values()) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            row.addView(peerAvatar(peer), new LinearLayout.LayoutParams(dp(52), dp(52)));
            TextView details = subtitle(peer.name + "\n" + peer.host + ":" + peer.address().getPort()
                    + "\nNetwork key: MATCHED");
            details.setPadding(dp(12), 0, dp(8), 0);
            row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            MacMotionButton connect = new MacMotionButton(this);
            boolean connected = connectedServer != null
                    && connectedServer.address().getAddress().equals(peer.address().getAddress());
            connect.setText(connected ? "CONNECTED" : "CONNECT");
            connect.setEnabled(!connected);
            connect.setAlpha(connected ? 0.55f : 1f);
            connect.setTextColor(Color.WHITE);
            connect.setBackgroundColor(Color.rgb(49, 103, 213));
            connect.setOnClickListener(v -> {
                if (node == null || role != SessionCoordinator.Role.CLIENT) return;
                stopSearchPulse();
                if (searchIndicator != null) searchIndicator.setText("●  Connecting to " + peer.name);
                node.requestConnection(peer);
                setConnectionStatus("Request sent to " + peer.name);
                MacToast.show(this, "Connection request sent to " + peer.name, true);
            });
            row.addView(connect, new LinearLayout.LayoutParams(dp(112), dp(48)));
            discoveredServers.addView(row);
        }
    }

    private void sendSelectedFiles() {
        if (node == null || role != SessionCoordinator.Role.CLIENT || pendingClientFiles.isEmpty()) {
            MacToast.show(this, "Choose files after connecting to a server", false);
            return;
        }
        Peer server = connectedServer != null ? connectedServer
                : (discoveredServerPeers.isEmpty() ? null
                : discoveredServerPeers.values().iterator().next());
        if (server == null) {
            MacToast.show(this, "Connect to a server first", false);
            return;
        }
        new Thread(() -> {
            for (File file : new java.util.ArrayList<>(pendingClientFiles)) {
                try { node.upload(server, file); }
                catch (Exception error) { runOnUiThread(() -> MacToast.show(this, error.getMessage(), false)); }
            }
            runOnUiThread(() -> {
                setConnectionStatus("File requests sent to the server");
                MacToast.show(this, "File requests sent to the server", true);
            });
        }).start();
    }

    private void confirmClient(Peer peer) {
            android.app.Dialog dialog = new android.app.Dialog(this);
            MaterialCardView card = new MaterialCardView(this);
            card.setRadius(dp(24));
            card.setCardBackgroundColor(Color.rgb(29, 38, 61));
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(22), dp(20), dp(22), dp(20));
            body.addView(title("Connection request"));
            body.addView(subtitle(peer.name + " wants to connect and browse hosted files."));
            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.END);
            MacMotionButton decline = new MacMotionButton(this);
            decline.setText("DECLINE");
            decline.setOnClickListener(v -> {
                node.approveConnection(peer, false);
                setConnectionStatus("Connection declined for " + peer.name);
                dialog.dismiss();
            });
            MacMotionButton accept = new MacMotionButton(this);
            accept.setText("ACCEPT");
            accept.setTextColor(Color.WHITE);
            accept.setBackgroundColor(Color.rgb(22, 145, 105));
            accept.setOnClickListener(v -> {
                node.approveConnection(peer, true);
                setConnectionStatus("Connection accepted for " + peer.name);
                dialog.dismiss();
            });
            actions.addView(decline);
            actions.addView(accept);
            body.addView(actions);
            card.addView(body);
            dialog.setContentView(card);
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.show();
        }

        private void confirmUpload(Peer peer, String transfer, FileInfo file) {
                pendingUploads.put(transfer, new PendingUpload(peer, transfer, file));
            if (role == SessionCoordinator.Role.CLIENT) {
                incomingOfferHandler.removeCallbacks(incomingOfferDialog);
                incomingOfferHandler.postDelayed(incomingOfferDialog, 150);
                return;
            }
            android.app.Dialog dialog = new android.app.Dialog(this);
            MaterialCardView card = new MaterialCardView(this);
            card.setRadius(dp(24));
            card.setCardBackgroundColor(Color.rgb(29, 38, 61));
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(22), dp(20), dp(22), dp(20));
            body.addView(title("Incoming file"));
            body.addView(subtitle(peer.name + " wants to send " + file.name + "."));
            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.END);
            MacMotionButton decline = new MacMotionButton(this);
            decline.setText("DECLINE");
            decline.setOnClickListener(v -> { pendingUploads.remove(transfer); node.approveUpload(peer, transfer, file, false); dialog.dismiss(); });
            MacMotionButton accept = new MacMotionButton(this);
            accept.setText("ACCEPT");
            accept.setTextColor(Color.WHITE);
            accept.setBackgroundColor(Color.rgb(22, 145, 105));
            accept.setOnClickListener(v -> { pendingUploads.remove(transfer); node.approveUpload(peer, transfer, file, true); dialog.dismiss(); });
            actions.addView(decline);
            actions.addView(accept);
            body.addView(actions);
            MacMotionButton downloadAll = new MacMotionButton(this);
            downloadAll.setText("DOWNLOAD ALL INCOMING");
            downloadAll.setTextColor(Color.WHITE);
            downloadAll.setBackgroundColor(Color.rgb(22, 145, 105));
            downloadAll.setOnClickListener(v -> {
                acceptAllUploads();
                dialog.dismiss();
            });
            body.addView(downloadAll, new LinearLayout.LayoutParams(-1, dp(48)));
            card.addView(body);
            dialog.setContentView(card);
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.show();
        }

        private void showIncomingOffers() {
            if (role != SessionCoordinator.Role.CLIENT || pendingUploads.isEmpty()) return;
            android.app.Dialog dialog = new android.app.Dialog(this);
            MaterialCardView card = new MaterialCardView(this);
            card.setRadius(dp(24));
            card.setCardBackgroundColor(Color.rgb(29, 38, 61));
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(dp(22), dp(20), dp(22), dp(20));
            body.addView(title("Incoming files"));
            StringBuilder names = new StringBuilder("The server wants to send:\n");
            for (PendingUpload upload : pendingUploads.values()) {
                names.append("• ").append(upload.file.name).append('\n');
            }
            body.addView(subtitle(names.toString()));
            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.END);
            MacMotionButton decline = new MacMotionButton(this);
            decline.setText("DECLINE ALL");
            decline.setOnClickListener(v -> {
                for (PendingUpload upload : new java.util.ArrayList<>(pendingUploads.values())) {
                    node.approveUpload(upload.peer, upload.transfer, upload.file, false);
                    pendingUploads.remove(upload.transfer);
                }
                dialog.dismiss();
            });
            MacMotionButton accept = new MacMotionButton(this);
            accept.setText("ACCEPT ALL");
            accept.setTextColor(Color.WHITE);
            accept.setBackgroundColor(Color.rgb(22, 145, 105));
            accept.setOnClickListener(v -> {
                acceptAllUploads();
                dialog.dismiss();
            });
            actions.addView(decline);
            actions.addView(accept);
            body.addView(actions);
            card.addView(body);
            dialog.setContentView(card);
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.show();
        }
    private boolean begin(SessionCoordinator.Role requested) {
        if (node != null || role != null) {
            MacToast.show(this, "Stop the current session before changing mode", false);
            return false;
        }
        if (!SessionCoordinator.acquire(requested)) {
            MacToast.show(this, "Another sharing mode is already active", false);
            return false;
        }
        role = requested;
        return true;
    }

    private void connect(boolean server) {
        String key = p().getString("network_key", "");
        if (key.length() < 8) {
            releaseRole();
            MacToast.show(this, "Set a network key in Settings first", false);
            settings();
            return;
        }
        String name = p().getString(server ? "server_name" : "client_name", Build.MODEL);
        new Thread(() -> {
            try {
                ZazzUdpNode made = new ZazzUdpNode(this, name, server, key, callbacks());
                node = made;
                made.start();
                runOnUiThread(() -> {
                    setConnectionStatus(server ? "Hosting securely" : "Searching nearby · requests listed below");
                    if (clientProfileLabel != null && !server) {
                        clientProfileLabel.setText("CLIENT\n" + name + "\n" + localHost() + ":" + localPort());
                    }
                    if (server) {
                        if (clientCard != null) clientCard.setVisibility(View.GONE);
                    } else {
                        if (clientCard != null) clientCard.setVisibility(View.GONE);
                        if (serverCard != null) serverCard.setVisibility(View.GONE);
                        if (clientStopButton != null) clientStopButton.setVisibility(View.VISIBLE);
                    }
                    ensureConnectionNotification();
                    MacToast.show(this, server ? "Server started" : "Client searching", true);
                    SoundFeedback.play(true);
                });
            } catch (Exception error) {
                if (node != null) {
                    node.close();
                    node = null;
                }
                releaseRole();
                runOnUiThread(() -> {
                    setConnectionStatus("Networking failed");
                    if (clientProfileLabel != null) clientProfileLabel.setText("CLIENT\n" + name + "\nNetworking failed");
                    MacToast.show(this, "Could not start networking", false);
                });
            }

        }).start();
    }

    private ZazzUdpNode.Callback callbacks() {
        return new ZazzUdpNode.Callback() {
            public void onPeer(Peer peer) {
                say("Found " + peer.name);
                if (role == SessionCoordinator.Role.CLIENT && peer.server) {
                    runOnUiThread(() -> {
                        discoveredServerPeers.put(peer.id, peer);
                        stopSearchPulse();
                        setConnectionStatus("Server discovered: " + peer.name);
                        if (searchIndicator != null) {
                            searchIndicator.setText("●  Connection request available from " + peer.name);
                            searchIndicator.setVisibility(View.VISIBLE);
                        }
                        refreshDiscoveredServers();
                    });
                } else if (role == SessionCoordinator.Role.SERVER && !peer.server) {
                    runOnUiThread(() -> {
                        androidx.fragment.app.Fragment fragment = getSupportFragmentManager().findFragmentById(content.getId());
                        if (fragment instanceof ServerFragment) ((ServerFragment) fragment).onClientFound(peer);
                    });
                }
            }
            public void onConnectionRequest(Peer peer) {
                if (role == SessionCoordinator.Role.SERVER) runOnUiThread(() -> {
                    androidx.fragment.app.Fragment fragment = getSupportFragmentManager().findFragmentById(content.getId());
                    if (fragment instanceof ServerFragment) ((ServerFragment) fragment).onConnectionRequest(peer);
                });
            }
            public void onConnectionDecision(Peer peer, boolean accepted) {
                if (accepted) {
                    runOnUiThread(() -> {
                        connectedServer = peer;
                        setConnectionStatus("Connection accepted by " + peer.name);
                        if (connectionPageStatus != null) {
                            connectionPageStatus.setText("Connection successful: " + peer.name
                                    + "\n" + peer.host + ":" + peer.address().getPort());
                        }
                        refreshDiscoveredServers();
                        ensureConnectionNotification();
                        MacToast.show(MainActivity.this, "Connected to " + peer.name, true);
                        if (role == SessionCoordinator.Role.CLIENT) node.requestManifest(peer);
                    });
                } else {
                    runOnUiThread(() -> {
                        setConnectionStatus(peer.name + " declined the connection");
                        MacToast.show(MainActivity.this, peer.name + " declined the connection", false);
                    });
                }
            }
            public void onUploadOffer(Peer peer, String transfer, FileInfo file) {
                runOnUiThread(() -> confirmUpload(peer, transfer, file));
            }
            public void onManifest(Peer peer, List<FileInfo> files) { showFiles(peer, files); }
            public void onTransfer(String name, long current, long total, boolean upload) {
                showTransferProgress(name, current, total, upload);
                androidx.fragment.app.Fragment fragment = getSupportFragmentManager().findFragmentById(content.getId());
                if (fragment instanceof ServerFragment) {
                    ((ServerFragment) fragment).onTransfer(name, current, total, upload);
                }
                say((upload ? "Uploading " : "Downloading ") + name);
            }
            public void onComplete(File file) {
                transferredInSession = true;
                runOnUiThread(() -> {
                    finishTransfer(file.getName());
                    MacToast.show(MainActivity.this, "Download completed: " + file.getName(), true);
                    if (role == SessionCoordinator.Role.CLIENT) {
                        for (List<FileInfo> files : receivedManifests.values()) {
                            for (FileInfo info : files) {
                                if (info.name.equals(file.getName())) completedDownloads.add(info.id);
                            }
                        }
                        if (connectedServer != null) showFiles(connectedServer,
                                receivedManifests.getOrDefault(connectedServer.id, Collections.emptyList()));
                    }
                    showTransferSuccess();
                });
                ensureConnectionNotification();
                if (role == SessionCoordinator.Role.CLIENT) recordCompletedDownload(file);
                androidx.fragment.app.Fragment fragment = getSupportFragmentManager().findFragmentById(content.getId());
                if (fragment instanceof ServerFragment) {
                    ((ServerFragment) fragment).finishTransfer(file.getName());
                }
                say("Complete: " + file.getName());
            }
            public void onFailure(String message) {
                setConnectionStatus("Networking failed: " + message);
                say(message);
            }
            public void onPeerRemoved(Peer peer, String reason) {
                say(peer.name + " " + reason);
                runOnUiThread(() -> {
                    if (role == SessionCoordinator.Role.CLIENT) {
                        discoveredServerPeers.remove(peer.id);
                        refreshDiscoveredServers();
                    }
                    androidx.fragment.app.Fragment fragment = getSupportFragmentManager().findFragmentById(content.getId());
                    if (fragment instanceof ServerFragment) ((ServerFragment) fragment).onClientRemoved(peer);
                });
            }
        };
    }

    private void confirmServer(Peer peer) {
        if (node == null || role != SessionCoordinator.Role.CLIENT) return;
        stopSearchPulse();
        if (searchIndicator != null) searchIndicator.setVisibility(View.GONE);
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.HORIZONTAL);
        details.setPadding(dp(24), dp(8), dp(24), 0);
        ImageView avatar = peerAvatar(peer);
        details.addView(avatar, new LinearLayout.LayoutParams(dp(58), dp(58)));
        TextView name = subtitle(peer.name);
        name.setTextColor(Color.WHITE);
        name.setPadding(dp(12), 0, 0, 0);
        details.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        android.app.Dialog dialog = new android.app.Dialog(this);
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(24));
        card.setCardBackgroundColor(Color.rgb(29, 38, 61));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(20), dp(22), dp(20));
        body.addView(title("Connect to " + peer.name + "?"));
        body.addView(details);
        body.addView(subtitle("Request this server's shared files?"));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        MacMotionButton cancel = new MacMotionButton(this);
        cancel.setText("NOT NOW");
        cancel.setOnClickListener(v -> dialog.dismiss());
        MacMotionButton connect = new MacMotionButton(this);
        connect.setText("CONNECT");
        connect.setTextColor(Color.WHITE);
        connect.setBackgroundColor(Color.rgb(49, 103, 213));
        connect.setOnClickListener(v -> {
            if (node != null) {
                node.requestManifest(peer);
                setConnectionStatus("Request sent to " + peer.name);
            }
            dialog.dismiss();
        });
        actions.addView(cancel);
        actions.addView(connect);
        body.addView(actions);
        card.addView(body);
        dialog.setContentView(card);
        android.view.Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dialog.show();
    }

    private ImageView peerAvatar(Peer peer) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setClipToOutline(true);
        image.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        if (peer.avatar.length > 0) {
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(new ByteArrayInputStream(peer.avatar));
            if (bitmap != null) image.setImageBitmap(bitmap);
        } else {
            image.setImageResource(com.totgb.zazzproxy.R.drawable.avatar_blue);
        }
        return image;
    }

    public void managePeer(Peer peer, boolean ban) {
        if (node == null || role != SessionCoordinator.Role.SERVER) return;
        if (ban) node.ban(peer); else node.kick(peer);
        MacToast.show(this, peer.name + (ban ? " banned" : " kicked"), true);
    }

    public List<Peer> connectedPeers() {
        return node == null ? Collections.emptyList() : node.connectedPeers();
    }

    public List<Peer> knownPeers() {
        return node == null ? Collections.emptyList() : node.knownPeers();
    }

    public List<Peer> pendingConnectionRequests() {
        return node == null ? Collections.emptyList() : node.pendingConnections();
    }

    public void respondToConnectionRequest(Peer peer, boolean accepted) {
        if (node != null && role == SessionCoordinator.Role.SERVER) {
            node.approveConnection(peer, accepted);
            setConnectionStatus("Connection " + (accepted ? "accepted for " : "declined for ") + peer.name);
            androidx.fragment.app.Fragment fragment =
                    getSupportFragmentManager().findFragmentById(content.getId());
            if (fragment instanceof ServerFragment) {
                ((ServerFragment) fragment).refreshClientCards();
            }
        }
    }

    public String localHost() {
        return node == null ? "" : node.localHost();
    }

    public int localPort() {
        return node == null ? 0 : node.localPort();
    }

    public String nodeName() {
        return node == null ? Build.MODEL : node.nodeName();
    }

    private void showFiles(Peer peer, List<FileInfo> files) {
        connectedServer = peer;
        runOnUiThread(() -> {
            StringBuilder signature = new StringBuilder(peer.id);
            for (FileInfo file : files) signature.append('|').append(file.id).append(':').append(file.bytes).append(':').append(file.sha256);
            receivedManifests.put(peer.id, new java.util.ArrayList<>(files));
            if (connectedServer != null
                    && connectedServer.address().getAddress().equals(peer.address().getAddress())) {
                receivedManifests.put(connectedServer.id, new java.util.ArrayList<>(files));
            }
            if (clientFiles == null) return;
            if (signature.toString().equals(lastManifestSignature)) return;
            lastManifestSignature = signature.toString();
            clientFiles.removeAllViews();
            if (files.isEmpty()) {
                clientFiles.addView(subtitle("This server has no hosted files yet."));
                return;
            }
            MacMotionButton downloadAll = new MacMotionButton(this);
            downloadAll.setText("DOWNLOAD ALL FILES");
            downloadAll.setTextColor(Color.WHITE);
            downloadAll.setBackgroundColor(Color.rgb(22, 145, 105));
            downloadAll.setOnClickListener(v -> {
                for (FileInfo file : files) {
                    if (!completedDownloads.contains(file.id)) {
                        requestedDownloads.add(file.id);
                        node.requestDownload(peer, file);
                    }
                }
                MacToast.show(this, "All available downloads started", true);
            });
            clientFiles.addView(downloadAll, new LinearLayout.LayoutParams(-1, dp(50)));
            for (FileInfo file : files) {
                if (completedDownloads.contains(file.id)) continue;
                MacMotionButton download = new MacMotionButton(this);
                download.setText("DOWNLOAD  " + file.name);
                download.setOnClickListener(v -> {
                    requestedDownloads.add(file.id);
                    node.requestDownload(peer, file);
                    MacToast.show(this, "Download started", true);
                });
                clientFiles.addView(download, new LinearLayout.LayoutParams(-1, dp(48)));
            }
        });
    }

    private void recordCompletedDownload(File file) {
        new Thread(() -> {
            if (!file.isFile()) {
                runOnUiThread(() -> MacToast.show(this, "Downloaded file could not be verified", false));
                return;
            }
            ZazzArchive.DownloadRecord record = new ZazzArchive.DownloadRecord(
                    file.getName(), file.getAbsolutePath(), file.length(), System.currentTimeMillis());
            try {
                ZazzArchive.rememberDownload(this, record);
                runOnUiThread(() -> {
                    downloadedRecords.removeIf(existing -> existing.path.equals(record.path));
                    downloadedRecords.add(0, record);
                    showDownloadedConfirmation();
                });
            } catch (Exception error) {
                runOnUiThread(() -> MacToast.show(this, "Could not save download history", false));
            }
        }).start();
    }

    private void showDownloadedConfirmation() {
        StringBuilder message = new StringBuilder("All completed files were verified:\n\n");
        for (ZazzArchive.DownloadRecord record : downloadedRecords) {
            message.append(record.name).append("\n").append(record.path).append("\n\n");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Download complete")
                .setMessage(message.toString())
                .setNegativeButton("CLOSE", null)
                .setPositiveButton("VIEW DOWNLOADS", (dialog, which) -> downloadedPage())
                .show();
    }

    public void ensureConnectionNotification() {
        if (node == null || role == null) return;
        android.app.NotificationManager manager =
                getSystemService(android.app.NotificationManager.class);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    CONNECTION_CHANNEL, "ZazzProxy connection", android.app.NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Persistent ZazzProxy connection status");
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class);
        android.app.PendingIntent pending = android.app.PendingIntent.getActivity(this, 1, open,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        Intent close = new Intent(this, MainActivity.class);
        close.setAction(ACTION_CLOSE_CONNECTION);
        android.app.PendingIntent closePending = android.app.PendingIntent.getActivity(this, 2, close,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        String connectionText = role == SessionCoordinator.Role.SERVER
                ? "Server is running on " + localHost() + ":" + localPort()
                : "Connected to " + (connectedServer == null ? "a server" : connectedServer.name);
        String text = notificationTransferStatus.isEmpty()
                ? connectionText : connectionText + " · " + notificationTransferStatus;
        String closeLabel = role == SessionCoordinator.Role.SERVER
                ? "CLOSE SERVER" : "DISCONNECT FROM SERVER";
        android.app.Notification notification = new androidx.core.app.NotificationCompat.Builder(
                this, CONNECTION_CHANNEL)
                .setSmallIcon(com.totgb.zazzproxy.R.drawable.ic_zazzproxy)
                .setContentTitle("ZazzProxy connection active")
                .setContentText(text)
                .setContentIntent(pending)
                .addAction(0, closeLabel, closePending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        manager.notify(CONNECTION_NOTIFICATION_ID, notification);
        notificationHandler.removeCallbacks(notificationCheck);
        notificationHandler.postDelayed(notificationCheck, 3000);
    }

    private void settings() {
        if (role != null || node != null) {
            MacToast.show(this, "Stop the active session from Files first", false);
            return;
        }
        updateActivePage("SETTINGS");
        stopSession(false);
        LinearLayout page = page();
        page.addView(eyebrow("PREFERENCES"));
        page.addView(title("Make it yours."));
        page.addView(subtitle("Identity, trust, appearance, and portable binary catalogs."));
        EditText clientName = field("Client name", p().getString("client_name", Build.MODEL), false);
        EditText serverName = field("Server name", p().getString("server_name", Build.MODEL), false);
        EditText networkKey = field("Network key", p().getString("network_key", ""), true);
        page.addView(labeledField("CLIENT NAME", clientName));
        page.addView(labeledField("SERVER NAME", serverName));
        page.addView(labeledField("NETWORK KEY", networkKey));
        MacMotionButton saveSettings = new MacMotionButton(this);
        saveSettings.setText("SAVE SETTINGS");
        saveSettings.setTextColor(Color.WHITE);
        saveSettings.setBackgroundColor(Color.rgb(49, 103, 213));
        saveSettings.setOnClickListener(v -> {
            String key = networkKey.getText().toString().trim();
            if (key.length() < 8) {
                MacToast.show(this, "Network key must be at least 8 characters", false);
                return;
            }
            p().edit()
                    .putString("client_name", clientName.getText().toString().trim())
                    .putString("server_name", serverName.getText().toString().trim())
                    .putString("network_key", key)
                    .apply();
            MacToast.show(this, "Settings saved", true);
        });
        page.addView(saveSettings, new LinearLayout.LayoutParams(-1, dp(52)));
        MacMotionButton developerMode = new MacMotionButton(this);
        developerMode.setText(p().getBoolean("developer_mode", false)
                ? "DEVELOPER MODE: ON" : "ENABLE DEVELOPER MODE");
        developerMode.setTextColor(Color.WHITE);
        developerMode.setBackgroundColor(Color.rgb(118, 78, 180));
        developerMode.setOnClickListener(v -> confirmDeveloperMode(developerMode));
        page.addView(developerMode, new LinearLayout.LayoutParams(-1, dp(52)));
        page.addView(action("Client profile picture", "Used when this device searches and connects",
                v -> pickAvatar(false)));
        page.addView(action("Server profile picture", "Used when this device hosts files",
                v -> pickAvatar(true)));
        page.addView(action("Export .zaZzProxy", "Save a binary catalog", v -> create(EXPORT_CATALOG, "catalog.zaZzProxy")));
        page.addView(action("Import .zaZzProxy", "Open a binary catalog", v -> open(IMPORT_CATALOG)));
        page.addView(action("Export .zaZzSettings", "Save settings without the key", v -> create(EXPORT_SETTINGS, "settings.zaZzSettings")));
        page.addView(action("Import .zaZzSettings", "Restore portable settings", v -> open(IMPORT_SETTINGS)));
        page.addView(action("Quit ZazzProxy", "Stop networking and leave the application",
                v -> quitApplication()));
        put(page);
    }

    private void confirmDeveloperMode(MacMotionButton button) {
        boolean enabling = !p().getBoolean("developer_mode", false);
        android.app.Dialog dialog = new android.app.Dialog(this);
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(24));
        card.setCardBackgroundColor(Color.rgb(29, 38, 61));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(20), dp(22), dp(20));
        body.addView(title(enabling ? "Enable developer mode?" : "Disable developer mode?"));
        body.addView(subtitle(enabling
                ? "This adds a button on Home that launches a separate ZazzProxy process and window."
                : "The second-window launcher will be removed from Home."));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        MacMotionButton cancel = new MacMotionButton(this);
        cancel.setText("CANCEL");
        cancel.setOnClickListener(v -> dialog.dismiss());
        MacMotionButton confirm = new MacMotionButton(this);
        confirm.setText(enabling ? "ENABLE" : "DISABLE");
        confirm.setTextColor(Color.WHITE);
        confirm.setBackgroundColor(enabling ? Color.rgb(118, 78, 180) : Color.rgb(150, 61, 78));
        confirm.setOnClickListener(v -> {
            p().edit().putBoolean("developer_mode", enabling).apply();
            button.setText(enabling ? "DEVELOPER MODE: ON" : "ENABLE DEVELOPER MODE");
            dialog.dismiss();
            MacToast.show(this, enabling ? "Developer mode enabled" : "Developer mode disabled", true);
        });
        actions.addView(cancel);
        actions.addView(confirm);
        body.addView(actions);
        card.addView(body);
        dialog.setContentView(card);
        android.view.Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        dialog.show();
    }

    private void launchSecondaryWindow() {
        if (!p().getBoolean("developer_mode", false)) {
            MacToast.show(this, "Enable developer mode in Settings first", false);
            return;
        }
        Intent intent = new Intent(this, SecondaryActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    private void quitApplication() {
        stopSession(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finishAffinity();
        }
    }

    public void hostDocument(Uri uri, String name, Runnable done) {
        if (node == null || role != SessionCoordinator.Role.SERVER) {
            MacToast.show(this, "Start server mode before adding files", false);
            return;
        }
        new Thread(() -> {
            try { node.hostCopy(getContentResolver().openInputStream(uri), name); runOnUiThread(done); }
            catch (Exception error) { MacToast.show(this, error.getMessage(), false); }
        }).start();
    }

    public void showFilePicker(boolean multiple, FilePickerDialog.Callback callback) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    && !android.os.Environment.isExternalStorageManager()) {
                requestStorageAccess();
                MacToast.show(this, "Enable direct file access, then open the picker again", false);
                return;
            }
            new FilePickerDialog(this, multiple, callback).show();
        }

    public void hostFiles(List<File> files, Runnable done) {
            if (node == null || role != SessionCoordinator.Role.SERVER) {
                MacToast.show(this, "Start server mode before adding files", false);
                return;
            }
            new Thread(() -> {
                try {
                    for (File file : files) {
                        if (!file.isFile() || !file.canRead()) {
                            throw new java.io.IOException("Cannot read " + file.getAbsolutePath());
                        }
                        try (InputStream input = new java.io.FileInputStream(file)) {
                            node.hostCopy(input, file.getName());
                        }
                    }
                    runOnUiThread(done);
                } catch (Exception error) {
                    runOnUiThread(() -> MacToast.show(this, error.getMessage(), false));
                }
            }).start();
        }

    public void removeHostedFile(String name, Runnable done) {
        if (node == null) return;
        new Thread(() -> {
            try { node.removeHostedFile(name); runOnUiThread(done); }
            catch (Exception error) { MacToast.show(this, error.getMessage(), false); }
        }).start();
    }

    public void removeHostedFiles(List<String> names, Runnable done) {
        if (node == null || names.isEmpty()) return;
        new Thread(() -> {
            try {
                for (String name : names) node.removeHostedFile(name);
                runOnUiThread(done);
            } catch (Exception error) {
                MacToast.show(this, error.getMessage(), false);
            }
        }).start();
    }

    public void stopAllNetworking() { confirmStopIfNeeded(() -> stopSession(true)); }

    public void returnHome() {
        confirmStopIfNeeded(() -> {
            boolean completedTransfer = transferredInSession;
            stopSession(true);
            dashboard();
            if (completedTransfer) showTransferSuccess();
        });
    }

    private void confirmStopIfNeeded(Runnable stopAction) {
        if (transferBars.isEmpty()) {
            stopAction.run();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Transfer in progress")
                .setMessage("Stopping now will interrupt active file transfers. End the current session?")
                .setNegativeButton("KEEP TRANSFERRING", null)
                .setPositiveButton("END SESSION", (dialog, which) -> stopAction.run())
                .show();
    }

    private void stopSession(boolean feedback) {
        boolean wasActive = node != null || role != null;
        notificationHandler.removeCallbacks(notificationCheck);
        android.app.NotificationManager notificationManager =
                getSystemService(android.app.NotificationManager.class);
        if (notificationManager != null) notificationManager.cancel(CONNECTION_NOTIFICATION_ID);
        if (node != null) node.close();
        node = null;
        releaseRole();
        stopSearchPulse();
        promptedServers.clear();
        discoveredServerPeers.clear();
        discoveredServers = null;
        connectedServer = null;
        lastManifestSignature = "";
        transferredInSession = false;
        transferBars.clear();
        transferLabels.clear();
        if (transferPanel != null) transferPanel.removeAllViews();
        if (clientStopButton != null) clientStopButton.setVisibility(View.GONE);
        if (clientCard != null) clientCard.setVisibility(View.VISIBLE);
        if (serverCard != null) serverCard.setVisibility(View.VISIBLE);
        if (resetButton != null) resetButton.setVisibility(View.VISIBLE);
        if (feedback && wasActive) {
            connectionLabel = connectionLabel == null ? null : connectionLabel;
            MacToast.show(this, "Sharing session stopped", false);
            SoundFeedback.play(true);
        }
    }

    private void showTransferSuccess() {
        if (content == null) return;
        FireworksView fireworks = new FireworksView(this);
        content.addView(fireworks, new FrameLayout.LayoutParams(-1, -1));
        fireworks.play();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                () -> content.removeView(fireworks), 3200);
    }

    private void releaseRole() {
        if (role != null) SessionCoordinator.release(role);
        role = null;
    }

    private void startSearchPulse() {
        searchPulse = true;
        searchHandler.post(new Runnable() {
            @Override public void run() {
                if (!searchPulse || searchIndicator == null) return;
                float target = searchIndicator.getTranslationX() == 0 ? dp(18) : 0;
                searchIndicator.setTranslationX(target);
                searchIndicator.setText(target == 0 ? "◌  Searching for servers…" : "◌   Searching for servers…");
                searchHandler.postDelayed(this, 260);
            }
        });
    }

    private void stopSearchPulse() {
        searchPulse = false;
        if (searchIndicator != null) {
            searchHandler.removeCallbacksAndMessages(null);
            searchIndicator.setTranslationX(0);
            searchIndicator.setText("◌  Client search ready · connection requests appear here");
            searchIndicator.setVisibility(View.VISIBLE);
        }
    }

    public void showTransferProgress(String name, long current, long total, boolean upload) {
            transferStates.put(name, new TransferState(current, total, upload));
            ProgressBar bar = transferBars.get(name);
            TextView label = transferLabels.get(name);
            if (bar == null) {
                label = subtitle((upload ? "Uploading  " : "Downloading  ") + name);
                bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                bar.setMax(1000);
                java.util.Random random = new java.util.Random();
                bar.setProgressTintList(android.content.res.ColorStateList.valueOf(
                        Color.rgb(70 + random.nextInt(130), 70 + random.nextInt(130), 70 + random.nextInt(130))));
                transferLabels.put(name, label);
                transferBars.put(name, bar);
                if (transferPanel != null) {
                    transferPanel.addView(label);
                    transferPanel.addView(bar, new LinearLayout.LayoutParams(-1, dp(10)));
                }
            }
            int percent = total <= 0 ? 0 : (int) Math.max(0, Math.min(1000, (current * 1000L) / total));
            bar.setProgress(percent);
            label.setText((upload ? "Uploading  " : "Downloading  ") + name + "  " + (percent / 10) + "%");
            notificationTransferStatus = (upload ? "Uploading " : "Downloading ")
                    + name + " " + (percent / 10) + "%";
            ensureConnectionNotification();
        }

    private void finishTransfer(String name) {
            ProgressBar bar = transferBars.remove(name);
            TextView label = transferLabels.remove(name);
            transferStates.remove(name);
            if (label != null && transferPanel != null) transferPanel.removeView(label);
            if (bar != null && transferPanel != null) transferPanel.removeView(bar);
            if (transferBars.isEmpty()) {
                notificationTransferStatus = "";
                ensureConnectionNotification();
            }
        }

    private LinearLayout transferPanel() {
            LinearLayout panel = new LinearLayout(this);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.addView(sectionLabel("ACTIVE TRANSFERS"));
            return panel;
    }

    private void pickAvatar(boolean server) {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra("zazz_server_avatar", server),
                server ? PICK_SERVER_AVATAR : PICK_CLIENT_AVATAR);
    }

    private void say(String message) {
        runOnUiThread(() -> {
            if (activityLog != null) activityLog.setText(message);
            if (connectionLabel != null) connectionLabel.setText("●  " + message);
        });
    }

    private void setConnectionStatus(String message) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (connectionLabel != null) connectionLabel.setText("●  " + message);
            if (activityLog != null) activityLog.setText(message);
        } else {
            runOnUiThread(() -> setConnectionStatus(message));
        }
    }

    private LinearLayout bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        String[] labels = {"HOME", "CONNECTION", "TRANSFER", "DOWNLOADED", "DEVELOPER", "SETTINGS"};
        for (String label : labels) {
            MaterialButton button = new MacMotionButton(this);
            button.setText(label);
            button.setTextSize(11);
            button.setOnClickListener(v -> {
                if (navigationBusy) return;
                navigationBusy = true;
                v.postDelayed(() -> navigationBusy = false, 350);
                if (label.equals("HOME")) {
                    if (role != null || node != null) {
                        MacToast.show(this, "Stop the active session from Files first", false);
                    } else dashboard();
                } else if (label.equals("CONNECTION")) {
                    filesPage();
                } else if (label.equals("TRANSFER")) {
                    transferPage();
                } else if (label.equals("DOWNLOADED")) {
                    downloadedPage();
                } else if (label.equals("DEVELOPER")) {
                    developer();
                } else {
                    settings();
                }
            });
            navigationButtons.put(label, button);
            bar.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
        }
        updateActivePage(activePage);

        return bar;
    }

    private void updateActivePage(String page) {
        activePage = page;
        for (java.util.Map.Entry<String, MaterialButton> entry : navigationButtons.entrySet()) {
            boolean active = entry.getKey().equals(page);
            entry.getValue().setText(active ? entry.getKey() : navigationIcon(entry.getKey()));
            entry.getValue().setBackgroundColor(entry.getKey().equals(page)
                    ? Color.rgb(49, 103, 213) : Color.rgb(29, 38, 61));
            entry.getValue().setTextColor(Color.WHITE);
        }
    }

    private String navigationIcon(String label) {
        if ("HOME".equals(label)) return "⌂";
        if ("CONNECTION".equals(label)) return "⌁";
        if ("TRANSFER".equals(label)) return "➤";
        if ("DOWNLOADED".equals(label)) return "✓";
        if ("DEVELOPER".equals(label)) return "▣";
        return "⚙";
    }

    private void resetCards() {
        if (role != null || node != null) return;
        if (clientCard != null) clientCard.setVisibility(View.VISIBLE);
        if (serverCard != null) serverCard.setVisibility(View.VISIBLE);
        if (resetButton != null) resetButton.setVisibility(View.GONE);
        MacToast.show(this, "Mode cards restored", true);
    }

    private void developer() {
        if (role != null || node != null) {
            MacToast.show(this, "Stop the active session from Files first", false);
            return;
        }
        updateActivePage("DEVELOPER");
        stopSession(false);
        LinearLayout page = page();
        page.addView(eyebrow("LAB"));
        page.addView(title("Developer room."));
        page.addView(subtitle("Built for private, offline-first sharing on trusted local networks."));
        page.addView(sectionLabel("ABOUT"));
        page.addView(subtitle("Made by totgb for the ZazzProxy project. The application is programmatic Java UI with no remote account service and no cloud dependency."));
        page.addView(sectionLabel("LIBRARIES AND PLATFORM"));
        page.addView(subtitle("AndroidX AppCompat and Fragment, Material Components, Android SDK networking and media APIs, Java Cryptography Architecture, PBKDF2-HMAC-SHA256, AES-GCM, and SHA-256 integrity verification."));
        page.addView(sectionLabel("TRANSPORT"));
        page.addView(subtitle("Encrypted UDP discovery and file transfer on port 39841. Control records and archives use the case-sensitive binary .zaZzProxy and .zaZzSettings formats."));
        page.addView(sectionLabel("LICENSE AND SOURCE"));
        page.addView(subtitle("See the repository LICENSE and README files for project terms, architecture notes, and build instructions."));
        page.addView(action("Run server in background", "Keep hosting alive with a foreground notification",
                v -> startService(new Intent(this, ZazzBackgroundService.class))));
        page.addView(action("Project source", "Open the ZazzProxy repository",
                v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/totgb/ZazzProxy-Android")))));
        put(page);
    }

    private void requestStorageAccess() {
        if (p().getBoolean("storage_access_prompted", false)) return;
        p().edit().putBoolean("storage_access_prompted", true).apply();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException ignored) {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, STORAGE_PERMISSION_REQUEST);
        }
    }

    private LinearLayout page() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setScrollbarFadingEnabled(false);
        scroll.setSmoothScrollingEnabled(true);
        scroll.setNestedScrollingEnabled(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setFocusable(true);
        content.setFocusableInTouchMode(true);
        int widthDp = Math.round(getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
        boolean compact = widthDp < 420;
        int horizontalPadding = dp(compact ? 14 : 24);
        content.setPadding(horizontalPadding, dp(compact ? 8 : 12),
                horizontalPadding, dp(compact ? 12 : 18));
        content.setMinimumHeight(dp(compact ? 760 : 900));
        scroll.addView(content);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return page;
    }

    private void put(View view) {
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(-1, -1));
        slidePage(view);
    }

    private void slidePage(View view) {
        final int distance = getResources().getDisplayMetrics().widthPixels;
        view.setTranslationX(distance);
        view.postDelayed(() -> view.setTranslationX(distance / 2f), 45);
        view.postDelayed(() -> view.setTranslationX(-distance / 5f), 90);
        view.postDelayed(() -> view.setTranslationX(0), 145);
    }
    private TextView eyebrow(String value) { TextView t = text(value, 12); t.setTextColor(Color.rgb(132, 169, 224)); t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private TextView sectionLabel(String value) { return eyebrow(value); }
    private TextView title(String value) { TextView t = text(value, 30); t.setTextColor(Color.WHITE); t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private TextView subtitle(String value) { TextView t = text(value, 15); t.setTextColor(Color.rgb(186, 198, 220)); t.setPadding(0, dp(5), 0, dp(12)); return t; }
    private TextView text(String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); return t; }
    private Space space(int height) { Space s = new Space(this); s.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height))); return s; }
    private View action(String heading, String detail, View.OnClickListener listener) {
        MaterialCardView card = new MaterialCardView(this); card.setRadius(dp(20)); card.setCardBackgroundColor(Color.rgb(29, 38, 61));
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(18), dp(14), dp(18), dp(14));
        body.addView(titleSmall(heading)); body.addView(subtitle(detail)); card.addView(body); card.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.bottomMargin = dp(10); card.setLayoutParams(p); return card;
    }
    private TextView titleSmall(String value) { TextView t = text(value, 16); t.setTextColor(Color.WHITE); t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private EditText field(String hint, String value, boolean secret) { EditText e = new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(); e.setTextColor(Color.WHITE); e.setHintTextColor(Color.rgb(130, 145, 170)); if (secret) e.setInputType(129); return e; }
    private View labeledField(String label, EditText field) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelView = sectionLabel(label);
        row.addView(labelView, new LinearLayout.LayoutParams(dp(112), -2));
        row.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }
    private SharedPreferences p() { return getSharedPreferences("ZazzPrefs", Context.MODE_PRIVATE); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { MacToast.show(this, value, false); }
    private void create(int code, String name) { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, name), code); }
    private void open(int code) { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE), code); }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data); if (result != RESULT_OK || data == null) return;
        try {
            if (code == EXPORT_CATALOG) try (OutputStream out = getContentResolver().openOutputStream(data.getData())) { ZazzArchive.exportManifest(node == null ? Collections.emptyList() : node.hostedFiles(), "ZazzProxy", ZazzArchive.profileAvatar(this, true), out); }
            else if (code == EXPORT_SETTINGS) try (OutputStream out = getContentResolver().openOutputStream(data.getData())) { ZazzArchive.exportSettings(this, out); }
            else if (code == IMPORT_SETTINGS) try (InputStream in = getContentResolver().openInputStream(data.getData())) { ZazzArchive.importSettings(this, in); }
            else if (code == IMPORT_CATALOG) try (InputStream in = getContentResolver().openInputStream(data.getData())) { MacToast.show(this, "Imported " + ZazzArchive.importManifest(in).size() + " files", true); }
            else if (code == PICK_CLIENT_AVATAR) { ProfileAvatarStore.save(this, data.getData(), false); MacToast.show(this, "Client profile picture saved", true); }
            else if (code == PICK_SERVER_AVATAR) { ProfileAvatarStore.save(this, data.getData(), true); MacToast.show(this, "Server profile picture saved", true); }
        } catch (Exception error) { MacToast.show(this, "Binary archive failed", false); }
    }

    private File copyClientFile(File source) {
            File target = new File(getCacheDir(), "send-" + System.nanoTime() + "-"
                    + source.getName().replaceAll("[/\\\\]", "_"));
            try (InputStream input = new java.io.FileInputStream(source);
                 OutputStream output = new java.io.FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) >= 0; ) output.write(buffer, 0, count);
                return target;
            } catch (Exception error) {
                MacToast.show(this, "Could not prepare " + source.getName(), false);
                return null;
        }
    }
    private void applyTheme(String theme) {
        int mode = theme.equals("dark") || theme.equals("graphite") || theme.equals("sunset") ? AppCompatDelegate.MODE_NIGHT_YES : theme.equals("light") ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        AppCompatDelegate.setDefaultNightMode(mode);
    }
    private void acquireWifi() { WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE); if (manager != null) { multicastLock = manager.createMulticastLock("ZazzProxy"); multicastLock.setReferenceCounted(false); multicastLock.acquire(); } }
    @Override protected void onDestroy() { stopSession(false); if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); super.onDestroy(); }
}
