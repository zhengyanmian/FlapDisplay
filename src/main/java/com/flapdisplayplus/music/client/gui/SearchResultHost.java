package com.flapdisplayplus.music.client.gui;

/**
 * 搜索结果宿主接口。由刻录机界面的 Mixin 实现，搜索界面通过此接口把
 * 选中的歌曲标识回填到刻录机输入框（筐），再由用户点击「制作唱片」。
 */
public interface SearchResultHost {

    /**
     * 把字符串写入刻录机的歌曲输入框。
     * @param value 网易云分享链接（music.163.com/song?id=XXX）或 QQ 标识（qqmusic:{mid}）
     */
    void netmusicdisplay$applySearchResult(String value);
}
