package com.totgb.zazzproxy;

import android.content.Context;
import android.graphics.Typeface;
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

public class MainActivity extends AppCompatActivity {
    private boolean isServerMode = true;
    private TextView statusText;
    private TextView logText;
    private DrawerLayout drawerLayout;

    private float startX;
    private float endX;
    private static final int SWIPE_THRESHOLD = 150;

    private FrameLayout fragmentContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        // Enable light/dark mode auto
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

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
                appendLog("Start button pressed");
                // TODO: Implement start logic
            }
        });
        return mainLayout;
    }
}