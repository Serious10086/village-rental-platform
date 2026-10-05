package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 留言板条目(主帖与回复<b>共用同一个 VO</b>)。
 *
 * <p>这不是图省事。主帖和回复在数据库里就是同一张表的同一组列,
 * 如果这里硬要拆成 {@code BoardPostVO} 和 {@code BoardReplyVO},
 * 两个类的字段会一字不差地重复一遍,然后每一处字段改动都要改两遍。
 * 真正有差别的只有语义:{@code parentId == null} 就是主帖。
 *
 * <p>前端也用同一个组件渲染两种条目 —— 一条留言在列表里是主帖、
 * 在回复列表里是回复,长得本来就一样。
 */
@Data
public class BoardItemVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** NULL = 主帖 */
    private Long parentId;

    /** 已按敏感词策略处理过的正文:命中替换类词的部分显示为 *** */
    private String content;

    private List<String> images;

    private Integer likeCount;

    /** 只对主帖有意义,回复行恒为 0 */
    private Integer replyCount;

    /**
     * 正文里是否有被过滤的词。
     *
     * <p>前端据此显示「内容已过滤」标记。<b>这个标记必须给出来</b> ——
     * 不说破的话,用户会以为对方打错了字,反而去追问"你说的**是什么意思",
     * 把本来被压下去的东西又翻出来。
     */
    private Integer isFiltered;

    private Integer status;

    private LocalDateTime createTime;

    // ── 作者信息(从 sys_user 联出来) ──────────────────────────
    private Long userId;
    private String userName;
    private String userAvatar;

    private Long villageId;
    private String villageName;

    /**
     * 当前登录用户是否已点过赞。
     *
     * <p>服务端算好返回,不让前端再发一次请求判断 —— 列表里十条留言
     * 就是十次请求。这个字段和 {@code likeCount} 一起,让"点赞"这个
     * 按钮的状态在一次响应里就完整了。
     */
    private Boolean liked;
}
