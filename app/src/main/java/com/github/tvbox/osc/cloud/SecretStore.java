package com.github.tvbox.osc.cloud;

/** 会话与账本的私有存储边界；测试可以注入内存实现。 */
public interface SecretStore {
    String read(String key) throws Exception;
    void write(String key, String value) throws Exception;
}
