package io.github.hhb97.fcmpush;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param credentials      服务账号私钥 JSON 的路径；为空时服务照常启动，在网页里再配置
 * @param androidChannelId 安卓通知渠道 id（要和 App 里创建的渠道一致）
 * @param dataDir          设备列表等本地数据的目录
 */
@ConfigurationProperties(prefix = "fcm")
public record FcmProperties(String credentials, String androidChannelId, String dataDir) {

    public FcmProperties {
        if (androidChannelId == null || androidChannelId.isBlank()) androidChannelId = "push";
        if (dataDir == null || dataDir.isBlank()) dataDir = "./data";
    }
}
