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
import android.view.Gravity;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.totgb.zazzproxy.archive.ZazzArchive;
import com.totgb.zazzproxy.model.FileInfo;
import com.totgb.zazzproxy.model.Peer;
import com.totgb.zazzproxy.network.ZazzUdpNode;
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
public final class MainActivity extends AppCompatActivity {
    private static final int EXPORT_CATALOG = 41, IMPORT_CATALOG = 42, EXPORT_SETTINGS = 43, IMPORT_SETTINGS = 44,
            PICK_CLIENT_AVATAR = 45, PICK_SERVER_AVATAR = 46, PICK_CLIENT_FILES = 47;
    private FrameLayout content;
    private ZazzUdpNode node;
    private SessionCoordinator.Role role;
    private TextView connectionLabel, activityLog;
    private TextView clientSelectedFiles;
    private MacMotionButton clientStopButton;
    private View clientCard;
    private View serverCard;
    private MacMotionButton resetButton;
    private TextView searchIndicator;
    private LinearLayout discoveredServers;
    private LinearLayout clientFiles;
    private Peer connectedServer;
    private String lastManifestSignature = "";
    private final java.util.Map<String, Peer> discoveredServerPeers = new java.util.LinkedHashMap<>();
    private final java.util.List<File> pendingClientFiles = new java.util.ArrayList<>();
    private boolean transferredInSession;
    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Set<String> promptedServers = new HashSet<>();
    private boolean searchPulse;
    private WifiManager.MulticastLock multicastLock;
    private final java.util.Map<String, MaterialButton> navigationButtons = new java.util.LinkedHashMap<>();
    private String activePage = "HOME";
    private static final int STORAGE_PERMISSION_REQUEST = 901;
    private LinearLayout bottomNavigation;

    @Override public void onCreate(Bundle state) {
        applyTheme(p().getString("theme", "system"));
        super.onCreate(state);
        setContentView(new SplashOverlay(this, this::showDashboard));
    }

    private void showDashboard() {
        acquireWifi();
        requestStorageAccess();
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
        configureSystemNavigation(root);
        BlockChaseView chase = new BlockChaseView(this);
        chase.setClickable(false);
        root.addView(chase, new FrameLayout.LayoutParams(-1, -1));
        scheduleChase(chase);
        setContentView(root);
        dashboard();
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
        clientStopButton = new MacMotionButton(this);
        clientStopButton.setText("STOP CURRENT SESSION");
        clientStopButton.setTextColor(Color.WHITE);
        clientStopButton.setBackgroundColor(Color.rgb(150, 61, 78));
        clientStopButton.setVisibility(View.GONE);
        clientStopButton.setOnClickListener(v -> {
            stopSession(true);
            dashboard();
        });
        searchIndicator = subtitle("◌  Client search is idle");
        searchIndicator.setTextColor(Color.rgb(151, 190, 255));
        page.addView(searchIndicator);
        page.addView(space(18));
        put(page);
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
        if (clientCard != null) clientCard.setVisibility(View.GONE);
        if (serverCard != null) serverCard.setVisibility(View.GONE);
        if (resetButton != null) resetButton.setVisibility(View.GONE);
        connect(true);
        getSupportFragmentManager().beginTransaction()
                .replace(content.getId(), new ServerFragment()).commitNow();
    }

    private void filesPage() {
        if (role == null && node == null) {
            MacToast.show(this, "Start client or server mode first", false);
            return;
        }
        updateActivePage("FILES");
        if (role == SessionCoordinator.Role.SERVER) {
            getSupportFragmentManager().beginTransaction()
                    .replace(content.getId(), new ServerFragment()).commitNow();
            return;
        }
        LinearLayout page = page();
        page.addView(eyebrow("FILES"));
        page.addView(title("Your file room."));
        page.addView(subtitle("Discover a server, request access, then choose which files to download."));
        clientStopButton = new MacMotionButton(this);
        clientStopButton.setText("STOP CURRENT SESSION");
        clientStopButton.setTextColor(Color.WHITE);
        clientStopButton.setBackgroundColor(Color.rgb(150, 61, 78));
        clientStopButton.setOnClickListener(v -> {
            stopSession(true);
            dashboard();
        });
        page.addView(clientStopButton, new LinearLayout.LayoutParams(-1, dp(52)));
        MacMotionButton chooseFiles = new MacMotionButton(this);
        chooseFiles.setText("CHOOSE FILES TO SEND");
        chooseFiles.setTextColor(Color.WHITE);
        chooseFiles.setBackgroundColor(Color.rgb(49, 103, 213));
        chooseFiles.setOnClickListener(v -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK_CLIENT_FILES));
        page.addView(chooseFiles, new LinearLayout.LayoutParams(-1, dp(52)));
        clientSelectedFiles = subtitle("No files selected for sending.");
        page.addView(clientSelectedFiles);
        page.addView(action("SEND SELECTED FILES", "Ask the server to accept these files.",
                v -> sendSelectedFiles()));
        page.addView(sectionLabel("SERVERS NEARBY"));
        discoveredServers = new LinearLayout(this);
        discoveredServers.setOrientation(LinearLayout.VERTICAL);
        page.addView(discoveredServers);
        page.addView(sectionLabel("AVAILABLE FILES"));
        clientFiles = new LinearLayout(this);
        clientFiles.setOrientation(LinearLayout.VERTICAL);
        page.addView(clientFiles);
        refreshDiscoveredServers();
        page.addView(action("SEARCHING NEARBY", "Listening for servers on your local network.", v -> { }));
        page.addView(action("ACTIVITY", "Transfers and completed downloads appear in the activity feed.", v -> { }));
        put(page);
    }

    private void refreshDiscoveredServers() {
        if (discoveredServers == null) return;
        discoveredServers.removeAllViews();
        if (discoveredServerPeers.isEmpty()) {
            discoveredServers.addView(subtitle("No servers found yet. Keep this page open while a server is hosting."));
            return;
        }
        for (Peer peer : discoveredServerPeers.values()) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            row.addView(peerAvatar(peer), new LinearLayout.LayoutParams(dp(52), dp(52)));
            TextView details = subtitle(peer.name + "\n" + peer.host);
            details.setPadding(dp(12), 0, dp(8), 0);
            row.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            MacMotionButton connect = new MacMotionButton(this);
            connect.setText("CONNECT");
            connect.setTextColor(Color.WHITE);
            connect.setBackgroundColor(Color.rgb(49, 103, 213));
            connect.setOnClickListener(v -> {
                if (node == null || role != SessionCoordinator.Role.CLIENT) return;
                stopSearchPulse();
                if (searchIndicator != null) searchIndicator.setText("●  Connecting to " + peer.name);
                node.requestConnection(peer);
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
            runOnUiThread(() -> MacToast.show(this, "File requests sent to the server", true));
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
            decline.setOnClickListener(v -> { node.approveConnection(peer, false); dialog.dismiss(); });
            MacMotionButton accept = new MacMotionButton(this);
            accept.setText("ACCEPT");
            accept.setTextColor(Color.WHITE);
            accept.setBackgroundColor(Color.rgb(22, 145, 105));
            accept.setOnClickListener(v -> { node.approveConnection(peer, true); dialog.dismiss(); });
            actions.addView(decline);
            actions.addView(accept);
            body.addView(actions);
            card.addView(body);
            dialog.setContentView(card);
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.show();
        }

        private void confirmUpload(Peer peer, String transfer, FileInfo file) {
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
            decline.setOnClickListener(v -> { node.approveUpload(peer, transfer, file, false); dialog.dismiss(); });
            MacMotionButton accept = new MacMotionButton(this);
            accept.setText("ACCEPT");
            accept.setTextColor(Color.WHITE);
            accept.setBackgroundColor(Color.rgb(22, 145, 105));
            accept.setOnClickListener(v -> { node.approveUpload(peer, transfer, file, true); dialog.dismiss(); });
            actions.addView(decline);
            actions.addView(accept);
            body.addView(actions);
            card.addView(body);
            dialog.setContentView(card);
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
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
                    connectionLabel.setText(server ? "●  Hosting securely" : "●  Searching nearby");
                    if (server) {
                        if (clientCard != null) clientCard.setVisibility(View.GONE);
                    } else {
                        if (clientCard != null) clientCard.setVisibility(View.GONE);
                        if (serverCard != null) serverCard.setVisibility(View.GONE);
                        if (clientStopButton != null) clientStopButton.setVisibility(View.VISIBLE);
                    }
                    MacToast.show(this, server ? "Server started" : "Client searching", true);
                    SoundFeedback.play(true);
                });
            } catch (Exception error) {
                if (node != null) {
                    node.close();
                    node = null;
                }
                releaseRole();
                MacToast.show(this, "Could not start networking", false);
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
                if (role == SessionCoordinator.Role.SERVER) runOnUiThread(() -> confirmClient(peer));
            }
            public void onConnectionDecision(Peer peer, boolean accepted) {
                if (accepted) {
                    runOnUiThread(() -> {
                        MacToast.show(MainActivity.this, "Connected to " + peer.name, true);
                        if (role == SessionCoordinator.Role.CLIENT) node.requestManifest(peer);
                    });
                } else {
                    runOnUiThread(() -> MacToast.show(MainActivity.this, peer.name + " declined the connection", false));
                }
            }
            public void onUploadOffer(Peer peer, String transfer, FileInfo file) {
                if (role == SessionCoordinator.Role.SERVER) runOnUiThread(() -> confirmUpload(peer, transfer, file));
            }
            public void onManifest(Peer peer, List<FileInfo> files) { showFiles(peer, files); }
            public void onTransfer(String name, long current, long total, boolean upload) { say((upload ? "Uploading " : "Downloading ") + name); }
            public void onComplete(File file) {
                transferredInSession = true;
                say("Complete: " + file.getName());
            }
            public void onFailure(String message) { say(message); }
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
            if (node != null) node.requestManifest(peer);
            if (connectionLabel != null) connectionLabel.setText("●  Connected to " + peer.name);
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

    private void showFiles(Peer peer, List<FileInfo> files) {
        connectedServer = peer;
        runOnUiThread(() -> {
            if (clientFiles == null) return;
            StringBuilder signature = new StringBuilder(peer.id);
            for (FileInfo file : files) signature.append('|').append(file.id).append(':').append(file.bytes).append(':').append(file.sha256);
            if (signature.toString().equals(lastManifestSignature)) return;
            lastManifestSignature = signature.toString();
            clientFiles.removeAllViews();
            if (files.isEmpty()) {
                clientFiles.addView(subtitle("This server has no hosted files yet."));
                return;
            }
            for (FileInfo file : files) {
                MacMotionButton download = new MacMotionButton(this);
                download.setText("DOWNLOAD  " + file.name);
                download.setOnClickListener(v -> {
                    node.requestDownload(peer, file);
                    MacToast.show(this, "Download started", true);
                });
                clientFiles.addView(download, new LinearLayout.LayoutParams(-1, dp(48)));
            }
        });
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
        page.addView(clientName);
        page.addView(serverName);
        page.addView(networkKey);
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

    public void stopAllNetworking() { stopSession(true); }

    public void returnHome() {
        stopSession(true);
        dashboard();
    }

    private void stopSession(boolean feedback) {
        boolean wasActive = node != null || role != null;
        boolean completedTransfer = transferredInSession;
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
        if (clientStopButton != null) clientStopButton.setVisibility(View.GONE);
        if (clientCard != null) clientCard.setVisibility(View.VISIBLE);
        if (serverCard != null) serverCard.setVisibility(View.VISIBLE);
        if (resetButton != null) resetButton.setVisibility(View.VISIBLE);
        if (feedback && wasActive) {
            connectionLabel = connectionLabel == null ? null : connectionLabel;
            MacToast.show(this, "Sharing session stopped", false);
            SoundFeedback.play(true);
            if (completedTransfer) showTransferSuccess();
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
            searchIndicator.setText("◌  Client search is idle");
            searchIndicator.setVisibility(View.VISIBLE);
        }
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

    private LinearLayout bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        String[] labels = {"HOME", "FILES", "SETTINGS", "DEVELOPER"};
        for (String label : labels) {
            MaterialButton button = new MacMotionButton(this);
            button.setText(label);
            button.setTextSize(11);
            button.setOnClickListener(v -> {
                if (label.equals("HOME")) {
                    if (role != null || node != null) {
                        MacToast.show(this, "Stop the active session from Files first", false);
                    } else dashboard();
                } else if (label.equals("FILES")) {
                    filesPage();
                } else if (label.equals("SETTINGS")) {
                    settings();
                } else {
                    developer();
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
            entry.getValue().setBackgroundColor(entry.getKey().equals(page)
                    ? Color.rgb(49, 103, 213) : Color.rgb(29, 38, 61));
            entry.getValue().setTextColor(Color.WHITE);
        }
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
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(12), dp(24), dp(18));
        content.setMinimumHeight(dp(720));
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
    private SharedPreferences p() { return getSharedPreferences("ZazzPrefs", Context.MODE_PRIVATE); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { MacToast.show(this, value, false); }
    private void create(int code, String name) { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, name), code); }
    private void open(int code) { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE), code); }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data); if (result != RESULT_OK || data == null) return;
        try {
            if (code == EXPORT_CATALOG) try (OutputStream out = getContentResolver().openOutputStream(data.getData())) { ZazzArchive.exportManifest(node == null ? Collections.emptyList() : node.hostedFiles(), "ZazzProxy", out); }
            else if (code == EXPORT_SETTINGS) try (OutputStream out = getContentResolver().openOutputStream(data.getData())) { ZazzArchive.exportSettings(this, out); }
            else if (code == IMPORT_SETTINGS) try (InputStream in = getContentResolver().openInputStream(data.getData())) { ZazzArchive.importSettings(this, in); }
            else if (code == IMPORT_CATALOG) try (InputStream in = getContentResolver().openInputStream(data.getData())) { MacToast.show(this, "Imported " + ZazzArchive.importManifest(in).size() + " files", true); }
            else if (code == PICK_CLIENT_AVATAR) { ProfileAvatarStore.save(this, data.getData(), false); MacToast.show(this, "Client profile picture saved", true); }
            else if (code == PICK_SERVER_AVATAR) { ProfileAvatarStore.save(this, data.getData(), true); MacToast.show(this, "Server profile picture saved", true); }
            else if (code == PICK_CLIENT_FILES) {
                StringBuilder names = new StringBuilder("Selected files:\n");
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        File file = copyClientFile(data.getClipData().getItemAt(i).getUri());
                        if (file != null) {
                            pendingClientFiles.add(file);
                            names.append("• ").append(file.getName()).append('\n');
                        }
                    }
                } else if (data.getData() != null) {
                    File file = copyClientFile(data.getData());
                    if (file != null) {
                        pendingClientFiles.add(file);
                        names.append("• ").append(file.getName()).append('\n');
                    }
                }
                if (clientSelectedFiles != null) clientSelectedFiles.setText(names.toString());
                MacToast.show(this, "Files selected for sending", true);
            }
        } catch (Exception error) { MacToast.show(this, "Binary archive failed", false); }
    }

    private File copyClientFile(Uri uri) {
        String name = uri.getLastPathSegment() == null ? "shared-file" : uri.getLastPathSegment();
        name = name.replaceAll("[/\\\\]", "_");
        File target = new File(getCacheDir(), "send-" + System.nanoTime() + "-" + name);
        try (InputStream input = getContentResolver().openInputStream(uri);
             OutputStream output = new java.io.FileOutputStream(target)) {
            if (input == null) throw new java.io.IOException("Could not open selected file");
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count);
            return target;
        } catch (Exception error) {
            MacToast.show(this, "Could not prepare selected file", false);
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
