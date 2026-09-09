package com.totgb.zazzproxy;

import android.content.Context;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public class MainActivity extends AppCompatActivity {
    private final java.util.Set<String> discoveredPeers = new java.util.HashSet<>();
    private TextView statusText;
    private TextView logText;
    private DrawerLayout drawerLayout;

    private float startX;
    private float endX;
    private static final int SWIPE_THRESHOLD = 150;

    private FrameLayout fragmentContainer;
    private MulticastSocket multicastSocket;
    private boolean isScanning = false;

    // Class level buttons for drawer management
    private Button switchToServerBtn;
    private Button switchToClientBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 1. Handle Theme before super.onCreate to avoid double-inflation
        boolean isDarkMode = getSharedPreferences("ZazzPrefs", Context.MODE_PRIVATE)
                .getBoolean("dark_mode", false);
        AppCompatDelegate.setDefaultNightMode(
                isDarkMode ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);

        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        // Multicast lock (Required for most Android devices to receive UDP)
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            WifiManager.MulticastLock lock = wifi.createMulticastLock("ZazzProxyLock");
            lock.acquire();
        }

        // --- UI Construction ---
        drawerLayout = new DrawerLayout(this);
        drawerLayout.setLayoutParams(new DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT));

        FrameLayout frameLayout = new FrameLayout(this);
        frameLayout.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        fragmentContainer = new FrameLayout(this);
        fragmentContainer.setId(View.generateViewId());
        fragmentContainer.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        frameLayout.addView(fragmentContainer);

        // Menu icon
        ImageView menuIcon = new ImageView(this);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(100, 100);
        iconParams.gravity = Gravity.TOP | Gravity.START;
        iconParams.topMargin = 24;
        iconParams.leftMargin = 24;
        menuIcon.setLayoutParams(iconParams);
        menuIcon.setImageResource(R.drawable.menu_svgrepo_com);
        menuIcon.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));
        frameLayout.addView(menuIcon);

        // Theme toggle
        ThemeToggleView themeToggle = new ThemeToggleView(this);
        FrameLayout.LayoutParams themeParams = new FrameLayout.LayoutParams(100, 100);
        themeParams.gravity = Gravity.BOTTOM | Gravity.END;
        themeParams.bottomMargin = 48;
        themeParams.rightMargin = 24;
        themeToggle.setLayoutParams(themeParams);
        themeToggle.setDark(isDarkMode);
        themeToggle.setOnClickListener(v -> {
            boolean dark = !themeToggle.isDark();
            getSharedPreferences("ZazzPrefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("dark_mode", dark).apply();
            AppCompatDelegate.setDefaultNightMode(
                    dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });
        frameLayout.addView(themeToggle);

        // --- Drawer Panel ---
        LinearLayout drawerPanel = new LinearLayout(this);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setBackgroundColor(isDarkMode ? 0xFF333333 : 0xFFEEEEEE);
        DrawerLayout.LayoutParams drawerParams = new DrawerLayout.LayoutParams(
                650, DrawerLayout.LayoutParams.MATCH_PARENT);
        drawerParams.gravity = GravityCompat.START;
        drawerPanel.setLayoutParams(drawerParams);

        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("ZazzProxy Menu");
        drawerTitle.setTextSize(20);
        drawerTitle.setPadding(32, 80, 32, 32);
        drawerPanel.addView(drawerTitle);

        switchToServerBtn = new Button(this);
        switchToServerBtn.setText("Switch to Server Mode");
        switchToServerBtn.setOnClickListener(v -> {
            showServerFragment();
            drawerLayout.closeDrawer(GravityCompat.START);
        });
        drawerPanel.addView(switchToServerBtn);

        switchToClientBtn = new Button(this);
        switchToClientBtn.setText("Switch to Client Mode");
        switchToClientBtn.setVisibility(View.GONE);
        switchToClientBtn.setOnClickListener(v -> {
            getSupportFragmentManager().popBackStack();
            drawerLayout.closeDrawer(GravityCompat.START);
        });
        drawerPanel.addView(switchToClientBtn);

        Button setClientNameBtn = new Button(this);
        setClientNameBtn.setText("Set Client Name");
        setClientNameBtn.setOnClickListener(v -> showNameDialog(false));
        drawerPanel.addView(setClientNameBtn);

        Button setServerNameBtn = new Button(this);
        setServerNameBtn.setText("Set Server Name");
        setServerNameBtn.setOnClickListener(v -> showNameDialog(true));
        drawerPanel.addView(setServerNameBtn);

        drawerLayout.addView(frameLayout);
        drawerLayout.addView(drawerPanel);
        setContentView(drawerLayout);

        // Initial Screen
        showClientScreen();

        // Listen for Fragment changes (Back to Client)
        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                updateDrawerUI(false);
                showClientScreen();
                statusText.setText("Status: Ready (Client Mode)");
                stopAllNetworking();
            }
        });
    }

    private void showClientScreen() {
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(32, 64, 32, 32);
        mainLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("ZazzProxy: P2P Sharing");
        title.setTextSize(22);
        mainLayout.addView(title);

        RoundStartButton startButton = new RoundStartButton(this);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(400, 400);
        btnParams.topMargin = 48;
        startButton.setOnClickListener(v -> {
            if (!isScanning) {
                startDiscovery();
                statusText.setText("Status: Scanning for peers...");
            }
        });
        mainLayout.addView(startButton, btnParams);

        statusText = new TextView(this);
        statusText.setText("Status: Ready (Client Mode)");
        statusText.setPadding(0, 24, 0, 8);
        mainLayout.addView(statusText);

        logText = new TextView(this);
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(logText);
        mainLayout.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        fragmentContainer.removeAllViews();
        fragmentContainer.addView(mainLayout);
    }

    public void showServerFragment() {
        updateDrawerUI(true);
        getSupportFragmentManager().beginTransaction()
                .replace(fragmentContainer.getId(), new ServerFragment())
                .addToBackStack("server")
                .commit();
        startDiscovery(); // Server broadcasts presence
    }

    private void updateDrawerUI(boolean isServer) {
        if (switchToServerBtn != null) switchToServerBtn.setVisibility(isServer ? View.GONE : View.VISIBLE);
        if (switchToClientBtn != null) switchToClientBtn.setVisibility(isServer ? View.VISIBLE : View.GONE);
    }

    public void appendLog(String text) {
        runOnUiThread(() -> {
            if (logText != null) {
                logText.append("\n" + text);
            }
        });
    }

    private void showNameDialog(boolean isServer) {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle(isServer ? "Set Server Name" : "Set Client Name");
        final EditText input = new EditText(this);
        input.setText(getDeviceName(isServer));
        builder.setView(input);
        builder.setPositiveButton("Save", (d, w) -> {
            setDeviceName(input.getText().toString(), isServer);
            appendLog((isServer ? "Server" : "Client") + " name updated.");
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // --- Networking Logic ---

    private void startDiscovery() {
        isScanning = true;
        discoveredPeers.clear();
        appendLog("Discovery Started...");
        new Thread(this::listenForPeers).start();
        new Thread(this::broadcastPresence).start();
    }

    public void stopAllNetworking() {
        isScanning = false;
        if (multicastSocket != null) {
            multicastSocket.close();
            multicastSocket = null;
        }
        appendLog("Networking Stopped.");
    }

    private void broadcastPresence() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            String mode = (getSupportFragmentManager().getBackStackEntryCount() > 0) ? "SERVER" : "CLIENT";
            String message = "ZAZZ_" + mode + ":" + getDeviceName(mode.equals("SERVER"));
            byte[] buffer = message.getBytes();
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length,
                    InetAddress.getByName("239.0.0.1"), 8888);

            while (isScanning) {
                socket.send(packet);
                Thread.sleep(3000);
            }
        } catch (Exception ignored) {}
    }

    private void listenForPeers() {
        try {
            multicastSocket = new MulticastSocket(8888);
            InetAddress group = InetAddress.getByName("239.0.0.1");
            multicastSocket.joinGroup(group);

            byte[] buffer = new byte[1024];
            while (isScanning) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                multicastSocket.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength());
                String ip = packet.getAddress().getHostAddress();

                if (!discoveredPeers.contains(ip)) {
                    discoveredPeers.add(ip);
                    appendLog("Found Peer: " + msg + " (" + ip + ")");
                }
            }
        } catch (Exception e) {
            if (isScanning) appendLog("Error: " + e.getMessage());
        }
    }

    private void setDeviceName(String name, boolean isServer) {
        getSharedPreferences("ZazzPrefs", Context.MODE_PRIVATE).edit()
                .putString(isServer ? "server_name" : "client_name", name).apply();
    }

    private String getDeviceName(boolean isServer) {
        return getSharedPreferences("ZazzPrefs", Context.MODE_PRIVATE)
                .getString(isServer ? "server_name" : "client_name", android.os.Build.MODEL);
    }
}