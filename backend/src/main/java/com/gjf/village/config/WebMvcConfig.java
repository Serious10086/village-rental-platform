package com.gjf.village.config;

import com.gjf.village.interceptor.JwtAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Web MVC 配置。
 *
 * <p>负责三件事:跨域、JWT 拦截器注册、上传图片的静态资源映射。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;

    /** 上传文件的物理存储目录 */
    @Value("${app.upload.path}")
    private String uploadPath;

    /**
     * 允许跨域的前端来源,逗号分隔。
     *
     * <p>开发期是 Vite 的默认端口 5173;部署后由 Nginx 反向代理,
     * 前后端同源,这里配不配都不影响。
     *
     * <p>用配置项而不是写死,是因为换端口或换机器时不用改代码。
     */
    @Value("${app.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // allowedOriginPatterns 而非 allowedOrigins:
                // 当 allowCredentials=true 时,allowedOrigins 不允许写 "*",
                // 用 patterns 可以同时支持通配和携带凭证
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // 允许携带 Cookie / Authorization 头
                .allowCredentials(true)
                // 预检请求结果缓存 1 小时,减少 OPTIONS 请求
                .maxAge(3600);
    }

    /**
     * 注册 JWT 认证拦截器。
     *
     * <p>拦截范围是全部路径 —— 免登录的白名单由拦截器内部判断,
     * 因为公开接口也需要"能知道当前访问者是谁"(比如资源列表要标记
     * "我收藏过的"),所以不能简单地在注册时排除掉。
     *
     * <p>排除 Swagger 相关路径:接口文档页面本身不需要登录,
     * 而且它的静态资源请求很频繁,没必要每次走一遍拦截器逻辑。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/v3/api-docs/**",
                        "/doc.html",
                        "/webjars/**",
                        "/error"
                );
    }

    /**
     * 上传图片的静态资源映射。
     *
     * <p>把 URL 路径 {@code /upload/**} 映射到磁盘目录 {@code app.upload.path}。
     *
     * <p><b>为什么不放到 src/main/resources/static 下面:</b>
     * 那个目录会被打进 jar 包,运行时往里写文件在开发期看着能用,
     * 一旦打成 jar 部署就会失败(或者写到临时解压目录,重启即丢)。
     * 放到外部目录 + 静态映射,才是能部署的做法,也方便挂 Docker volume。
     *
     * <p><b>路径末尾的斜杠不能省</b> —— Spring 要求资源位置以分隔符结尾,
     * 否则会把最后一段当成文件名解析。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 用 Paths 拼接而不是手写字符串,避免 Windows 盘符和分隔符处理的坑
        String location = Paths.get(uploadPath).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/upload/**")
                .addResourceLocations(location);
    }
}
