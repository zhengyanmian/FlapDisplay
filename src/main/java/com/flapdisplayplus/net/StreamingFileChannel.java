/*
 * StreamingFileChannel.java
 *
 * 给「正在下载的文件」套一层 jcodec 的 SeekableByteChannel：
 * 读取超出已下载范围时阻塞等待数据到位（而不是返回 EOF），从而支持边下边播。
 *
 * 注意：这里实现的是【org.jcodec.common.io.SeekableByteChannel】（jcodec 自有接口，
 * 方法名是 setPosition/truncate），不是 java.nio.channels.SeekableByteChannel。
 * NIOUtils.readableChannel() 返回的也是 jcodec 这套接口，两者必须一致才能替换。
 *
 * size() 返回【服务器声明的总长度】：解封装器据此定位文件尾部的索引(moov)，
 * 读尾部数据时会自然等待下载进度。代价是非 faststart 的 MP4 必须等下载完成才能
 * 解出第一帧——这是 MP4 容器特性，不是实现问题。
 */
package com.flapdisplayplus.net;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.StandardOpenOption;

public final class StreamingFileChannel implements org.jcodec.common.io.SeekableByteChannel {

    /** 单次读取最多等待多久（毫秒）；超时后交给上层重试，此时下载仍在后台继续 */
    private static final long AWAIT_TIMEOUT_MS = 30000L;

    private final FileChannel fc;
    private final StreamDownloader dl;
    private final long declaredSize;
    private boolean open = true;

    private StreamingFileChannel(FileChannel fc, StreamDownloader dl, long declaredSize) {
        this.fc = fc;
        this.dl = dl;
        this.declaredSize = declaredSize;
    }

    /**
     * 打开通道。文件已完整（本地缓存命中）时返回普通 FileChannel 包装，零额外开销。
     *
     * @param dl           正在进行的下载；已完成或为 null 时走普通通道
     * @param declaredSize 服务器声明的总长度；未知传 -1
     */
    public static org.jcodec.common.io.SeekableByteChannel open(File file, StreamDownloader dl,
                                                                long declaredSize) throws IOException {
        FileChannel fc = FileChannel.open(file.toPath(), StandardOpenOption.READ);
        if (dl == null || dl.isFinished()) {
            return new org.jcodec.common.io.FileChannelWrapper(fc);
        }
        return new StreamingFileChannel(fc, dl, declaredSize);
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        long want = fc.position() + dst.remaining();
        long have = fc.size();
        if (want > have) {
            // 请求范围尚未下载到：等待（下载失败/超时会抛 IOException，上层重试）
            awaitBytes(have, want - have);
        }
        int n = fc.read(dst);
        if (n < 0 && !dl.isFinished()) {
            // 竞态兜底：等到了声明长度但字节还没落盘，再等一轮
            awaitBytes(fc.size(), 1);
            n = fc.read(dst);
        }
        return n;
    }

    /**
     * 通道的 read 只能抛 IOException，而等待数据会抛 InterruptedException，
     * 这里转成 InterruptedIOException（IOException 子类）并保留线程中断标志。
     */
    private void awaitBytes(long offset, long len) throws IOException {
        try {
            dl.awaitBytes(offset, len, AWAIT_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("等待下载数据被中断");
        }
    }

    @Override
    public int write(ByteBuffer src) {
        throw new NonWritableChannelException();
    }

    @Override
    public long position() throws IOException {
        return fc.position();
    }

    @Override
    public org.jcodec.common.io.SeekableByteChannel setPosition(long newPosition) throws IOException {
        fc.position(newPosition);
        return this;
    }

    /**
     * 返回声明的总长度（而非已下载长度）：解封装器据此定位文件尾部索引。
     * 总长度未知时退回实际长度。
     */
    @Override
    public long size() throws IOException {
        return declaredSize > 0 ? declaredSize : fc.size();
    }

    @Override
    public org.jcodec.common.io.SeekableByteChannel truncate(long size) {
        throw new NonWritableChannelException();
    }

    @Override
    public boolean isOpen() {
        return open && fc.isOpen();
    }

    @Override
    public void close() throws IOException {
        open = false;
        fc.close();
    }

    /** 是否已完成（供播放逻辑判断） */
    public boolean complete() {
        return dl == null || dl.isFinished();
    }
}
