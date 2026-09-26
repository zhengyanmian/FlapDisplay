/*
 * NetMediaManager.java
 *
 * 网络媒体获取协调器：把「用户粘贴的链接」变成「本地可播放的文件」。
 *
 * 流程：查缓存 →（未命中）解析器解析出直链 → 流式下载 → 完成标记 → 就绪。
 * 下载进行中就把本地文件交给播放器，配合 StreamingFileChannel 实现边下边播。
 *
 * 缓存 key 用【用户输入的原始链接】而非解析出的直链：站点直链常带时效签名，
 * 同一视频每次解析出的 URL 都不同，用直链做 key 会导致每次都重新下载。
 */
package com.flapdisplayplus.net;

import com.flapdisplayplus.FlapDisplayPlus;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NetMediaManager {

    private NetMediaManager() {
    }

    public enum State {
        /** 排队/准备中 */
        PENDING,
        /** 正在解析直链（可能调用外部 yt-dlp） */
        RESOLVING,
        /** 正在下载 */
        DOWNLOADING,
        /** 已就绪：file 可直接使用（可能仍在后台继续下载完剩余部分） */
        READY,
        /** 失败：见 message */
        FAILED
    }

    public static final class Task {
        public final String input;
        public volatile State state = State.PENDING;
        public volatile String message = "";
        /** 下载进度 0~1；-1 表示总长度未知 */
        public volatile float progress = -1f;
        public volatile ResolvedMedia resolved;
        /** 本地缓存文件（下载开始后即存在，可用于边下边播） */
        public volatile File file;
        public volatile StreamDownloader downloader;

        Task(String input) {
            this.input = input;
        }

        /** 界面用的状态文案 */
        public String statusText() {
            switch (state) {
                case PENDING:
                    return "准备中…";
                case RESOLVING:
                    return "解析链接中…";
                case DOWNLOADING: {
                    float p = progress;
                    if (p >= 0) {
                        return String.format("下载中 %.0f%%", p * 100f);
                    }
                    long mb = downloader != null ? downloader.downloaded() / (1024 * 1024) : 0;
                    return "下载中 " + mb + " MB";
                }
                case READY:
                    return "就绪";
                case FAILED:
                    return message == null || message.isEmpty() ? "失败" : message;
                default:
                    return "";
            }
        }
    }

    private static final Map<String, Task> TASKS = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "fdp-netmedia");
        t.setDaemon(true);
        return t;
    });

    public static Task get(String input) {
        return TASKS.get(input);
    }

    /** 已就绪的本地文件；未完成返回 null */
    public static File readyFile(String input) {
        Task t = TASKS.get(input);
        if (t != null && t.state == State.READY && t.file != null && t.file.isFile()) {
            return t.file;
        }
        return null;
    }

    /**
     * 开始获取（幂等）：已有任务则直接返回，不会重复下载。
     * 全程后台执行，可安全从渲染线程调用。
     */
    public static Task acquire(String input) {
        Task exist = TASKS.get(input);
        if (exist != null && (exist.state == State.DOWNLOADING || exist.state == State.RESOLVING
                || exist.state == State.READY || exist.state == State.PENDING)) {
            return exist;
        }
        Task t = new Task(input);
        TASKS.put(input, t);
        POOL.submit(() -> run(t));
        return t;
    }

    private static void run(Task t) {
        try {
            // 1) 解析
            t.state = State.RESOLVING;
            ResolvedMedia rm = MediaResolverManager.resolve(t.input);
            t.resolved = rm;

            // 扩展名缺失时按类型补默认后缀：下游靠扩展名判断走视频还是图片管线
            String ext = rm.ext;
            if (ext == null || ext.isEmpty()) {
                ext = rm.video ? ".mp4" : ".img";
            }

            // 2) 缓存命中？（用原始输入做 key，见类注释）
            File cached = NetCache.get(t.input, ext);
            if (cached != null) {
                t.file = cached;
                t.state = State.READY;
                t.progress = 1f;
                NetCache.touch(cached);
                return;
            }

            // 3) 下载（文件即刻存在，可供边下边播）
            File target = NetCache.file(t.input, ext);
            t.file = target;
            t.state = State.DOWNLOADING;
            StreamDownloader dl = StreamDownloader.start(rm.directUrl, target);
            t.downloader = dl;

            // 4) 等待完成（失败/取消则退出）
            while (!dl.isFinished() && !dl.isFailed()) {
                t.progress = dl.progress();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    dl.cancel();
                    t.state = State.FAILED;
                    t.message = "已取消";
                    return;
                }
            }
            if (dl.isFailed()) {
                t.state = State.FAILED;
                t.message = "下载失败：" + dl.error();
                return;
            }
            NetCache.markComplete(target);
            t.progress = 1f;
            t.state = State.READY;
            FlapDisplayPlus.LOGGER.info("[NetMedia] 就绪: {} -> {}", rm.displayName(), target);
        } catch (Throwable e) {
            t.state = State.FAILED;
            t.message = e.getMessage() == null ? e.toString() : e.getMessage();
            FlapDisplayPlus.LOGGER.warn("[NetMedia] 获取失败: {} {}", t.input, e.toString());
        }
    }

    /** 取消并移除任务（不删除已下载的文件，下次可继续复用） */
    public static void cancel(String input) {
        Task t = TASKS.remove(input);
        if (t != null && t.downloader != null) {
            t.downloader.cancel();
        }
    }
}
