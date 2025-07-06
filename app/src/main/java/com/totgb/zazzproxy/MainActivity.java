package com.totgb.zazzproxy;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class MainActivity extends AppCompatActivity {
    private boolean isServerMode = true;
    private TextView statusText;
    private TextView logText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        // Root layout
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setPadding(32, 32, 32, 32);
        rootLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        // Title
        TextView title = new TextView(this);
        title.setText("ZazzProxy: Offline P2P Sharing");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        rootLayout.addView(title);

        // Mode toggle button
        Button toggleModeBtn = new Button(this);
        toggleModeBtn.setText("Switch to Client Mode");
        rootLayout.addView(toggleModeBtn);

        // Action button
        Button actionBtn = new Button(this);
        actionBtn.setText("Share Files");
        rootLayout.addView(actionBtn);

        // Status area
        statusText = new TextView(this);
        statusText.setText("Status: Ready (Server Mode)");
        statusText.setPadding(0, 24, 0, 8);
        rootLayout.addView(statusText);

        // Log/history area
        TextView logLabel = new TextView(this);
        logLabel.setText("History / Log:");
        rootLayout.addView(logLabel);

        ScrollView logScroll = new ScrollView(this);
        logText = new TextView(this);
        logText.setText("");
        logScroll.addView(logText);
        rootLayout.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Toggle mode logic
        toggleModeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isServerMode = !isServerMode;
                if (isServerMode) {
                    toggleModeBtn.setText("Switch to Client Mode");
                    actionBtn.setText("Share Files");
                    statusText.setText("Status: Ready (Server Mode)");
                    appendLog("Switched to Server Mode");
                } else {
                    toggleModeBtn.setText("Switch to Server Mode");
                    actionBtn.setText("Receive Files");
                    statusText.setText("Status: Ready (Client Mode)");
                    appendLog("Switched to Client Mode");
                }
            }
        });

        // Action button logic (placeholder)
        actionBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isServerMode) {
                    appendLog("[Server] Share Files clicked");
                    // TODO: Implement server logic
                } else {
                    appendLog("[Client] Receive Files clicked");
                    // TODO: Implement client logic
                }
            }
        });

        setContentView(rootLayout);
    }

    private void appendLog(String msg) {
        logText.append(msg + "\n");
    }
}