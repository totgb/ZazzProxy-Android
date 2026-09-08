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
import androidx.fragment.app.FragmentTransaction;
import androidx.viewpager.widget.ViewPager;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public class MainActivity extends AppCompatActivity {
    private final java.util.Set<String> discoveredPeers = new java.util.HashSet<>();
    private boolean isServerMode = true;
    private TextView statusText;
    private TextView logText;
    private DrawerLayout drawerLayout;

    private float startX;
    private float endX;
    private static final int SWIPE_THRESHOLD = 150;

    private FrameLayout fragmentContainer;

    private MulticastSocket multicastSocket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        // Enable light/dark mode auto
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);


        // Multicast lock
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            WifiManager.MulticastLock lock = wifi.createMulticastLock("ZazzProxyLock");
            lock.acquire();
        }

        // DrawerLayout root
        drawerLayout = new DrawerLayout(this);
        drawerLayout.setLayoutParams(new DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT));

        // FrameLayout to overlay menu icon on top left
        FrameLayout frameLayout = new FrameLayout(this);
        frameLayout.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Fragment container for swapping screens
        fragmentContainer = new FrameLayout(this);
        fragmentContainer.setId(View.generateViewId());
        FrameLayout.LayoutParams fragParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        fragmentContainer.setLayoutParams(fragParams);
        frameLayout.addView(fragmentContainer);

        // Main content layout
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(32, 32, 32, 32);
        mainLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        DrawerLayout.LayoutParams mainParams = new DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT);
        mainLayout.setLayoutParams(mainParams);

        // Title
        TextView title = new TextView(this);
        title.setText("ZazzProxy: Offline P2P Sharing");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        mainLayout.addView(title);

        // Big round start button
        RoundStartButton startButton = new RoundStartButton(this);
        LinearLayout.LayoutParams startBtnParams = new LinearLayout.LayoutParams(400, 400);
        startBtnParams.gravity = Gravity.CENTER_HORIZONTAL;
        startBtnParams.topMargin = 48;
        mainLayout.addView(startButton, startBtnParams);

        // Status area
        statusText = new TextView(this);
        statusText.setText("Status: Ready (Server Mode)");
        statusText.setPadding(0, 24, 0, 8);
        mainLayout.addView(statusText);

        // Log/history area
        TextView logLabel = new TextView(this);
        logLabel.setText("History / Log:");
        mainLayout.addView(logLabel);

        ScrollView logScroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setText("");
        logScroll.addView(logText);
        mainLayout.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Menu icon (Vector Drawable)
        ImageView menuIcon = new ImageView(this);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(100, 100);
        iconParams.gravity = Gravity.TOP | Gravity.START;
        iconParams.topMargin = 24;
        iconParams.leftMargin = 24;
        menuIcon.setLayoutParams(iconParams);
        menuIcon.setImageResource(R.drawable.menu_svgrepo_com);
        menuIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });
        frameLayout.addView(menuIcon);

        // Theme toggle icon (sun/moon)
        ThemeToggleView themeToggle = new ThemeToggleView(this);
        FrameLayout.LayoutParams themeParams = new FrameLayout.LayoutParams(100, 100);
        themeParams.gravity = Gravity.BOTTOM | Gravity.END;
        themeParams.bottomMargin = 48;
        themeParams.rightMargin = 24;
        themeToggle.setLayoutParams(themeParams);
        // Set initial state based on current mode
        int currentNightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        themeToggle.setDark(currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        themeToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean dark = !themeToggle.isDark();
                themeToggle.setDark(dark);
                AppCompatDelegate.setDefaultNightMode(
                        dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
            }
        });
        frameLayout.addView(themeToggle);

        // Drawer panel (left)
        LinearLayout drawerPanel = new LinearLayout(this);
        drawerPanel.setOrientation(LinearLayout.VERTICAL);
        drawerPanel.setBackgroundColor(0xFFEEEEEE);
        DrawerLayout.LayoutParams drawerParams = new DrawerLayout.LayoutParams(
                600, DrawerLayout.LayoutParams.MATCH_PARENT);
        drawerParams.gravity = GravityCompat.START;
        drawerPanel.setLayoutParams(drawerParams);
        TextView drawerTitle = new TextView(this);
        drawerTitle.setText("ZazzProxy Menu");
        drawerTitle.setTextSize(20);
        drawerTitle.setPadding(32, 64, 32, 32);
        drawerPanel.addView(drawerTitle);

        // Switch to Server Mode button
        Button switchToServerBtn = new Button(this);
        switchToServerBtn.setText("Switch to Server Mode");
        switchToServerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showServerFragment();
                drawerLayout.closeDrawer(GravityCompat.START);
            }
        });
        drawerPanel.addView(switchToServerBtn);

        // Set Client Name
        Button setClientNameBtn = new Button(this);
        setClientNameBtn.setText("Set Client Name");
        setClientNameBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText input = new EditText(MainActivity.this);
                input.setHint("Enter client name");
                Toast.makeText(MainActivity.this, "Feature coming soon", Toast.LENGTH_SHORT).show();
                // TODO: Show dialog to set client name
            }
        });
        drawerPanel.addView(setClientNameBtn);

        // Set Server Name
        Button setServerNameBtn = new Button(this);
        setServerNameBtn.setText("Set Server Name");
        setServerNameBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText input = new EditText(MainActivity.this);
                input.setHint("Enter server name");
                Toast.makeText(MainActivity.this, "Feature coming soon", Toast.LENGTH_SHORT).show();
                // TODO: Show dialog to set server name
            }
        });
        drawerPanel.addView(setServerNameBtn);

        // Settings
        Button settingsBtn = new Button(this);
        settingsBtn.setText("Settings");
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "Settings coming soon", Toast.LENGTH_SHORT).show();
                // TODO: Open settings screen
            }
        });
        drawerPanel.addView(settingsBtn);

        // Add layouts to DrawerLayout
        drawerLayout.addView(frameLayout);
        drawerLayout.addView(drawerPanel);

        // Swipe left to move to server side (right screen)
        frameLayout.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getX();
                        return false;
                    case MotionEvent.ACTION_UP:
                        endX = event.getX();
                        if (startX - endX > SWIPE_THRESHOLD) {
                            // Show server side UI (right screen) using fragment
                            showServerFragment();
                        }
                        return false;
                }
                return false;
            }
        });

        // Show Client screen (mainLayout) by default
        showClientScreen();

        setContentView(drawerLayout);

        // Load Lato font if available
        Typeface lato = null;
        try {
            lato = ResourcesCompat.getFont(this, R.font.lato_regular);
        } catch (Exception e) {
            try {
                lato = Typeface.createFromAsset(getAssets(), "fonts/lato_regular.ttf");
            } catch (Exception ignored) {}
        }
        // Load Poppins font for main screen
        Typeface poppins = null;
        try {
            poppins = ResourcesCompat.getFont(this, R.font.poppins_regular);
        } catch (Exception e) {
            try {
                poppins = Typeface.createFromAsset(getAssets(), "fonts/poppins_regular.ttf");
            } catch (Exception ignored) {}
        }
        // Apply Poppins font to all main screen TextViews if available
        if (poppins != null) {
            title.setTypeface(poppins);
            statusText.setTypeface(poppins);
            logLabel.setTypeface(poppins);
            logText.setTypeface(poppins);
        }
        // Apply Lato font to drawer panel
        if (lato != null) {
            drawerTitle.setTypeface(lato);
            switchToServerBtn.setTypeface(lato);
            setClientNameBtn.setTypeface(lato);
            setServerNameBtn.setTypeface(lato);
            settingsBtn.setTypeface(lato);
        }
    }

    // Implement UDP Discovery
    // Inside MainActivity.java

    private static final String MULTICAST_GROUP = "239.0.0.1";
    private static final int PORT = 8888;
    private boolean isScanning = false;

    private void startDiscovery() {
        isScanning = true;
        discoveredPeers.clear(); // Reset this list of found peers
        appendLog("Starting UDP Discovery...");

        // 1. Start the Listener (to find others)
        new Thread(this::listenForPeers).start();

        // 2. Start the Broadcaster (to be found by others)
        new Thread(this::broadcastPresence).start();
    }

    private void broadcastPresence() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            String message = "ZAZZ_PEER:" + android.os.Build.MODEL;
            byte[] buffer = message.getBytes();
            DatagramPacket packet = new DatagramPacket(
                    buffer, buffer.length, InetAddress.getByName(MULTICAST_GROUP), PORT);

            while (isScanning) {
                socket.send(packet);
                Thread.sleep(3000); // Broadcast every 3 seconds
            }
        } catch (Exception e) {
            runOnUiThread(() -> appendLog("Broadcast Error: " + e.getMessage()));
        }
    }

    private void listenForPeers() {
        try {
            // Initialize the class-level variable, not a local one
            multicastSocket = new MulticastSocket(PORT);
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            multicastSocket.joinGroup(group);

            byte[] buffer = new byte[1024];
            while (isScanning) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

                // This call blocks until a packet is received or the socket is closed
                multicastSocket.receive(packet);

                String received = new String(packet.getData(), 0, packet.getLength());
                String senderIp = packet.getAddress().getHostAddress();

                if (received.startsWith("ZAZZ_PEER:")) {
                    String peerName = received.replace("ZAZZ_PEER:", "");

                    if(!discoveredPeers.contains(senderIp)) {
                        discoveredPeers.add(senderIp);
                        runOnUiThread(() -> appendLog("Found Peer: " + peerName + " at " + senderIp));
                    }
                }
            }
        } catch (Exception e) {
            // When stopDiscovery() calls multicastSocket.close(),
            // receive() throws a SocketException. We only log if it's unexpected.
            if (isScanning) {
                runOnUiThread(() -> appendLog("Listen Error: " + e.getMessage()));
            }
        } finally {
            if (multicastSocket != null && !multicastSocket.isClosed()) {
                multicastSocket.close();
            }
        }
    }
    private void stopDiscovery() {
        isScanning = false;
        if(multicastSocket != null){
            multicastSocket.close();
            multicastSocket = null; // Reset to null after closing
        }
        appendLog("UDP Discovery Stopped.");

        // Note: The sockets will close automatically or on next loop iteration
        // because isScanning is now false.
    }

    private void appendLog(String msg) {
        logText.append(msg + "\n");
    }

    private void showServerFragment() {
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction ft = fm.beginTransaction();
        ft.replace(fragmentContainer.getId(), new ServerFragment());
        ft.addToBackStack(null);
        ft.commit();
        if (statusText != null) {
            statusText.setText("Status: Ready (Server Mode)");
        }
    }

    private void showClientScreen() {
        fragmentContainer.removeAllViews();
        fragmentContainer.addView(createMainLayout());
        if (statusText != null) {
            statusText.setText("Status: Ready (Client Mode)");
        }
    }

    private LinearLayout createMainLayout() {
        // Main content layout
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(32, 32, 32, 32);
        mainLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        // Title
        TextView title = new TextView(this);
        title.setText("ZazzProxy: Offline P2P Sharing");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        mainLayout.addView(title);
        // Big round start button
        RoundStartButton startButton = new RoundStartButton(this);
        LinearLayout.LayoutParams startBtnParams = new LinearLayout.LayoutParams(400, 400);
        startBtnParams.gravity = Gravity.CENTER_HORIZONTAL;
        startBtnParams.topMargin = 48;
        mainLayout.addView(startButton, startBtnParams);
        // Separate Stop Button
        Button stopButton = new Button(this);
        stopButton.setText("STOP DISCOVERY");
        stopButton.setVisibility(View.GONE); // Hidden by default
        LinearLayout.LayoutParams stopBtnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stopBtnParams.gravity = Gravity.CENTER_HORIZONTAL;
        stopBtnParams.topMargin = 24;
        mainLayout.addView(stopButton, stopBtnParams);

        // Status area
        statusText = new TextView(this);
        statusText.setText("Status: Ready (Client Mode)");
        statusText.setPadding(0, 24, 0, 8);
        mainLayout.addView(statusText);
        // Log/history area
        TextView logLabel = new TextView(this);
        logLabel.setText("History / Log:");
        mainLayout.addView(logLabel);
        ScrollView logScroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setText("");
        logScroll.addView(logText);
        mainLayout.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        // Start button logic
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isScanning) {
                    startDiscovery();
                    startButton.setEnabled(false); // Disable to prevent multiple threads
                    stopButton.setVisibility(View.VISIBLE);
                    statusText.setText("Status: Scanning for peers...");
                }
            }
        });
        // Stop Button Logic
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopDiscovery();
                startButton.setEnabled(true);
                stopButton.setVisibility(View.GONE);
                statusText.setText("Status: Ready (Client Mode)");
            }
        });
        return mainLayout;
    }
}


