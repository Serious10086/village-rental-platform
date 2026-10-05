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
 * 留言点赞表。
 *
 * <p><b>防重复点赞靠的是 {@code uk_board_user(board_id, user_id)} 唯一约束,
 * 不是 Service 里的"先查再插"。</b>后者在并发下有个致命窗口:
 * 两个请求同时查到"还没点过",然后各自插入,结果两条点赞记录、
 * 计数 +2。Service 层仍然会先查一次 —— 但那次查询的目的只是
 * "给用户一个友好的提示",真正拦得住的是数据库。
 *
 * <p>这里也解释了为什么 {@code biz_board.like_count} 这个冗余列
 * 值得存在:列表页要显示每条留言的点赞数,不冗余的话每条都要
 * {@code COUNT(*)},十条留言就是十次子查询。代价是计数可能和明细
 * 对不上 —— 所以演示数据脚本里有一条专门的校验查询在盯着它。
 */
@Data
@TableName("biz_board_like")
public class BizBoardLike implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long boardId;

    private Long userId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
