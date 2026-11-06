package io.github.hhb97.fcmpush.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.api.client.json.gson.GsonFactory;
import io.github.hhb97.fcmpush.FcmProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 真实发送器的离线部分：消息组装、服务账号加载、错误说明。不连谷歌。 */
class FcmPushSenderTest {

    @TempDir
    Path tmp;

    private static String toJson(com.google.firebase.messaging.Message m) throws Exception {
        return GsonFactory.getDefaultInstance().toString(m);
    }

    @Test
    void normalMessageCarriesNotificationDataHighPriorityAndChannel() throws Exception {
        PushRequest req = new PushRequest(List.of("tok"), null, "标题", "正文", Map.of("k", "v"), false);
        String json = toJson(FcmPushSender.buildMessage(req, "tok", "push"));
        assertThat(json)
                .contains("\"token\":\"tok\"")
                .contains("\"notification\":{\"body\":\"正文\",\"title\":\"标题\"}")
                .contains("\"k\":\"v\"")
                .contains("\"title\":\"标题\"")          // 也放进 data，App 前台读取
                .contains("\"priority\":\"high\"")
                .contains("\"channel_id\":\"push\"");
    }

    @Test
    void dataOnlyMessageHasNoNotificationBlock() throws Exception {
        PushRequest req = new PushRequest(List.of("tok"), null, "标题", "正文", Map.of(), true);
        String json = toJson(FcmPushSender.buildMessage(req, "tok", "push"));
        assertThat(json).doesNotContain("\"notification\"").contains("\"body\":\"正文\"").contains("\"priority\":\"high\"");
    }

    @Test
    void topicMessageTargetsTheTopic() throws Exception {
        PushRequest req = new PushRequest(null, "news", "t", "b", null, false);
        assertThat(toJson(FcmPushSender.buildMessage(req, null, "push"))).contains("\"topic\":\"news\"").doesNotContain("\"token\"");
    }

    @Test
    void loadsAServiceAccountKeyAndRejectsBadFiles() throws Exception {
        FcmPushSender sender = new FcmPushSender(new FcmProperties("", "push", tmp.toString()));
        assertThat(sender.isReady()).isFalse();

        // 本地生成一个一次性的假私钥：只验证加载流程，不会用它连接谷歌
        var kp = KeyPairGenerator.getInstance("RSA");
        kp.initialize(2048);
        String pem = ("-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n").replace("\n", "\\n"); // 写进 JSON 字符串时换行要转义
        Path key = tmp.resolve("sa.json");
        Files.writeString(key, """
                {"type":"service_account","project_id":"fake-project","private_key_id":"abc",
                 "private_key":"%s","client_email":"fake@fake-project.iam.gserviceaccount.com","client_id":"1",
                 "token_uri":"https://oauth2.googleapis.com/token"}
                """.formatted(pem));
        sender.configure(key);
        assertThat(sender.isReady()).isTrue();
        assertThat(sender.projectId()).isEqualTo("fake-project");
        // 更换服务账号也可以
        sender.configure(key);
        assertThat(sender.isReady()).isTrue();

        assertThatThrownBy(() -> sender.configure(tmp.resolve("missing.json"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不存在");
        Path junk = tmp.resolve("junk.json");
        Files.writeString(junk, "{not json");
        assertThatThrownBy(() -> sender.configure(junk)).isInstanceOf(IllegalArgumentException.class);
        Path wrongType = tmp.resolve("user.json");
        Files.writeString(wrongType, "{\"type\":\"authorized_user\",\"client_id\":\"a\",\"client_secret\":\"b\",\"refresh_token\":\"c\"}");
        assertThatThrownBy(() -> sender.configure(wrongType)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("服务账号");
    }

    @Test
    void sendingWithoutCredentialsFailsClearly() {
        FcmPushSender sender = new FcmPushSender(new FcmProperties("", "push", tmp.toString()));
        assertThatThrownBy(() -> sender.send(new PushRequest(List.of("t"), null, "x", "", null, false)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void commonErrorCodesAreExplained() {
        assertThat(FcmPushSender.explain("UNREGISTERED", "raw")).contains("失效");
        assertThat(FcmPushSender.explain("SENDER_ID_MISMATCH", "raw")).contains("不是同一个项目");
        assertThat(FcmPushSender.explain("SOMETHING_ELSE", "raw")).isEqualTo("raw");
    }

    @Test
    void tokensAreMaskedInRecords() {
        assertThat(PushOutcome.mask("abcdefghijklmnopqrstuvwxyz")).isEqualTo("abcdefgh…uvwxyz");
        assertThat(PushOutcome.mask("short")).isEqualTo("short");
    }
}
