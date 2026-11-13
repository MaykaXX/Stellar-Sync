package nasa.spaceapps.aegis;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Звуковий маячок: три короткі сигнали. Тон генерується програмою, окремих файлів і
 * залежностей не потрібно. Якщо звукової карти немає, мовчки нічого не робить.
 */
final class MeshSound {
    private static volatile boolean muted = false;
    private static volatile long lastPlay = 0;

    private MeshSound() {
    }

    static void setMuted(boolean m) { muted = m; }

    static void beacon() {
        long now = System.currentTimeMillis();
        if (muted || now - lastPlay < 2500) return;
        lastPlay = now;
        Thread t = new Thread(MeshSound::play, "mesh-beacon");
        t.setDaemon(true);
        t.start();
    }

    private static void play() {
        try {
            float rate = 22050f;
            AudioFormat fmt = new AudioFormat(rate, 16, 1, true, false);
            int beepSamples = (int) (rate * 0.14), gapSamples = (int) (rate * 0.09);
            int total = 3 * (beepSamples + gapSamples);
            byte[] buf = new byte[total * 2];
            int pos = 0;
            for (int b = 0; b < 3; b++) {
                double freq = b == 2 ? 1320 : 990;
                for (int i = 0; i < beepSamples; i++) {
                    double env = Math.min(1.0, Math.min(i, beepSamples - i) / (rate * 0.008));
                    short v = (short) (Math.sin(2 * Math.PI * freq * i / rate) * 9000 * env);
                    buf[pos++] = (byte) (v & 0xFF);
                    buf[pos++] = (byte) ((v >> 8) & 0xFF);
                }
                pos += gapSamples * 2;
            }
            SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt);
            line.start();
            line.write(buf, 0, buf.length);
            line.drain();
            line.close();
        } catch (Throwable ignored) {
            // немає аудіопристрою: не страшно
        }
    }
}
