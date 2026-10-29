package com.github.tvbox.osc.bean;

import com.thoughtworks.xstream.annotations.XStreamAlias;

import java.io.Serializable;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
@XStreamAlias("rss")
public class AbsXml implements Serializable {
    public String sourceKey;
    public String searchToken;
    public int detailRequestGeneration;
    // 由宿主搜索边界写入；与上游msg分离，禁止展示原始异常或响应内容。
    public String searchErrorCode;
    public String searchErrorMessage;

    @XStreamAlias("list")
    public Movie movie;

    @XStreamAlias("msg")
    public String msg;
}
