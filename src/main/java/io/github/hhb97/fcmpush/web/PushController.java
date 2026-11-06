package io.github.hhb97.fcmpush.web;

import io.github.hhb97.fcmpush.device.DeviceStore;
import io.github.hhb97.fcmpush.device.DeviceStore.Device;
import io.github.hhb97.fcmpush.push.PushOutcome;
import io.github.hhb97.fcmpush.push.PushRequest;
import io.github.hhb97.fcmpush.push.PushSender;
import io.github.hhb97.fcmpush.push.PushService;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 接口：
 * GET    /api/status              服务账号是否已加载、项目 id
 * POST   /api/config              {credentialsPath} 加载 / 更换服务账号私钥
 * GET    /api/devices             设备列表
 * POST   /api/devices             {name, token} 添加（同 token 则改名）
 * DELETE /api/devices/{id}        删除
 * POST   /api/push                发送（见 PushRequest）
 * POST   /api/push/devices        {deviceIds, title, body, data, dataOnly} 按已保存的设备发送
 * GET    /api/history             最近发送记录
 */
@RestController
@RequestMapping("/api")
public class PushController {

    private final PushSender sender;
    private final PushService pushService;
    private final DeviceStore devices;
    private final Validator validator;

    public PushController(PushSender sender, PushService pushService, DeviceStore devices, Validator validator) {
        this.sender = sender;
        this.pushService = pushService;
        this.devices = devices;
        this.validator = validator;
    }

    public record Status(boolean ready, String projectId) {
    }

    public record ConfigRequest(@NotBlank(message = "请填写私钥文件路径") String credentialsPath) {
    }

    public record DeviceRequest(String name, @NotBlank(message = "token 不能为空") String token) {
    }

    public record DevicePushRequest(List<String> deviceIds, String title, String body, Map<String, String> data, boolean dataOnly) {
    }

    @GetMapping("/status")
    public Status status() {
        return new Status(sender.isReady(), sender.projectId());
    }

    @PostMapping("/config")
    public Status configure(@Valid @RequestBody ConfigRequest req) {
        // 允许用户直接粘贴带引号的路径（Windows「复制文件地址」会带引号）
        String path = req.credentialsPath().trim().replaceAll("^\"|\"$", "");
        try {
            sender.configure(Path.of(path));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return status();
    }

    @GetMapping("/devices")
    public List<Device> devices() {
        return devices.list();
    }

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    public Device addDevice(@Valid @RequestBody DeviceRequest req) {
        return devices.add(req.name(), req.token());
    }

    @DeleteMapping("/devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDevice(@PathVariable String id) {
        if (!devices.delete(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在");
    }

    @PostMapping("/push")
    public PushOutcome push(@Valid @RequestBody PushRequest req) {
        requireReady();
        return pushService.send(req);
    }

    @PostMapping("/push/devices")
    public PushOutcome pushToDevices(@RequestBody DevicePushRequest req) {
        List<String> ids = req.deviceIds() == null ? List.of() : req.deviceIds();
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少选择一台设备");
        List<String> tokens = ids.stream()
                .map(id -> devices.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备不存在：" + id)).token())
                .toList();
        PushRequest push = new PushRequest(tokens, null, req.title(), req.body(), req.data(), req.dataOnly());
        // 这里是手动构造的请求，@Valid 不会生效，手动校验一次
        validator.validate(push).stream().findFirst().ifPresent(v -> {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, v.getMessage());
        });
        return push(push);
    }

    @GetMapping("/history")
    public List<PushOutcome> history() {
        return pushService.history();
    }

    private void requireReady() {
        if (!sender.isReady()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "还没有配置服务账号私钥：在页面顶部填写私钥文件路径");
        }
    }
}
