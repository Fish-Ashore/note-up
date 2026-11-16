package com.noteup.service;

import com.noteup.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评论发布的进程内限流器。
 *
 * <p>位于评论写入链路中 {@code CommentService} 调用数据库插入之前，用来拒绝过频的请求，
 * 防止单个账号刷屏。采用“每分钟/每天”双窗口：一分钟窗口抑制瞬时连发，一天窗口控制总量，
 * 阈值来自 {@code app.comments.per-minute} 与 {@code app.comments.per-day}。</p>
 *
 * <p>关键设计：</p>
 * <ul>
 *   <li>按用户分桶，每个用户维护一条按时间升序排列的时间戳队列；</li>
 *   <li>外层使用 {@link ConcurrentHashMap} 做用户隔离，桶内用 {@code synchronized}
 *       把“检查配额”和“记录本次发送”合并为原子操作，避免并发下超额；</li>
 *   <li>每次取用时顺带从队首淘汰超过一天的旧记录，因此无需独立的定时清理任务；</li>
 *   <li>状态只存在当前 JVM 内：重启后清零，多实例部署时各实例独立计数，
 *       这是有意的轻量实现，而非全局精确配额。</li>
 * </ul>
 */
@Component
public class CommentRateLimiter {

    /** 一分钟窗口的毫秒数。 */
    private static final long MINUTE = 60_000L;
    /** 一天窗口的毫秒数，用乘法而非魔法数字表达时间单位。 */
    private static final long DAY = 24 * 3600_000L;

    private final AppProperties properties;
    /** 用户 id 到发送时间队列的映射；队列元素为毫秒时间戳，按插入顺序天然升序。 */
    private final Map<Long, Deque<Long>> history = new ConcurrentHashMap<>();

    /**
     * @param properties 应用配置，提供评论的每分钟/每天配额
     */
    public CommentRateLimiter(AppProperties properties) {
        this.properties = properties;
    }

    /**
     * 尝试为用户申请一次评论配额；成功时会立即记录本次时间戳。
     *
     * <p>判定顺序：先淘汰一天前的记录，再检查日配额，最后统计一分钟内的记录数。
     * 先做日配额检查可以在超限时少遍历一次队列；两个判断都基于历史记录，只有未达到上限
     * 才写入本次请求，因此返回值直接表示“本次是否放行”。</p>
     *
     * @param userId 发表评论的用户 id
     * @return {@code true} 表示允许发布并已记账；{@code false} 表示触及任一窗口上限
     */
    public boolean tryAcquire(long userId) {
        long now = System.currentTimeMillis();
        Deque<Long> queue = history.computeIfAbsent(userId, key -> new ArrayDeque<>());
        synchronized (queue) {
            // 队列按时间升序，队首一旦超过一天就不会再影响日窗口或分钟窗口，可安全淘汰。
            while (!queue.isEmpty() && queue.peekFirst() < now - DAY) {
                queue.removeFirst();
            }
            // 此时队列长度即“最近一天”的发送次数。
            if (queue.size() >= properties.getComments().getPerDay()) {
                return false;
            }
            // 分钟窗口无法只靠队首判断，需要统计最近一分钟内的记录数。
            long minuteAgo = now - MINUTE;
            long recent = queue.stream().filter(ts -> ts >= minuteAgo).count();
            if (recent >= properties.getComments().getPerMinute()) {
                return false;
            }
            queue.addLast(now);
            return true;
        }
    }
}