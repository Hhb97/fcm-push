package io.github.hhb97.fcmpush;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hhb97.fcmpush.push.PushOutcome;
import io.github.hhb97.fcmpush.push.PushRequest;
import io.github.hhb97.fcmpush.push.PushSender;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 接口层测试：用假的发送器代替 Firebase，不连谷歌。 */
@SpringBootTest
@AutoConfigureMockMvc
class PushApiTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("fcm.data-dir", () -> dataDir.toString());
        r.add("fcm.credentials", () -> "");
    }

    /** 记录收到的请求；token 以 "bad" 开头的模拟失效 */
    static class FakeSender implements PushSender {
        boolean ready;
        final List<PushRequest> sent = new ArrayList<>();

        @Override public boolean isReady() { return ready; }
        @Override public String projectId() { return ready ? "fake-project" : null; }
        @Override public void configure(Path credentials) {
            if (!credentials.toString().endsWith("good.json")) throw new IllegalArgumentException("文件不存在：" + credentials);
            ready = true;
        }
        @Override public PushOutcome send(PushRequest request) {
            sent.add(request);
            List<PushOutcome.Result> results = request.topic() != null
                    ? List.of(PushOutcome.Result.ok("主题 " + request.topic(), "m-topic"))
                    : request.tokens().stream().map(t -> t.startsWith("bad")
                            ? PushOutcome.Result.fail(PushOutcome.mask(t), "UNREGISTERED", "token 已失效")
                            : PushOutcome.Result.ok(PushOutcome.mask(t), "m-" + t.length())).toList();
            return new PushOutcome(Instant.now(), request.title(), "test", results);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean @Primary FakeSender fakeSender() { return new FakeSender(); }
    }

    @Autowired MockMvc mvc;
    @Autowired FakeSender sender;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() throws Exception {
        sender.ready = false;
        sender.sent.clear();
        for (JsonNode d : json.readTree(mvc.perform(get("/api/devices")).andReturn().getResponse().getContentAsString())) {
            mvc.perform(delete("/api/devices/" + d.get("id").asText()));
        }
    }

    private String body(Object o) throws Exception {
        return json.writeValueAsString(o);
    }

    @Test
    void statusReflectsWhetherCredentialsAreLoaded() throws Exception {
        mvc.perform(get("/api/status")).andExpect(jsonPath("$.ready").value(false));
        mvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON).content(body(java.util.Map.of("credentialsPath", "\"C:\\keys\\good.json\""))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.projectId").value("fake-project"));
    }

    @Test
    void badCredentialsPathIsA400WithAReadableMessage() throws Exception {
        mvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON).content(body(java.util.Map.of("credentialsPath", "C:\\nope.json"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("文件不存在")));
        mvc.perform(post("/api/config").contentType(MediaType.APPLICATION_JSON).content("{\"credentialsPath\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sendingBeforeConfigurationIsRefused() throws Exception {
        mvc.perform(post("/api/push").contentType(MediaType.APPLICATION_JSON).content("{\"tokens\":[\"t1\"],\"title\":\"hi\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("服务账号")));
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void requestValidation() throws Exception {
        sender.ready = true;
        String[] bad = {
                "{\"tokens\":[\"t1\"],\"title\":\"\"}",                          // 没标题
                "{\"title\":\"hi\"}",                                            // 没目标
                "{\"tokens\":[\"t1\"],\"topic\":\"news\",\"title\":\"hi\"}",     // 两个目标
                "{\"topic\":\"bad topic!\",\"title\":\"hi\"}",                    // 主题名非法
                "{not json",
        };
        for (String b : bad) {
            mvc.perform(post("/api/push").contentType(MediaType.APPLICATION_JSON).content(b)).andExpect(status().isBadRequest());
        }
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void tokensAreTrimmedAndDeduplicated() throws Exception {
        sender.ready = true;
        mvc.perform(post("/api/push").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokens\":[\" t1 \",\"t1\",\"\",\"t2\"],\"title\":\"hi\"}"))
                .andExpect(status().isOk());
        assertThat(sender.sent.get(0).tokens()).containsExactly("t1", "t2");
    }

    @Test
    void devicesCanBeAddedRenamedAndDeleted() throws Exception {
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"手机\",\"token\":\" tok-1 \"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.token").value("tok-1"));
        // 同一个 token 再加一次：只改名，不重复
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"新名字\",\"token\":\"tok-1\"}"));
        JsonNode list = json.readTree(mvc.perform(get("/api/devices")).andReturn().getResponse().getContentAsString());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("name").asText()).isEqualTo("新名字");

        mvc.perform(delete("/api/devices/" + list.get(0).get("id").asText())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/devices/nope")).andExpect(status().isNotFound());
        mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"token\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pushToSavedDevicesReportsPerDeviceResultsAndKeepsHistory() throws Exception {
        sender.ready = true;
        String a = json.readTree(mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"好的\",\"token\":\"good-token-0123456789abcdef\"}")).andReturn().getResponse().getContentAsString()).get("id").asText();
        String b = json.readTree(mvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"失效\",\"token\":\"bad-token-0123456789abcdef\"}")).andReturn().getResponse().getContentAsString()).get("id").asText();

        mvc.perform(post("/api/push/devices").contentType(MediaType.APPLICATION_JSON)
                        .content(body(java.util.Map.of("deviceIds", List.of(a, b), "title", "告警", "body", "温度过高", "data", java.util.Map.of("level", "high")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].success").value(true))
                .andExpect(jsonPath("$.results[1].errorCode").value("UNREGISTERED"))
                // 结果里不出现完整 token
                .andExpect(jsonPath("$.results[0].target").value("good-tok…abcdef"));
        assertThat(sender.sent.get(0).data()).containsEntry("level", "high");

        mvc.perform(get("/api/history")).andExpect(jsonPath("$[0].title").value("告警"));

        // 设备不存在 / 没选设备 / 没标题
        mvc.perform(post("/api/push/devices").contentType(MediaType.APPLICATION_JSON).content("{\"deviceIds\":[\"nope\"],\"title\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/push/devices").contentType(MediaType.APPLICATION_JSON).content("{\"deviceIds\":[],\"title\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/push/devices").contentType(MediaType.APPLICATION_JSON).content(body(java.util.Map.of("deviceIds", List.of(a), "title", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("标题不能为空"));
    }

    @Test
    void topicPush() throws Exception {
        sender.ready = true;
        mvc.perform(post("/api/push").contentType(MediaType.APPLICATION_JSON).content("{\"topic\":\"news\",\"title\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].success").value(true));
        assertThat(sender.sent.get(0).topic()).isEqualTo("news");
    }

    @Test
    void theWebPageIsServed() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk());
    }
}
