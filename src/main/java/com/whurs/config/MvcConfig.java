package com.whurs.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.whurs.dto.UserDTO;
import com.whurs.utils.LoginInterceptor;
import com.whurs.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private RefreshTokenInterceptor refreshTokenInterceptor;
    @Resource
    private LoginInterceptor loginInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(refreshTokenInterceptor)
                .addPathPatterns("/**")
                .order(0);
        //登录拦截器
        registry.addInterceptor(loginInterceptor)
                .excludePathPatterns(
                  "/user/login",
                  "/user/code",
                  "/shop/**",
                  "/shop-type/**",
                  "/blog/*",
                  "/voucher/**"
                ).order(1);

    }
}
