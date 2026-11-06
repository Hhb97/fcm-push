package io.github.hhb97.fcmpush.push;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Service;

/** 发送 + 最近发送记录（内存中保留 200 条，重启清空）。 */
@Service
public class PushService {

    private static final int HISTORY_LIMIT = 200;

    private final PushSender sender;
    private final Deque<PushOutcome> history = new ArrayDeque<>();

    public PushService(PushSender sender) {
        this.sender = sender;
    }

    public PushOutcome send(PushRequest request) {
        PushOutcome outcome = sender.send(request);
        synchronized (history) {
            history.addFirst(outcome);
            while (history.size() > HISTORY_LIMIT) history.removeLast();
        }
        return outcome;
    }

    public List<PushOutcome> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }
}
