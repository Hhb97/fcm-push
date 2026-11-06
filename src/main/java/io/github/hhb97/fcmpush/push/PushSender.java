package io.github.hhb97.fcmpush.push;

import java.nio.file.Path;

/** 推送发送器。真实实现走 Firebase Admin SDK；测试里用假的实现，不连谷歌。 */
public interface PushSender {

    /** 是否已加载服务账号，可以发送 */
    boolean isReady();

    /** 当前服务账号所属的 Firebase 项目 id；未配置时为 null */
    String projectId();

    /** 加载（或更换）服务账号私钥 */
    void configure(Path credentials);

    /** 发送；未配置时抛 IllegalStateException */
    PushOutcome send(PushRequest request);
}
