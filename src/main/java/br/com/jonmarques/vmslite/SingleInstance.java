package br.com.jonmarques.vmslite;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

public class SingleInstance {

    private static RandomAccessFile raf;
    private static FileChannel channel;
    private static FileLock lock;

    public static synchronized boolean lock() {
        if (lock != null && lock.isValid()) return true;
        try {
            File file = new File(System.getProperty("java.io.tmpdir"), "vmslite.lock");

            raf = new RandomAccessFile(file, "rw");
            channel = raf.getChannel();
            lock = channel.tryLock();

            if (lock == null) {
                channel.close();
                raf.close();
                return false;
            }

            return true;

        } catch (Exception e) {
            unlock();
            return false;
        }
    }

    public static synchronized void unlock() {
        try {
            if (lock != null) {
                lock.release();
            }
        } catch (Exception ignored) {
        }

        try {
            if (channel != null) {
                channel.close();
            }
        } catch (Exception ignored) {
        }

        try {
            if (raf != null) {
                raf.close();
            }
        } catch (Exception ignored) {
        }
        lock = null;
        channel = null;
        raf = null;
    }
}
