package com.totgb.zazzproxy.ui;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class ServerFragment extends Fragment {
    private TextView fileListContent;
    private TextView removeHint;
    private LinearLayout peerList;
    private final java.util.Map<String, com.totgb.zazzproxy.model.Peer> peers = new java.util.LinkedHashMap<>();
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // 1. Create the ScrollView as the root
        android.widget.ScrollView scrollView = new android.widget.ScrollView(getContext());
        scrollView.setFillViewport(true); // Ensures layout takes full height
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setSmoothScrollingEnabled(true);

        // 2. Main content container
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        // The activity owns the floating menu affordance; reserve it a real row.
        layout.setPadding(dp(24), dp(18), dp(24), dp(24));
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setMinimumHeight(dp(900));

        TextView title = new TextView(getContext());
        title.setText("Server Mode Active");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView subtitle = new TextView(getContext());
        subtitle.setText("Select files to make them available to peers.");
        subtitle.setPadding(0, 16, 0, 48);
        layout.addView(subtitle);

        MacMotionButton stopServerButton = new MacMotionButton(requireContext());
        stopServerButton.setText("STOP CURRENT SESSION");
        stopServerButton.setTextColor(android.graphics.Color.WHITE);
        stopServerButton.setBackgroundColor(android.graphics.Color.rgb(150, 61, 78));
        stopServerButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).returnHome();
            }
        });
        addSpaced(layout, stopServerButton, 52);

        TextView peersTitle = new TextView(getContext());
        peersTitle.setText("Connected clients");
        peersTitle.setTextSize(19);
        peersTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        peersTitle.setPadding(0, dp(28), 0, dp(8));
        layout.addView(peersTitle);
        peerList = new LinearLayout(getContext());
        peerList.setOrientation(LinearLayout.VERTICAL);
        layout.addView(peerList);

        MacMotionButton pickFilesBtn = new MacMotionButton(requireContext());
        pickFilesBtn.setText("Select Files to Host");
        pickFilesBtn.setTextColor(android.graphics.Color.WHITE);
        pickFilesBtn.setBackgroundColor(android.graphics.Color.rgb(22, 145, 105));
        pickFilesBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, 1001);
        });
        addSpaced(layout, pickFilesBtn, 52);

        // 3. File List Area (This will now grow without covering buttons)
        TextView fileListHeader = new TextView(getContext());
        fileListHeader.setText("\nCurrently Hosting:");
        fileListHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(fileListHeader);

        fileListContent = new TextView(getContext());
        fileListContent.setText("No files selected.");
        fileListContent.setPadding(0, dp(12), 0, dp(4));
        fileListContent.setLineSpacing(dp(4), 1f);
        layout.addView(fileListContent);
        removeHint = new TextView(getContext());
        removeHint.setTextColor(android.graphics.Color.rgb(151, 190, 255));
        removeHint.setTextSize(14);
        removeHint.setPadding(0, 0, 0, dp(18));
        removeHint.setVisibility(View.GONE);
        layout.addView(removeHint);

        scrollView.addView(layout, new android.widget.ScrollView.LayoutParams(-1, -2));
        if (getActivity() instanceof MainActivity) {
            for (com.totgb.zazzproxy.model.Peer peer : ((MainActivity) getActivity()).connectedPeers()) {
                if (!peer.server) peers.put(peer.id, peer);
            }
        }
        refreshPeers();
        refreshHostedFiles();
        return scrollView; // Return the scrollview, not the layout!
    }

    public void onClientFound(com.totgb.zazzproxy.model.Peer peer) {
        if (!peer.server) {
            peers.put(peer.id, peer);
            refreshPeers();
        }
    }

    public void onClientRemoved(com.totgb.zazzproxy.model.Peer peer) {
        peers.remove(peer.id);
        refreshPeers();
    }

    private void refreshPeers() {
        if (peerList == null) return;
        peerList.removeAllViews();
        if (peers.isEmpty()) {
            TextView empty = new TextView(getContext());
            empty.setText("No clients connected yet.");
            peerList.addView(empty);
            return;
        }
        for (com.totgb.zazzproxy.model.Peer peer : peers.values()) {
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView avatar = peer.avatar.length == 0 ? null : new ImageView(requireContext());
            if (avatar == null) {
                avatar = new ImageView(requireContext());
                avatar.setImageResource(com.totgb.zazzproxy.R.drawable.avatar_green);
            } else {
                avatar.setImageBitmap(android.graphics.BitmapFactory.decodeStream(
                        new java.io.ByteArrayInputStream(peer.avatar)));
            }
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setClipToOutline(true);
            avatar.setBackground(new android.graphics.drawable.GradientDrawable());
            ((android.graphics.drawable.GradientDrawable) avatar.getBackground()).setShape(android.graphics.drawable.GradientDrawable.OVAL);
            avatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            row.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));
            TextView name = new TextView(getContext());
            name.setText("●  " + peer.name + "\n    " + peer.host);
            name.setTextSize(16);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            MacMotionButton kick = new MacMotionButton(requireContext());
            kick.setText("KICK");
            kick.setTextColor(android.graphics.Color.WHITE);
            kick.setBackgroundColor(android.graphics.Color.rgb(49, 103, 213));
            kick.setOnClickListener(v -> managePeer(peer, false));
            row.addView(kick);
            MacMotionButton ban = new MacMotionButton(requireContext());
            ban.setText("BAN");
            ban.setTextColor(android.graphics.Color.WHITE);
            ban.setBackgroundColor(android.graphics.Color.rgb(150, 61, 78));
            ban.setOnClickListener(v -> managePeer(peer, true));
            row.addView(ban);
            peerList.addView(row);
        }
    }

    private void managePeer(com.totgb.zazzproxy.model.Peer peer, boolean ban) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).managePeer(peer, ban);
            peers.remove(peer.id);
            refreshPeers();
        }
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
        if (files == null || files.length == 0) {
            fileListContent.setText("No files selected.");
            removeHint.setVisibility(View.GONE);
            return;
        }
        StringBuilder text = new StringBuilder();
        for (java.io.File file : files) if (file.isFile() && !file.getName().startsWith(".") && !file.getName().equals("catalog.zaZzProxy")) text.append("• ").append(file.getName()).append('\n');
        fileListContent.setText(text.length() == 0 ? "No files selected." : text.toString());
        if (text.length() > 0) {
            removeHint.setText("Tap here to remove a hosted file.");
            removeHint.setVisibility(View.VISIBLE);
            removeHint.setOnClickListener(v -> showRemoveDialog(files));
        } else {
            removeHint.setVisibility(View.GONE);
        }
    }

    private void addSpaced(LinearLayout parent, View child, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(height));
        params.bottomMargin = dp(12);
        parent.addView(child, params);
    }

    private void showRemoveDialog(java.io.File[] files) {
        java.util.ArrayList<java.io.File> hosted = new java.util.ArrayList<>();
        for (java.io.File file : files) if (file.isFile() && !file.getName().startsWith(".") && !file.getName().equals("catalog.zaZzProxy")) hosted.add(file);
        android.app.Dialog dialog = new android.app.Dialog(requireContext());
        com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(requireContext());
        card.setRadius(dp(24));
        card.setCardBackgroundColor(android.graphics.Color.rgb(29, 38, 61));
        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(18), dp(20), dp(16));
        TextView heading = new TextView(requireContext());
        heading.setText("Remove hosted files");
        heading.setTextColor(android.graphics.Color.WHITE);
        heading.setTextSize(22);
        body.addView(heading);
        TextView hint = new TextView(requireContext());
        hint.setText("Select one or more files to remove.");
        hint.setTextColor(android.graphics.Color.rgb(186, 198, 220));
        body.addView(hint);
        android.widget.ScrollView choicesScroll = new android.widget.ScrollView(requireContext());
        choicesScroll.setFillViewport(false);
        LinearLayout choices = new LinearLayout(requireContext());
        choices.setOrientation(LinearLayout.VERTICAL);
        java.util.ArrayList<android.widget.CheckBox> checks = new java.util.ArrayList<>();
        for (java.io.File file : hosted) {
            android.widget.CheckBox check = new android.widget.CheckBox(requireContext());
            check.setText(file.getName());
            check.setTextColor(android.graphics.Color.WHITE);
            checks.add(check);
            choices.addView(check);
        }
        choicesScroll.addView(choices, new android.widget.ScrollView.LayoutParams(-1, -2));
        body.addView(choicesScroll, new LinearLayout.LayoutParams(-1, dp(300)));
        LinearLayout actions = new LinearLayout(requireContext());
        actions.setGravity(Gravity.END);
        MacMotionButton cancel = new MacMotionButton(requireContext());
        cancel.setText("CANCEL");
        cancel.setOnClickListener(v -> dialog.dismiss());
        MacMotionButton remove = new MacMotionButton(requireContext());
        remove.setText("REMOVE SELECTED");
        remove.setTextColor(android.graphics.Color.WHITE);
        remove.setBackgroundColor(android.graphics.Color.rgb(150, 61, 78));
        remove.setOnClickListener(v -> {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            for (int i = 0; i < checks.size(); i++) if (checks.get(i).isChecked()) names.add(hosted.get(i).getName());
            if (names.isEmpty()) return;
            dialog.dismiss();
            ((MainActivity) requireActivity()).removeHostedFiles(names, this::refreshHostedFiles);
        });
        actions.addView(cancel);
        actions.addView(remove);
        body.addView(actions);
        card.addView(body);
        dialog.setContentView(card);
        android.view.Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        dialog.show();
    }

    private int dp(int value) {
        return Math.round(value * requireContext().getResources().getDisplayMetrics().density);
    }
}
