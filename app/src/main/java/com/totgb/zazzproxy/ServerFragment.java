package com.totgb.zazzproxy;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class ServerFragment extends Fragment {
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // 1. Create the ScrollView as the root
        android.widget.ScrollView scrollView = new android.widget.ScrollView(getContext());
        scrollView.setFillViewport(true); // Ensures layout takes full height

        // 2. Main content container
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 64, 48, 48);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(getContext());
        title.setText("Server Mode Active");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView subtitle = new TextView(getContext());
        subtitle.setText("Select files to make them available to peers.");
        subtitle.setPadding(0, 16, 0, 48);
        layout.addView(subtitle);

        Button pickFilesBtn = new Button(getContext());
        pickFilesBtn.setText("Select Files to Host");
        pickFilesBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, 1001);
        });
        layout.addView(pickFilesBtn);

        // 3. File List Area (This will now grow without covering buttons)
        TextView fileListHeader = new TextView(getContext());
        fileListHeader.setText("\nCurrently Hosting:");
        fileListHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(fileListHeader);

        TextView fileListContent = new TextView(getContext());
        fileListContent.setText("No files selected.");
        fileListContent.setPadding(0, 16, 0, 16);
        layout.addView(fileListContent);

        scrollView.addView(layout);
        return scrollView; // Return the scrollview, not the layout!
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Stop server broadcasting when we leave this fragment
        if (getActivity() instanceof MainActivity) {

            ((MainActivity) getActivity()).stopAllNetworking();
        }
    }
}
