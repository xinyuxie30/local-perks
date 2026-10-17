package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 登录拦截器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        // 内容只读放行：笔记详情、点赞列表、评论列表对游客开放；
                        // 写接口（发笔记/点赞/评论/回复/删除）路径段数不同，仍走登录校验
                        "/blog/*",
                        "/blog/likes/*",
                        "/blog/of/shop/**",
                        "/blog-comments/of/blog/**",
                        // 前端启动时读取的公开配置（订单持有分钟数等）
                        "/config/public",
                        // 身份探测：未登录返回 ok(null)，避免游客一进内容页就被弹去登录
                        "/user/me",
                        "/user/code",
                        "/user/login",
                        // 异常转发到 /error 时不再走登录拦截，避免 401 覆盖真实错误体
                        "/error"
                ).order(1);
        // token刷新的拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
