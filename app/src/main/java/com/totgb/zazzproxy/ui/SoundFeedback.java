package com.totgb.zazzproxy.ui;

import android.media.AudioManager;
import android.media.ToneGenerator;

/** Offline system tones for connection state changes. */
public final class SoundFeedback {
    private SoundFeedback() {}

    public static void play(boolean started) {
        ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
        tone.startTone(started ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_NACK, 130);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(tone::release, 220);
    }
}
