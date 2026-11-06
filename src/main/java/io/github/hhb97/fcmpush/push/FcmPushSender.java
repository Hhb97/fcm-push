package io.github.hhb97.fcmpush.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import io.github.hhb97.fcmpush.FcmProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 基于 Firebase Admin SDK 的发送器。
 * - 服务账号可在启动时从配置加载，也可以运行中更换（每次更换创建一个新的 FirebaseApp，旧的删除）
 * - 单台设备用 send，多台用 sendEach（逐条结果），主题用 send(topic)
 * - 日志里不输出私钥、访问令牌和完整的设备 token
 */
@Component
public class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);
    private static final AtomicInteger APP_SEQ = new AtomicInteger();

    private final FcmProperties props;
    private volatile FirebaseApp app;
    private volatile String projectId;

    public FcmPushSender(FcmProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        String path = props.credentials();
        if (path == null || path.isBlank()) {
            log.info("未配置服务账号（fcm.credentials / 环境变量 FCM_CREDENTIALS），可以在网页里填写");
            return;
        }
        try {
            configure(Path.of(path));
        } catch (RuntimeException e) {
            // 配置错了也让服务起来，网页上会显示原因
            log.warn("加载服务账号失败：{}", e.getMessage());
        }
    }

    @Override
    public boolean isReady() {
        return app != null;
    }

    @Override
    public String projectId() {
        return projectId;
    }

    @Override
    public synchronized void configure(Path credentials) {
        if (!Files.isRegularFile(credentials)) throw new IllegalArgumentException("文件不存在：" + credentials);
        GoogleCredentials creds;
        try (InputStream in = Files.newInputStream(credentials)) {
            creds = GoogleCredentials.fromStream(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("不是有效的服务账号私钥 JSON：" + e.getMessage(), e);
        }
        if (!(creds instanceof ServiceAccountCredentials sa)) {
            throw new IllegalArgumentException("需要「服务账号」类型的私钥（项目设置 → 服务账号 → 生成新的私钥）");
        }
        FirebaseOptions options = FirebaseOptions.builder().setCredentials(creds).setProjectId(sa.getProjectId()).build();
        FirebaseApp next = FirebaseApp.initializeApp(options, "fcm-push-" + APP_SEQ.incrementAndGet());
        FirebaseApp previous = app;
        app = next;
        projectId = sa.getProjectId();
        if (previous != null) previous.delete();
        log.info("已加载服务账号，Firebase 项目：{}", projectId);
    }

    @Override
    public PushOutcome send(PushRequest request) {
        FirebaseApp current = app;
        if (current == null) throw new IllegalStateException("还没有配置服务账号私钥");
        FirebaseMessaging messaging = FirebaseMessaging.getInstance(current);
        List<PushOutcome.Result> results = new ArrayList<>();

        if (request.topic() != null) {
            results.add(sendOne(messaging, buildMessage(request, null, props.androidChannelId()), "主题 " + request.topic()));
            return new PushOutcome(Instant.now(), request.title(), "主题 " + request.topic(), results);
        }
        List<String> tokens = request.tokens();
        if (tokens.size() == 1) {
            String t = tokens.get(0);
            results.add(sendOne(messaging, buildMessage(request, t, props.androidChannelId()), PushOutcome.mask(t)));
        } else {
            List<Message> messages = tokens.stream().map(t -> buildMessage(request, t, props.androidChannelId())).toList();
            try {
                BatchResponse batch = messaging.sendEach(messages);
                for (int i = 0; i < tokens.size(); i++) {
                    SendResponse r = batch.getResponses().get(i);
                    String target = PushOutcome.mask(tokens.get(i));
                    results.add(r.isSuccessful()
                            ? PushOutcome.Result.ok(target, r.getMessageId())
                            : fail(target, r.getException()));
                }
            } catch (FirebaseMessagingException e) {
                // 整批失败（鉴权、网络等）：每台设备记同一个原因
                tokens.forEach(t -> results.add(fail(PushOutcome.mask(t), e)));
            }
        }
        String target = tokens.size() + " 台设备";
        PushOutcome outcome = new PushOutcome(Instant.now(), request.title(), target, results);
        log.info("推送「{}」→ {}：成功 {}，失败 {}", request.title(), target, outcome.successCount(), outcome.failureCount());
        return outcome;
    }

    private static PushOutcome.Result sendOne(FirebaseMessaging messaging, Message message, String target) {
        try {
            return PushOutcome.Result.ok(target, messaging.send(message));
        } catch (FirebaseMessagingException e) {
            return fail(target, e);
        }
    }

    private static PushOutcome.Result fail(String target, FirebaseMessagingException e) {
        String code = e.getMessagingErrorCode() != null ? e.getMessagingErrorCode().name()
                : e.getErrorCode() != null ? e.getErrorCode().name() : "UNKNOWN";
        return PushOutcome.Result.fail(target, code, explain(code, e.getMessage()));
    }

    /** 常见错误码给出中文说明 */
    static String explain(String code, String raw) {
        return switch (code) {
            case "UNREGISTERED" -> "token 已失效（App 被卸载、清除数据或 token 已更新），请在 App 里重新获取";
            case "INVALID_ARGUMENT" -> "参数无效，多半是 token 不完整或格式不对：" + raw;
            case "SENDER_ID_MISMATCH" -> "token 不属于这个 Firebase 项目（服务账号和 App 的 google-services.json 不是同一个项目）";
            case "QUOTA_EXCEEDED" -> "发送太频繁，被 FCM 限流，稍后再试";
            case "UNAVAILABLE", "INTERNAL" -> "FCM 服务暂时不可用，稍后重试";
            case "PERMISSION_DENIED", "UNAUTHENTICATED" -> "服务账号没有权限或已被删除：" + raw;
            default -> raw;
        };
    }

    /**
     * 组装一条 FCM 消息。
     * - 普通消息：notification + data，安卓走高优先级、指定通知渠道（App 在后台时由系统显示）
     * - dataOnly：只带 data（标题正文也放进 data），App 在后台也会回调，由 App 自己显示
     */
    static Message buildMessage(PushRequest req, String token, String channelId) {
        Message.Builder b = Message.builder();
        if (token != null) b.setToken(token); else b.setTopic(req.topic());
        b.putAllData(req.data());
        // 标题和正文同时放进 data：HardwareLens 在前台 / 纯 data 消息时从这里读取
        b.putData("title", req.title());
        b.putData("body", req.body());
        AndroidConfig.Builder android = AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH);
        if (!req.dataOnly()) {
            b.setNotification(Notification.builder().setTitle(req.title()).setBody(req.body()).build());
            android.setNotification(AndroidNotification.builder().setChannelId(channelId).build());
        }
        b.setAndroidConfig(android.build());
        return b.build();
    }
}
