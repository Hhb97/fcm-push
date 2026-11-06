package io.github.hhb97.fcmpush;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** 本地 FCM 推送服务入口：启动后打开 http://127.0.0.1:8090 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FcmPushApplication {

    public static void main(String[] args) {
        SpringApplication.run(FcmPushApplication.class, args);
    }
}
