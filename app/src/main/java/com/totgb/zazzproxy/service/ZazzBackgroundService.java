package com.totgb.zazzproxy.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.util.List;
import com.totgb.zazzproxy.network.ZazzUdpNode;
import com.totgb.zazzproxy.model.FileInfo;
import com.totgb.zazzproxy.model.Peer;
import com.totgb.zazzproxy.ui.MainActivity;
import com.totgb.zazzproxy.R;

/** Opt-in foreground hosting; Android displays a permanent notification while the socket is alive. */
public final class ZazzBackgroundService extends Service {
    private static final String CHANNEL = "zazzproxy_sharing";
    private ZazzUdpNode node;
    @Override public void onCreate() { super.onCreate(); createChannel(); startForeground(71, notification("Starting secure server…")); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!SessionCoordinator.acquire(SessionCoordinator.Role.SERVER)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String key = getSharedPreferences("ZazzPrefs", MODE_PRIVATE).getString("network_key", "");
        if (key.trim().length() < 8) { stopSelf(); return START_NOT_STICKY; }
        try {
            String name = getSharedPreferences("ZazzPrefs", MODE_PRIVATE).getString("server_name", android.os.Build.MODEL);
            node = new ZazzUdpNode(this, name, true, key, new ZazzUdpNode.Callback() {
                @Override public void onPeer(Peer peer) { update("Connected: " + peer.name); }
                @Override public void onManifest(Peer peer, List<FileInfo> files) { }
                @Override public void onTransfer(String name, long current, long total, boolean upload) { update((upload ? "Receiving " : "Sending ") + name); }
                @Override public void onComplete(File file) { update("Complete: " + file.getName()); }
                @Override public void onFailure(String message) { update("Problem: " + message); }
            });
            node.start(); update("Secure server active"); return START_STICKY;
        } catch (Exception e) { SessionCoordinator.release(SessionCoordinator.Role.SERVER); update("Unable to start: " + e.getMessage()); stopSelf(); return START_NOT_STICKY; }
    }
    @Override public void onDestroy() { if (node != null) node.close(); SessionCoordinator.release(SessionCoordinator.Role.SERVER); super.onDestroy(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "ZazzProxy sharing", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while secure local sharing is active");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        android.app.PendingIntent pending = android.app.PendingIntent.getActivity(this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_zazzproxy).setContentTitle("ZazzProxy background server").setContentText(text).setContentIntent(pending).setOngoing(true).build();
    }
    private void update(String text) { getSystemService(NotificationManager.class).notify(71, notification(text)); }
}
