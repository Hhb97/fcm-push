package io.github.hhb97.fcmpush.device;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.hhb97.fcmpush.FcmProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 设备列表：保存在 data/devices.json（原子写入：先写临时文件再替换）。
 * 同一个 token 只保存一份；再次添加同一个 token 时更新名称。
 */
@Component
public class DeviceStore {

    public record Device(String id, String name, String token, Instant addedAt) {
    }

    private final Path file;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final List<Device> devices = new ArrayList<>();

    public DeviceStore(FcmProperties props) {
        this.file = Path.of(props.dataDir()).resolve("devices.json");
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            devices.addAll(json.readValue(file.toFile(), new TypeReference<List<Device>>() { }));
        } catch (IOException e) {
            // 文件损坏：改名保留，从空列表开始，服务照常启动
            try {
                Files.move(file, file.resolveSibling("devices.json.bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // 改名也失败就算了
            }
        }
    }

    public synchronized List<Device> list() {
        return List.copyOf(devices);
    }

    public synchronized Optional<Device> find(String id) {
        return devices.stream().filter(d -> d.id().equals(id)).findFirst();
    }

    public synchronized Device add(String name, String token) {
        String t = token.trim();
        String n = name == null || name.isBlank() ? "设备 " + (devices.size() + 1) : name.trim();
        for (int i = 0; i < devices.size(); i++) {
            Device d = devices.get(i);
            if (d.token().equals(t)) {
                Device renamed = new Device(d.id(), n, t, d.addedAt());
                devices.set(i, renamed);
                save();
                return renamed;
            }
        }
        Device d = new Device(UUID.randomUUID().toString().substring(0, 8), n, t, Instant.now());
        devices.add(d);
        save();
        return d;
    }

    public synchronized boolean delete(String id) {
        boolean removed = devices.removeIf(d -> d.id().equals(id));
        if (removed) save();
        return removed;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("devices.json.tmp");
            json.writeValue(tmp.toFile(), devices);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("保存设备列表失败", e);
        }
    }
}
