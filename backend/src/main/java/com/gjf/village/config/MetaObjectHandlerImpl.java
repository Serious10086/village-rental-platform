package com.gjf.village.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 公共字段自动填充。
 *
 * <p>实体类上标注 {@code @TableField(fill = FieldFill.INSERT)} 或
 * {@code FieldFill.INSERT_UPDATE} 的字段,在插入/更新时自动写入时间,
 * 业务代码里不用再手动 set。
 *
 * <p><b>注意:</b>数据库层面虽然也对 create_time / update_time 设了
 * {@code DEFAULT CURRENT_TIMESTAMP},但那只在 SQL 里完全没写该列时才生效。
 * 有了这个填充器,插入时时间戳由 Java 侧统一生成,插入和更新行为一致。
 */
@Component
public class MetaObjectHandlerImpl implements MetaObjectHandler {

    private static final String CREATE_TIME = "createTime";
    private static final String UPDATE_TIME = "updateTime";

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        // strictInsertFill 只在字段为 null 时填充,不会覆盖业务代码显式设置的值
        this.strictInsertFill(metaObject, CREATE_TIME, LocalDateTime.class, now);
        this.strictInsertFill(metaObject, UPDATE_TIME, LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, UPDATE_TIME, LocalDateTime.class, LocalDateTime.now());
    }
}
