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
 * 用户行为记录 —— 推荐算法的唯一数据源。
 *
 * <h3>为什么要冗余 {@code categoryId}</h3>
 *
 * <p>取"这个用户最近偏好哪些分类"是推荐的主查询,而它每次都只用到
 * {@code category_id}。如果每次都要 {@code JOIN biz_resource} 才能拿到分类,
 * 那 {@code idx_beh_user_category} 这个索引就用不上了(索引只能加速
 * 本表的列)。所以分类在写入时就跟着行为一起存下来 ——
 * <b>用一点冗余换掉推荐路径上的一个 JOIN</b>,这是这类"读多写少"的
 * 记录表常见的取舍。
 *
 * <p>代价是这一列可能和资源当前的分类不一致(资源改分类之后,
 * 历史行为里记的还是老分类)。这个不一致是<b>可以接受</b>的:
 * 行为记录本来就该反映"用户当时对什么感兴趣",而不是"那个资源现在属于哪一类"。
 *
 * <h3>为什么 {@code source} 这个字段不能省</h3>
 *
 * <p>它记的是"这次行为是从哪儿发生的":列表页、详情页、搜索,还是<b>推荐位</b>。
 * 没有它就没法回答"推荐到底有没有用" —— 推荐位曝光多少次、点击多少次、
 * 点进去之后下单多少,全靠这一列区分。后台的推荐位点击率统计
 * 正是按 {@code source = 4} 过滤出来的。
 */
@Data
@TableName("ai_user_behavior")
public class AiUserBehavior implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    // ── 行为类型 ────────────────────────────────────────────────
    public static final int TYPE_VIEW = 1;      // 浏览
    public static final int TYPE_SEARCH = 2;    // 搜索
    public static final int TYPE_FAVORITE = 3;  // 收藏
    public static final int TYPE_ORDER = 4;     // 下单
    public static final int TYPE_EVALUATE = 5;  // 评价

    /**
     * 推荐位曝光(一次推荐请求记<b>一行</b>,不是每个卡片一行)。
     *
     * <p><b>为什么必须有这个类型:没有它就算不出点击率。</b>
     * 点击率 = 点击 ÷ 曝光,而"曝光"在原来的五种类型里没有任何一种表达得了 ——
     * 只有点击、没有分母,就只能报一个光秃秃的点击数,
     * 那个数字既说明不了推荐是好是坏,也做不出论文里的对比图。
     *
     * <p>为什么一次请求只记一行而不是 N 个卡片各记一行:
     * 记 N 行的话,首页每刷一次就多十几行,而它们的信息量是一样的
     * (同一时刻、同一个推荐位)。一行"这次曝光了几个"就够了 ——
     * 代价是算不出"某一个具体卡片被曝光了多少次",但那个粒度
     * 这一批不需要。
     */
    public static final int TYPE_IMPRESSION = 6;

    // ── 来源 ────────────────────────────────────────────────────
    public static final int SOURCE_LIST = 1;        // 列表页
    public static final int SOURCE_DETAIL = 2;      // 详情页
    public static final int SOURCE_SEARCH = 3;      // 搜索
    /** 推荐位。后台的点击率统计只认这一个值 */
    public static final int SOURCE_RECOMMEND = 4;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 用户所属村组。NULL 表示没绑定村组 */
    private Long villageId;

    /** 见上面的 TYPE_* */
    private Integer behaviorType;

    /** 目标资源ID。搜索行为为 NULL —— 搜索的目标是一个词,不是一条资源 */
    private Long resourceId;

    /** 冗余的分类ID(推荐算法的主查询用它,避免 JOIN) */
    private Long categoryId;

    /** 搜索关键词,仅搜索行为有值 */
    private String keyword;

    /** 停留时长(秒),浏览行为的权重信号 */
    private Integer staySeconds;

    /** 见上面的 SOURCE_* */
    private Integer source;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
