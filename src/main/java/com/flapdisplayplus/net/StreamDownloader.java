/*
 * StreamDownloader.java
 *
 * 流式下载器：后台线程把直链写入 .part 文件，同时允许播放器读取【已下载部分】。
 *
 * 边下边播的关键约定：
 * - 读取超出已下载范围时，阻塞等待数据到位（有超时），而不是直接 EOF。
 * - 因此 faststart 的 MP4（索引 moov 在文件头部）可以做到秒开边下边播；
 *   非 faststart（moov 在尾部）读索引时自然会等到下载完成，等效"下完再播"，
 *   但不会误判为文件损坏。这是 MP4 容器自身特性决定的，不是实现缺陷。
 */
package com.flapdisplayplus.net;

import com.flapdisplayplus.FlapDisplayPlus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class StreamDownloader {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) FlapDisplayPlus/1.0";
    private static final int BUF = 64 * 1024;

    private final String url;
    private final File part;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition progress = lock.newCondition();

    private volatile long downloaded = 0;
    private volatile long total = -1;
    private volatile boolean finished;
    private volatile boolean failed;
    private volatile String error;
    private volatile boolean cancelled;
    private Thread thread;

    private StreamDownloader(String url, File part) {
        this.url = url;
        this.part = part;
    }

    /** 启动下载（后台线程） */
    public static StreamDownloader start(String url, File part) {
        StreamDownloader d = new StreamDownloader(url, part);
        d.thread = new Thread(d::run, "fdp-netdl-" + part.getName());
        d.thread.setDaemon(true);
        d.thread.start();
        return d;
    }

    private void run() {
        HttpURLConnection conn = null;
        FileOutputStream fos = null;
        try {
            // 不做断点续传：缓存文件是固定路径，残留的半截文件长度不可信
            // （可能来自上次中断的下载），一律从头覆盖写，配合 .complete 标记保证一致性。
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code >= 400) {
                throw new java.io.IOException("HTTP " + code);
            }
            long len = conn.getContentLengthLong();
            total = len > 0 ? len : -1;
            downloaded = 0;

            File parent = part.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            fos = new FileOutputStream(part, false);
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[BUF];
                int n;
                while (!cancelled && (n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    fos.flush();
                    lock.lock();
                    try {
                        downloaded += n;
                        progress.signalAll();
                    } finally {
                        lock.unlock();
                    }
                }
            }
            if (cancelled) {
                return;
            }
            finished = true;
            lock.lock();
            try {
                progress.signalAll();
            } finally {
                lock.unlock();
            }
            FlapDisplayPlus.LOGGER.info("[NetDL] 下载完成: {} ({} 字节)", part.getName(), downloaded);
        } catch (Throwable t) {
            failed = true;
            error = t.toString();
            FlapDisplayPlus.LOGGER.warn("[NetDL] 下载失败: {} {}", url, t.toString());
            lock.lock();
            try {
                progress.signalAll();
            } finally {
                lock.unlock();
            }
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 等待直到 [offset, offset+len) 的数据已就位。
     *
     * @throws InterruptedException 被中断
     * @throws java.io.IOException   下载失败
     */
    public void awaitBytes(long offset, long len, long timeoutMs)
            throws InterruptedException, java.io.IOException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        lock.lock();
        try {
            while (true) {
                if (failed) {
                    throw new java.io.IOException("下载失败: " + error);
                }
                if (downloaded >= offset + len || finished) {
                    return;
                }
                long remainMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainMs <= 0) {
                    throw new java.io.IOException("等待数据超时（已下载 " + downloaded + "/"
                            + (total > 0 ? total : "?") + " 字节）");
                }
                if (!progress.await(Math.min(remainMs, 500), TimeUnit.MILLISECONDS)) {
                    // 继续循环，重新检查条件与 deadline
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public long downloaded() {
        return downloaded;
    }

    /** 总字节数；服务器未给出时为 -1 */
    public long total() {
        return total;
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isFailed() {
        return failed;
    }

    public String error() {
        return error;
    }

    /** 进度 0.0~1.0；总长未知时返回 -1 */
    public float progress() {
        long t = total;
        if (t <= 0) {
            return -1f;
        }
        return Math.min(1f, downloaded / (float) t);
    }

    public void cancel() {
        cancelled = true;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
