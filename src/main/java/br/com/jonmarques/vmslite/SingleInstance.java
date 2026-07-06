package br.com.jonmarques.vmslite;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

public class SingleInstance {

    private static RandomAccessFile raf;
    private static FileChannel channel;
    private static FileLock lock;

    public static boolean lock() {
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
            return false;
        }
    }

    public static void unlock() {
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
    }
}