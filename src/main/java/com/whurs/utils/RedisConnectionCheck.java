package com.whurs.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisConnectionCheck {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public boolean isRedisAvailable() {
        try {
            String result = stringRedisTemplate.execute((RedisCallback<String>) connection -> {
                String response= connection.ping();
                return response;
            });
            return "PONG".equalsIgnoreCase(result);
        } catch (Exception e) {
            // Redis 不可用或超时
            return false;
        }
    }
}
