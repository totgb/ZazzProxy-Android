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
import android.widget.ProgressBar;
import android.content.res.ColorStateList;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.totgb.zazzproxy.settings.ProfileAvatarStore;

public class ServerFragment extends Fragment {
    private TextView fileListContent;
    private TextView removeHint;
    private TextView serverIdentity;
    private LinearLayout peerList;
    private LinearLayout requestList;
    private LinearLayout transferPanel;
    private final java.util.Map<String, ProgressBar> transferBars = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, TextView> transferLabels = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, com.totgb.zazzproxy.model.Peer> peers = new java.util.LinkedHashMap<>();
    private final java.util.Set<String> requestedPeers = new java.util.HashSet<>();
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // 1. Create the ScrollView as the root
        android.widget.ScrollView scrollView = new android.widget.ScrollView(getContext());
        scrollView.setFillViewport(true); // Ensures layout takes full height
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.setScrollbarFadingEnabled(false);
        scrollView.setSmoothScrollingEnabled(true);
        scrollView.setNestedScrollingEnabled(true);
        scrollView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);

        // 2. Main content container
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        // The activity owns the floating menu affordance; reserve it a real row.
        layout.setPadding(dp(24), dp(18), dp(24), dp(24));
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setMinimumHeight(dp(900));
        LinearLayout topHalf = new LinearLayout(getContext());
        topHalf.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bottomHalf = new LinearLayout(getContext());
        bottomHalf.setOrientation(LinearLayout.VERTICAL);

        ImageView profile = new ImageView(requireContext());
        profile.setScaleType(ImageView.ScaleType.CENTER_CROP);
        profile.setClipToOutline(true);
        profile.setBackground(new android.graphics.drawable.GradientDrawable());
        ((android.graphics.drawable.GradientDrawable) profile.getBackground())
                .setShape(android.graphics.drawable.GradientDrawable.OVAL);
        profile.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        android.graphics.Bitmap profileBitmap = android.graphics.BitmapFactory.decodeFile(
                ProfileAvatarStore.avatar(requireContext(), true).getAbsolutePath());
        profile.setImageBitmap(profileBitmap);
        topHalf.addView(profile, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView title = new TextView(getContext());
        title.setText("Server Mode Active");
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        topHalf.addView(title);

        TextView subtitle = new TextView(getContext());
        String endpoint = getActivity() instanceof MainActivity
                ? ((MainActivity) getActivity()).localHost() + ":" + ((MainActivity) getActivity()).localPort()
                : "";
        serverIdentity = subtitle;
        subtitle.setText("Server: " + (getActivity() instanceof MainActivity
                ? ((MainActivity) getActivity()).nodeName() : "ZazzProxy")
                + "\n" + endpoint + "\nSelect files to make them available to peers.");
        subtitle.setPadding(0, 16, 0, 48);
        topHalf.addView(subtitle);

        MacMotionButton stopServerButton = new MacMotionButton(requireContext());
        stopServerButton.setText("STOP CURRENT SESSION");
        stopServerButton.setTextColor(android.graphics.Color.WHITE);
        stopServerButton.setBackgroundColor(android.graphics.Color.rgb(150, 61, 78));
        stopServerButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).returnHome();
            }
        });
        addSpaced(topHalf, stopServerButton, 52);
        MacMotionButton transferButton = new MacMotionButton(requireContext());
        transferButton.setText("OPEN TRANSFERS");
        transferButton.setTextColor(android.graphics.Color.WHITE);
        transferButton.setBackgroundColor(android.graphics.Color.rgb(49, 103, 213));
        transferButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openTransferPage();
            }
        });
        addSpaced(topHalf, transferButton, 48);

        TextView peersTitle = new TextView(getContext());
        peersTitle.setText("Clients nearby");
        peersTitle.setTextSize(19);
        peersTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        peersTitle.setPadding(0, dp(28), 0, dp(8));
        topHalf.addView(peersTitle);
        peerList = new LinearLayout(getContext());
        peerList.setOrientation(LinearLayout.VERTICAL);
        topHalf.addView(peerList);
        requestList = new LinearLayout(getContext());
        requestList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout requestsHeading = new LinearLayout(getContext());
        requestsHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView requestsTitle = new TextView(getContext());
        requestsTitle.setText("Connection requests");
        requestsTitle.setTextColor(android.graphics.Color.rgb(132, 169, 224));
        requestsTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        requestsTitle.setPadding(0, dp(20), 0, dp(8));
        requestsHeading.addView(requestsTitle, new LinearLayout.LayoutParams(0, -2, 1));
        MacMotionButton refreshRequests = new MacMotionButton(requireContext());
        refreshRequests.setText("REFRESH");
        refreshRequests.setTextColor(android.graphics.Color.WHITE);
        refreshRequests.setBackgroundColor(android.graphics.Color.rgb(49, 103, 213));
        refreshRequests.setOnClickListener(v -> refreshRequests());
        requestsHeading.addView(refreshRequests, new LinearLayout.LayoutParams(dp(100), dp(44)));
        bottomHalf.addView(requestsHeading);
        bottomHalf.addView(requestList);
        transferPanel = new LinearLayout(getContext());
        transferPanel.setOrientation(LinearLayout.VERTICAL);
        TextView transferTitle = new TextView(getContext());
        transferTitle.setText("ACTIVE TRANSFERS");
        transferTitle.setTextColor(android.graphics.Color.rgb(132, 169, 224));
        transferTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        transferPanel.addView(transferTitle);
        bottomHalf.addView(transferPanel);

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
        addSpaced(topHalf, pickFilesBtn, 52);

        // 3. File List Area (This will now grow without covering buttons)
        TextView fileListHeader = new TextView(getContext());
        fileListHeader.setText("\nCurrently Hosting:");
        fileListHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        topHalf.addView(fileListHeader);

        fileListContent = new TextView(getContext());
        fileListContent.setText("No files selected.");
        fileListContent.setPadding(0, dp(12), 0, dp(4));
        fileListContent.setLineSpacing(dp(4), 1f);
        topHalf.addView(fileListContent);
        removeHint = new TextView(getContext());
        removeHint.setTextColor(android.graphics.Color.rgb(151, 190, 255));
        removeHint.setTextSize(14);
        removeHint.setPadding(0, 0, 0, dp(18));
        removeHint.setVisibility(View.GONE);
        topHalf.addView(removeHint);
        TextView separator = new TextView(getContext());
        separator.setText("TRANSFER AREA");
        separator.setTextColor(android.graphics.Color.rgb(132, 169, 224));
        separator.setTypeface(null, android.graphics.Typeface.BOLD);
        separator.setPadding(0, dp(16), 0, dp(8));
        layout.addView(topHalf, new LinearLayout.LayoutParams(-1, 0, 1));
        layout.addView(separator);
        layout.addView(bottomHalf, new LinearLayout.LayoutParams(-1, 0, 1));

        scrollView.addView(layout, new android.widget.ScrollView.LayoutParams(-1, -2));
        if (getActivity() instanceof MainActivity) {
            for (com.totgb.zazzproxy.model.Peer peer : ((MainActivity) getActivity()).knownPeers()) {
                if (!peer.server) peers.put(peer.id, peer);
            }
        }
        refreshPeers();
        refreshRequests();
        refreshHostedFiles();
        scrollView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            int width = scrollView.getWidth();
            if (width == 0) return;
            boolean compact = width / getResources().getDisplayMetrics().density < 420;
            int horizontal = dp(compact ? 14 : 24);
            layout.setPadding(horizontal, dp(compact ? 10 : 18),
                    horizontal, dp(compact ? 14 : 24));
            layout.setMinimumHeight(dp(Math.max(compact ? 760 : 1000,
                    Math.round(scrollView.getHeight() / getResources().getDisplayMetrics().density) + 180)));
        });
        return scrollView; // Return the scrollview, not the layout!
    }

    public void onClientFound(com.totgb.zazzproxy.model.Peer peer) {
        if (!peer.server) {
            peers.put(peer.id, peer);
            refreshPeers();
            refreshIdentity();
        }
    }

    private void refreshIdentity() {
        if (serverIdentity == null || !(getActivity() instanceof MainActivity)) return;
        MainActivity activity = (MainActivity) getActivity();
        serverIdentity.setText("Server: " + activity.nodeName()
                + "\n" + activity.localHost() + ":" + activity.localPort()
                + "\nSelect files to make them available to peers.");
    }

    public void onConnectionRequest(com.totgb.zazzproxy.model.Peer peer) {
        requestedPeers.add(peer.id);
        peers.put(peer.id, peer);
        refreshPeers();
        refreshRequests();
    }

        private void refreshRequests() {
            if (requestList == null || !(getActivity() instanceof MainActivity)) return;
            requestList.removeAllViews();
            java.util.List<com.totgb.zazzproxy.model.Peer> requests =
                    ((MainActivity) getActivity()).pendingConnectionRequests();
            if (requests.isEmpty()) {
                TextView empty = new TextView(getContext());
                empty.setText("No pending client requests.");
                requestList.addView(empty);
                return;
            }
            for (com.totgb.zazzproxy.model.Peer peer : requests) {
                LinearLayout card = new LinearLayout(getContext());
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(10), dp(10), dp(10), dp(10));
                card.setBackgroundColor(android.graphics.Color.rgb(29, 38, 61));
                LinearLayout row = new LinearLayout(getContext());
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = new TextView(getContext());
                name.setText(peer.name + "\n" + peer.host + ":" + peer.address().getPort());
                name.setTextColor(android.graphics.Color.WHITE);
                row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                MacMotionButton decline = new MacMotionButton(requireContext());
                decline.setText("DECLINE");
                decline.setOnClickListener(v -> {
                    ((MainActivity) getActivity()).respondToConnectionRequest(peer, false);
                    refreshRequests();
                });
                row.addView(decline, new LinearLayout.LayoutParams(dp(92), dp(44)));
                MacMotionButton accept = new MacMotionButton(requireContext());
                accept.setText("ACCEPT");
                accept.setTextColor(android.graphics.Color.WHITE);
                accept.setBackgroundColor(android.graphics.Color.rgb(22, 145, 105));
                accept.setOnClickListener(v -> {
                    ((MainActivity) getActivity()).respondToConnectionRequest(peer, true);
                    refreshRequests();
                });
                row.addView(accept, new LinearLayout.LayoutParams(dp(92), dp(44)));
                requestList.addView(row);
            }
    }

    public void onClientRemoved(com.totgb.zazzproxy.model.Peer peer) {
        peers.remove(peer.id);
        refreshPeers();
    }

    public void onTransfer(String name, long current, long total, boolean upload) {
        if (transferPanel == null) return;
        ProgressBar bar = transferBars.get(name);
        TextView label = transferLabels.get(name);
        if (bar == null) {
            label = new TextView(requireContext());
            label.setTextColor(android.graphics.Color.WHITE);
            bar = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgressTintList(ColorStateList.valueOf(android.graphics.Color.rgb(
                    70 + new java.util.Random().nextInt(130),
                    70 + new java.util.Random().nextInt(130),
                    70 + new java.util.Random().nextInt(130))));
            transferLabels.put(name, label);
            transferBars.put(name, bar);
            transferPanel.addView(label);
            transferPanel.addView(bar, new LinearLayout.LayoutParams(-1, dp(10)));
        }
        int percent = total <= 0 ? 0 : (int) Math.max(0, Math.min(1000, (current * 1000L) / total));
        label.setText((upload ? "Uploading  " : "Downloading  ") + name + "  " + (percent / 10) + "%");
        bar.setProgress(percent);
    }

    public void finishTransfer(String name) {
        ProgressBar bar = transferBars.remove(name);
        TextView label = transferLabels.remove(name);
        if (label != null && transferPanel != null) transferPanel.removeView(label);
        if (bar != null && transferPanel != null) transferPanel.removeView(bar);
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
            LinearLayout card = new LinearLayout(getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(10), dp(10), dp(10));
            card.setBackgroundColor(android.graphics.Color.rgb(29, 38, 61));
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
            boolean awaiting = false;
            if (getActivity() instanceof MainActivity) {
                for (com.totgb.zazzproxy.model.Peer request : ((MainActivity) getActivity()).pendingConnectionRequests()) {
                    if (requestedPeers.contains(peer.id) || request.id.equals(peer.id)) {
                        awaiting = true;
                        break;
                    }
                }
            }
            name.setText("●  " + peer.name + "\n    " + peer.host + ":" + peer.address().getPort()
                    + "\n    Network key: MATCHED"
                    + "\n    " + (awaiting ? "Awaiting request response" : "Available · awaiting client request"));
            name.setTextSize(16);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            MacMotionButton accept = new MacMotionButton(requireContext());
            accept.setText("ACCEPT");
            accept.setTextColor(android.graphics.Color.WHITE);
            accept.setBackgroundColor(android.graphics.Color.rgb(22, 145, 105));
            accept.setEnabled(awaiting);
            accept.setAlpha(awaiting ? 1f : .45f);
            accept.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).respondToConnectionRequest(peer, true);
                    requestedPeers.remove(peer.id);
                    refreshPeers();
                }
            });
            row.addView(accept, new LinearLayout.LayoutParams(0, dp(44), 1));
            MacMotionButton decline = new MacMotionButton(requireContext());
            decline.setText("DECLINE");
            decline.setEnabled(awaiting);
            decline.setAlpha(awaiting ? 1f : .45f);
            decline.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).respondToConnectionRequest(peer, false);
                    requestedPeers.remove(peer.id);
                    refreshPeers();
                }
            });
            row.addView(decline, new LinearLayout.LayoutParams(0, dp(44), 1));
            MacMotionButton kick = new MacMotionButton(requireContext());
            kick.setText("KICK");
            kick.setTextColor(android.graphics.Color.WHITE);
            kick.setBackgroundColor(android.graphics.Color.rgb(49, 103, 213));
            kick.setOnClickListener(v -> managePeer(peer, false));
            row.addView(kick, new LinearLayout.LayoutParams(0, dp(44), 1));
            MacMotionButton ban = new MacMotionButton(requireContext());
            ban.setText("BAN");
            ban.setTextColor(android.graphics.Color.WHITE);
            ban.setBackgroundColor(android.graphics.Color.rgb(150, 61, 78));
            ban.setOnClickListener(v -> managePeer(peer, true));
            row.addView(ban, new LinearLayout.LayoutParams(0, dp(44), 1));
            card.addView(row);
            peerList.addView(card, new LinearLayout.LayoutParams(-1, -2));
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
        android.os.Handler rangeHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        int[] rangeAnchor = {-1};
        int[] rangeIndex = {-1};
        boolean[] rangeActive = {false};
        for (java.io.File file : hosted) {
            android.widget.CheckBox check = new android.widget.CheckBox(requireContext());
            check.setText(file.getName());
            check.setTextColor(android.graphics.Color.WHITE);
            checks.add(check);
            choices.addView(check);
            final int index = checks.size() - 1;
            check.setOnTouchListener((view, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    rangeHandler.postDelayed(() -> {
                        rangeActive[0] = true;
                        rangeAnchor[0] = index;
                        rangeIndex[0] = index;
                        check.setChecked(true);
                    }, android.view.ViewConfiguration.getLongPressTimeout());
                } else if (event.getAction() == android.view.MotionEvent.ACTION_MOVE
                        && rangeActive[0]) {
                    float absoluteY = check.getTop() + event.getY();
                    int current = rangeIndex[0];
                    for (int i = 0; i < checks.size(); i++) {
                        android.view.View candidate = choices.getChildAt(i);
                        if (absoluteY >= candidate.getTop()
                                && absoluteY <= candidate.getBottom()) {
                            current = i;
                            break;
                        }
                    }
                    if (current != rangeIndex[0]) {
                        if (current > rangeIndex[0]) {
                            for (int i = rangeIndex[0] + 1; i <= current; i++) {
                                checks.get(i).setChecked(true);
                            }
                        } else {
                            for (int i = current + 1; i <= rangeIndex[0]; i++) {
                                checks.get(i).setChecked(false);
                            }
                        }
                        rangeIndex[0] = current;
                    }
                    return true;
                } else if (event.getAction() == android.view.MotionEvent.ACTION_UP
                        || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                    rangeHandler.removeCallbacksAndMessages(null);
                    boolean consume = rangeActive[0];
                    rangeActive[0] = false;
                    if (consume) return true;
                }
                return false;
            });
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
