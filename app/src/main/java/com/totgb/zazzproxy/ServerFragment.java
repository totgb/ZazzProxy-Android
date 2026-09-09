package com.totgb.zazzproxy;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
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
    private TextView fileListContent;
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // 1. Create the ScrollView as the root
        android.widget.ScrollView scrollView = new android.widget.ScrollView(getContext());
        scrollView.setFillViewport(true); // Ensures layout takes full height

        // 2. Main content container
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        // The activity owns the floating menu affordance; reserve it a real row.
        layout.setPadding(dp(24), dp(88), dp(24), dp(88));
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

        fileListContent = new TextView(getContext());
        fileListContent.setText("No files selected.");
        fileListContent.setPadding(0, dp(12), 0, dp(12));
        layout.addView(fileListContent);

        scrollView.addView(layout);
        refreshHostedFiles();
        return scrollView; // Return the scrollview, not the layout!
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 1001 || resultCode != android.app.Activity.RESULT_OK || data == null) return;
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) addDocument(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            addDocument(data.getData());
        }
    }

    private void addDocument(Uri uri) {
        if (!(getActivity() instanceof MainActivity)) return;
        try { requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (SecurityException ignored) { }
        ((MainActivity) getActivity()).hostDocument(uri, documentName(uri), this::refreshHostedFiles);
    }

    private String documentName(Uri uri) {
        try (Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        }
        return "shared-file";
    }

    private void refreshHostedFiles() {
        if (!(getActivity() instanceof MainActivity) || fileListContent == null) return;
        // The actual files stay private to the app; this display deliberately exposes names only.
        java.io.File folder = new java.io.File(requireContext().getFilesDir(), "zazzproxy/shared");
        java.io.File[] files = folder.listFiles();
        if (files == null || files.length == 0) { fileListContent.setText("No files selected."); return; }
        StringBuilder text = new StringBuilder();
        for (java.io.File file : files) if (file.isFile() && !file.getName().startsWith(".") && !file.getName().equals("catalog.zaZzProxy")) text.append("• ").append(file.getName()).append('\n');
        fileListContent.setText(text.length() == 0 ? "No files selected." : text.toString());
        if (text.length() > 0) {
            fileListContent.setText(text + "\nTap here to remove a hosted file.");
            fileListContent.setClickable(true);
            fileListContent.setOnClickListener(v -> showRemoveDialog(files));
        }
    }

    private void showRemoveDialog(java.io.File[] files) {
        java.util.ArrayList<java.io.File> hosted = new java.util.ArrayList<>();
        for (java.io.File file : files) if (file.isFile() && !file.getName().startsWith(".") && !file.getName().equals("catalog.zaZzProxy")) hosted.add(file);
        String[] names = new String[hosted.size()];
        for (int i = 0; i < hosted.size(); i++) names[i] = hosted.get(i).getName();
        new android.app.AlertDialog.Builder(requireContext()).setTitle("Remove hosted file")
                .setItems(names, (d, which) -> new android.app.AlertDialog.Builder(requireContext()).setTitle("Remove " + names[which] + "?")
                        .setMessage("Clients will no longer be able to download it from this server.")
                        .setPositiveButton("Remove", (confirm, ignored) -> ((MainActivity) requireActivity()).removeHostedFile(names[which], this::refreshHostedFiles))
                        .setNegativeButton("Cancel", null).show()).setNegativeButton("Cancel", null).show();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Stop server broadcasting when we leave this fragment
        if (getActivity() instanceof MainActivity) {

            ((MainActivity) getActivity()).stopAllNetworking();
        }
    }

    private int dp(int value) {
        return Math.round(value * requireContext().getResources().getDisplayMetrics().density);
    }
}
