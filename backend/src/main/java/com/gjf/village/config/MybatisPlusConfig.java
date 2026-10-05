package com.gjf.village.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置。
 *
 * <p>逻辑删除和自动填充不在这里配 —— 它们通过 application.yml 的
 * {@code mybatis-plus.global-config} 和实体类上的 {@code @TableField(fill = ...)}
 * 声明,配置更集中。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * MyBatis-Plus 插件链。
     *
     * <p><b>顺序很重要:</b>分页插件必须放在最后。如果先加分页再加其他插件,
     * 分页会在其他插件改写 SQL 之前就计算好 count,导致分页数量不对。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 乐观锁(@Version 注解),用于并发更新场景
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 分页插件 —— 必须最后加
        // 指定 DbType.MYSQL 而不是让它自动探测,避免多数据源时探测错误
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最大条数限制,防止前端传 pageSize=999999 把库拖垮
        pagination.setMaxLimit(500L);
        // 超过最大页数后返回空数据而不是回到第一页,避免前端翻页时出现诡异数据
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        return interceptor;
    }
}
