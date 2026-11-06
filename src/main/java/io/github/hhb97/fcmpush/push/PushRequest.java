package io.github.hhb97.fcmpush.push;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * 一次推送请求。目标二选一：tokens（一台或多台设备）或 topic（订阅了该主题的所有设备）。
 *
 * @param tokens 设备 token 列表
 * @param topic  主题名（不带 /topics/ 前缀）
 * @param title  通知标题
 * @param body   通知正文
 * @param data   附带的键值数据（App 前台时可以读取）
 * @param dataOnly true 时只发 data、不带 notification：App 在后台时也会回调 onMessageReceived，由 App 自己决定怎么显示
 */
public record PushRequest(
        List<String> tokens,
        String topic,
        @NotBlank(message = "标题不能为空") @Size(max = 200, message = "标题最多 200 字") String title,
        @Size(max = 2000, message = "正文最多 2000 字") String body,
        Map<String, String> data,
        boolean dataOnly) {

    /** 一次最多发给 500 台：FCM 批量发送接口的上限 */
    public static final int MAX_TOKENS = 500;

    public PushRequest {
        tokens = tokens == null ? List.of() : tokens.stream().map(String::trim).filter(t -> !t.isEmpty()).distinct().toList();
        topic = topic == null || topic.isBlank() ? null : topic.trim();
        body = body == null ? "" : body;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    @AssertTrue(message = "请选择设备（token）或填写主题，两者只能选一个")
    public boolean isTargetValid() {
        return tokens.isEmpty() != (topic == null);
    }

    @AssertTrue(message = "一次最多发给 " + MAX_TOKENS + " 台设备")
    public boolean isTokenCountValid() {
        return tokens.size() <= MAX_TOKENS;
    }

    @AssertTrue(message = "主题名只能包含字母、数字和 -_.~%")
    public boolean isTopicValid() {
        return topic == null || topic.matches("[a-zA-Z0-9\\-_.~%]{1,900}");
    }
}
