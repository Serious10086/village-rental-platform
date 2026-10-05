package com.gjf.village.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 资源收藏。
 *
 * <p><b>没有 {@code deleted} 列,取消收藏是物理删除。</b>
 * 和留言、订单那些"逻辑删除"的表不一样:收藏没有状态,一行的存在
 * 就是"收藏了"这个事实,一行的消失就是"取消了"。留一条 {@code deleted=1}
 * 的记录既没有审计价值(收藏不是证据),又会让唯一约束
 * {@code uk_favorite_user_resource} 变得难用 —— 取消之后重新收藏会撞上
 * 那条软删的行,得先把 deleted 改回来,一个本来原子的操作变成两步。
 *
 * <p>防重复收藏靠唯一约束而不是"先查再插":后者在并发下有两个请求
 * 同时查到"还没收藏"的窗口。业务代码把 {@code DuplicateKeyException}
 * 当成"已经收藏了"处理即可。
 */
@Data
@TableName("biz_resource_favorite")
public class BizResourceFavorite implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long resourceId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
