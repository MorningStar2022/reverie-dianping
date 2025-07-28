package com.whurs.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    //封装逻辑过期时间
    private LocalDateTime expireTime;
    private Object data;
}
