package com.flapdisplayplus.music.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一的歌曲信息结构，用于把任意平台的搜索结果解析为可写入 CD 的 SongInfo。
 */
public final class SongInfoData {
    public String songUrl;
    public String songName;
    public int songTime;
    public String transName = "";
    public boolean vip;
    public boolean readOnly;
    public List<String> artists = new ArrayList<>();

    public boolean isValid() {
        return songUrl != null && !songUrl.isBlank()
                && songName != null && !songName.isBlank()
                && songTime > 0;
    }
}
