package io.github.hhb97.fcmpush.push;

import java.time.Instant;
import java.util.List;

/**
 * 一次推送的结果。
 *
 * @param at       发送时间
 * @param title    通知标题
 * @param target   目标描述（主题名或"N 台设备"）
 * @param results  每个目标的结果
 */
public record PushOutcome(Instant at, String title, String target, List<Result> results) {

    public long successCount() {
        return results.stream().filter(Result::success).count();
    }

    public long failureCount() {
        return results.size() - successCount();
    }

    /**
     * @param target    目标（token 只保留首尾几位，避免完整 token 出现在记录里）
     * @param success   是否被 FCM 接受（不代表设备已显示）
     * @param messageId FCM 返回的消息 id
     * @param errorCode FCM 错误码，如 UNREGISTERED（token 已失效）、INVALID_ARGUMENT
     * @param error     错误说明
     */
    public record Result(String target, boolean success, String messageId, String errorCode, String error) {

        public static Result ok(String target, String messageId) {
            return new Result(target, true, messageId, null, null);
        }

        public static Result fail(String target, String errorCode, String error) {
            return new Result(target, false, null, errorCode, error);
        }
    }

    /** 记录里只显示 token 的首尾，完整 token 仍保存在设备列表中 */
    public static String mask(String token) {
        if (token == null) return "";
        return token.length() <= 16 ? token : token.substring(0, 8) + "…" + token.substring(token.length() - 6);
    }
}
