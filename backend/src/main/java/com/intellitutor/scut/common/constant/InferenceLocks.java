package com.intellitutor.scut.common.constant;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/** Task-owned locks prevent late completion from deleting a successor's lock. */
public final class InferenceLocks {
    // Covers the existing 30-minute queue TTL plus proxy execution and snapshot recovery.
    public static final int TTL_SECONDS = 3600;
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end", Long.class);

    private InferenceLocks() {}

    public static void release(StringRedisTemplate redis, String key, String taskId) {
        redis.execute(RELEASE, List.of(key), taskId);
    }
}
