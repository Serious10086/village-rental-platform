-- ============================================================================
--  乡村闲置资源租售平台 —— 视图脚本
--  4 个视图。学校硬性指标要求"索引、视图、事务至少使用两种",本项目三种全用。
--
--  前置:先执行 01_schema_sys.sql 和 02_schema_biz.sql
--        (视图依赖的基表必须已存在,否则 CREATE VIEW 会报错)
--
--  【本机环境注意】ONLY_FULL_GROUP_BY 已开启,
--   视图 3、4 中所有非聚合列都必须出现在 GROUP BY 里,否则创建失败。
--
--  【MyBatis-Plus 注意】@TableLogic 对视图不生效(视图里没有 deleted 列),
--   所以软删条件必须写进视图定义内部,不能靠框架自动加。这是刻意的。
-- ============================================================================

SET NAMES utf8mb4;
USE `village_rental`;


-- ----------------------------------------------------------------------------
-- 视图 1:v_resource_list 资源列表视图
--
-- 为什么有价值(不是为了凑数):
--   1) 前台首页、资源列表页、搜索结果页、收藏页四处都要展示
--      "资源名 + 分类名 + 村组名 + 发布人 + 信用分",这段 4 表 JOIN
--      会被复制粘贴四遍,任何一处漏 JOIN sys_user,发布人姓名就为空。
--   2) 更关键的是安全边界:resource_status = 1 被固化在视图里。
--      未审核(0)、驳回(5)、违规下架(6)的资源永远不可能从视图漏出去。
--      如果靠每个 Service 手写条件,早晚有一处忘了加,导致违规资源
--      出现在前台 —— 这是真实事故。把安全条件内聚到视图里,
--      是视图在这个系统里最硬的价值。
--   3) deleted = 0 也写在这里,因为 @TableLogic 对视图不生效。
--
-- 使用方式:外层照常加筛选和分页,MySQL 的 MERGE 算法会把外层条件下推到
-- 基表,idx_res_category_status / idx_res_trade_price 等索引仍然生效。
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW `v_resource_list` AS
SELECT
    r.`id`,
    r.`resource_no`,
    r.`resource_name`,
    r.`cover_url`,
    r.`images`,
    r.`tags`,
    r.`trade_type`,
    r.`price`,
    r.`price_unit`,
    r.`deposit`,
    r.`old_degree`,
    r.`rent_start_date`,
    r.`rent_end_date`,
    r.`rentable_weekdays`,
    r.`location`,
    r.`view_count`,
    r.`order_count`,
    r.`favorite_count`,
    r.`risk_level`,
    r.`need_training`,
    r.`create_time`,
    r.`publisher_id`,
    r.`village_id`,
    r.`category_id`,
    c.`category_name`,
    c.`category_code`,
    v.`village_name`,
    v.`town_name`,
    u.`real_name`     AS `publisher_name`,
    u.`avatar`        AS `publisher_avatar`,
    u.`credit_score`  AS `publisher_credit`
FROM `biz_resource` r
JOIN `biz_resource_category` c ON c.`id` = r.`category_id`
JOIN `sys_village`           v ON v.`id` = r.`village_id`
JOIN `sys_user`              u ON u.`id` = r.`publisher_id`
WHERE r.`resource_status` = 1
  AND r.`deleted` = 0;


-- ----------------------------------------------------------------------------
-- 视图 2:v_order_detail 订单详情视图
--
-- 为什么有价值:
--   订单详情要同时展示资源信息、买卖双方姓名/手机/信用分、村组。
--   三处复用 —— 前台"我的订单详情"、后台"订单管理"、
--   纠纷处理时调取订单数据(需求原文明确要求"调取聊天记录与订单数据作为依据")。
--   如果纠纷页面和订单详情页各写一遍 4 表 JOIN,维护成本翻倍,
--   且纠纷页面很容易漏掉某个字段。
--
--   sys_user 在这段 SQL 里 JOIN 了两次(买家别名 b、卖家别名 s),
--   这种自连接别名在四个地方各写一遍,出错概率很高。
--
-- 【与视图 1 的关键差别】本视图不带任何 WHERE。
--   因为不同角色可见范围不同(村民看自己的、村管看本村的、超管看全部),
--   数据级过滤必须在外层用 village_id 兜住,不能像视图 1 那样内聚。
--
--   判断依据(建议写进论文):"哪些条件能固化进视图、哪些必须留外层,
--   取决于这个条件对所有调用方是否一致。"
--   —— "只看上架资源"对所有调用方一致,可以固化;
--      "只看本村"因角色而异,不能固化。
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW `v_order_detail` AS
SELECT
    o.`id`,
    o.`order_no`,
    o.`order_type`,
    o.`resource_id`,
    o.`village_id`,
    o.`buyer_id`,
    o.`seller_id`,
    o.`rent_start_date`,
    o.`rent_end_date`,
    o.`rent_days`,
    o.`unit_price`,
    o.`total_amount`,
    o.`deposit_amount`,
    o.`pay_amount`,
    o.`order_status`,
    o.`pay_status`,
    o.`delivery_type`,
    o.`training_confirmed`,
    o.`buyer_remark`,
    o.`cancel_reason`,
    o.`confirm_time`,
    o.`pay_time`,
    o.`deliver_time`,
    o.`return_time`,
    o.`finish_time`,
    o.`create_time`,
    r.`resource_name`,
    r.`cover_url`,
    r.`trade_type`,
    r.`location`,
    r.`use_rule`,
    r.`publisher_id`,
    b.`real_name`    AS `buyer_name`,
    b.`phone`        AS `buyer_phone`,
    b.`credit_score` AS `buyer_credit`,
    s.`real_name`    AS `seller_name`,
    s.`phone`        AS `seller_phone`,
    s.`credit_score` AS `seller_credit`,
    v.`village_name`
FROM `biz_order` o
JOIN `biz_resource` r ON r.`id` = o.`resource_id`
JOIN `sys_user`     b ON b.`id` = o.`buyer_id`
JOIN `sys_user`     s ON s.`id` = o.`seller_id`
JOIN `sys_village`  v ON v.`id` = o.`village_id`;


-- ----------------------------------------------------------------------------
-- 视图 3:v_village_resource_stat 村级资源统计视图
--
-- 为什么有价值:
--   这是需求原文的直接映射 —— "统计本村闲置资源总量、租售利用率、热门品类"。
--   usage_rate(租售利用率)就是需求里的原词。
--
--   它是三个页面的唯一数据源:村级管理员工作台(只看自己村,外层加
--   WHERE village_id = ?)、超级管理员全局对比(按 usage_rate 排序看各村排名)、
--   后台数据统计与公示页。没有视图的话,这段 4 表 JOIN + 多个 CASE WHEN
--   的聚合 SQL 要写三遍,且任何一处口径改了另外两处就会不一致 ——
--   **统计口径不一致是答辩老师最爱追问的点**。
--
-- 必须全用 LEFT JOIN:新建的村还没有任何资源,用 INNER JOIN 会让这个村凭空消失。
--
-- COUNT(DISTINCT ...) 不可省:三张表 JOIN 在一起会产生笛卡尔积
-- (r × o × u),不加 DISTINCT 计数会被放大。
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW `v_village_resource_stat` AS
SELECT
    v.`id`           AS `village_id`,
    v.`village_name`,
    v.`town_name`,
    COUNT(DISTINCT r.`id`) AS `resource_total`,
    COUNT(DISTINCT CASE WHEN r.`resource_status` = 1 THEN r.`id` END) AS `online_count`,
    COUNT(DISTINCT CASE WHEN r.`trade_type` = 1 AND r.`resource_status` = 1 THEN r.`id` END) AS `rent_count`,
    COUNT(DISTINCT CASE WHEN r.`trade_type` = 2 AND r.`resource_status` = 1 THEN r.`id` END) AS `sell_count`,
    COUNT(DISTINCT CASE WHEN r.`risk_level` = 3 THEN r.`id` END)    AS `high_risk_count`,
    COUNT(DISTINCT u.`id`)                                          AS `user_total`,
    COUNT(DISTINCT o.`id`)                                          AS `order_total`,
    COUNT(DISTINCT CASE WHEN o.`order_status` = 4 THEN o.`id` END)  AS `order_finished`,
    COUNT(DISTINCT CASE WHEN o.`order_status` = 3 THEN o.`id` END)  AS `order_using`,
    -- 租售利用率 = (交易中 + 已完成) 订单数 / 资源总数 * 100
    -- 外层 IFNULL 不可省:新建的村还没有任何资源时,NULLIF 会让整个除法返回
    -- NULL,前端统计卡片就会显示空白而不是 "0%"。分母为 0 时应显示 0。
    IFNULL(ROUND(
        IFNULL(COUNT(DISTINCT CASE WHEN o.`order_status` IN (3, 4) THEN o.`id` END), 0)
        / NULLIF(COUNT(DISTINCT r.`id`), 0) * 100, 2
    ), 0)                                                           AS `usage_rate`
FROM `sys_village` v
LEFT JOIN `biz_resource` r ON r.`village_id` = v.`id` AND r.`deleted` = 0
LEFT JOIN `biz_order`    o ON o.`village_id` = v.`id`
LEFT JOIN `sys_user`     u ON u.`village_id` = v.`id` AND u.`deleted` = 0
GROUP BY v.`id`, v.`village_name`, v.`town_name`;


-- ----------------------------------------------------------------------------
-- 视图 4:v_user_credit_summary 用户信用概览视图
--
-- 为什么有价值:
--   需求原文是"查看自身信用评分(基于交易履约、资源完好率、用户评价等)"——
--   三个词对应视图里的 fulfill_rate(交易履约)、intact_rate(资源完好率)、
--   avg_score(用户评价),一一对应。
--
--   没有这个视图,"我的信用"页面就得在 Java 里查 4 次、内存里算 3 个比率;
--   村级管理员"信用管理"页面还要再实现一遍同样口径。
--   更实际的是:论文里"信用评分模型"这一节可以直接贴这个视图定义的 SQL
--   作为评分口径的实现,比空谈公式有说服力得多。
--
-- 【实现方式说明】用标量子查询而非 LEFT JOIN + GROUP BY。
--   原因是 JOIN 会把订单和评价两张表乘在一起产生笛卡尔积;
--   虽然对一个用户而言重复系数是均匀的、比率结果恰好正确,
--   但那是"恰好对",读代码的人要推演一遍才敢信。
--   标量子查询每个指标彼此独立,正确性一眼可见 ——
--   对毕设而言,可读性和可辩护性比微秒级性能重要。
--   配合 idx_order_buyer / idx_order_seller / idx_eval_to_user 索引,
--   百级用户规模下的开销可以忽略。
--
-- 【最容易写错的地方】e.status = 1 必须放在子查询的 WHERE 里,
--   否则被隐藏的违规评价还是会被算进信用分。
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW `v_user_credit_summary` AS
SELECT
    u.`id`           AS `user_id`,
    u.`real_name`,
    u.`phone`,
    u.`village_id`,
    u.`credit_score`,
    -- 已完成订单数
    (SELECT COUNT(*) FROM `biz_order` o
      WHERE o.`order_status` = 4 AND (o.`buyer_id` = u.`id` OR o.`seller_id` = u.`id`)) AS `finish_order_count`,
    -- 已取消订单数
    (SELECT COUNT(*) FROM `biz_order` o
      WHERE o.`order_status` = 5 AND (o.`buyer_id` = u.`id` OR o.`seller_id` = u.`id`)) AS `cancel_order_count`,
    -- 纠纷中订单数
    (SELECT COUNT(*) FROM `biz_order` o
      WHERE o.`order_status` = 6 AND (o.`buyer_id` = u.`id` OR o.`seller_id` = u.`id`)) AS `dispute_order_count`,
    -- 订单总数(算履约率的分母)
    (SELECT COUNT(*) FROM `biz_order` o
      WHERE o.`buyer_id` = u.`id` OR o.`seller_id` = u.`id`)                            AS `total_order_count`,
    -- 用户评价均分(只算正常状态的评价)
    --
    -- 【这里刻意不包 IFNULL(..., 0)】没有评价时返回 NULL 而不是 0。
    -- 0 分和"暂无评分"是两回事:前者是"所有人都给了差评",
    -- 后者是"还没人评过"。用 0 表示"没有数据",前端就只能显示 0.0 分,
    -- 页面上看起来像这个人信用崩了 —— 实际上他只是个新用户。
    -- 下面三列同理。
    (SELECT ROUND(AVG(e.`score`), 2) FROM `biz_evaluation` e
      WHERE e.`to_user_id` = u.`id` AND e.`status` = 1)                                  AS `avg_score`,
    -- 评价条数
    (SELECT COUNT(*) FROM `biz_evaluation` e
      WHERE e.`to_user_id` = u.`id` AND e.`status` = 1)                                  AS `eval_count`,
    -- 资源完好率:资源相符度评分 >= 4 的占比
    --
    -- NULLIF(COUNT(...), 0) 保证分母为 0 时整体返回 NULL。
    -- 【为什么买家这一列会是 NULL 而不是 0%】"资源相符度"是买家评卖家的维度,
    -- 卖家评买家时这一项不填 —— 买家手里的是钱,不是被租的东西。
    -- 所以一个纯买家永远没有这一项的数据,显示"暂无"才是对的。
    (SELECT ROUND(SUM(CASE WHEN e.`resource_score` >= 4 THEN 1 ELSE 0 END)
                  / NULLIF(COUNT(e.`resource_score`), 0) * 100, 2)
      FROM `biz_evaluation` e
     WHERE e.`to_user_id` = u.`id` AND e.`status` = 1)                                   AS `intact_rate`,
    -- 交易履约率:已完成订单 / 订单总数
    (SELECT ROUND(COUNT(CASE WHEN o.`order_status` = 4 THEN 1 END)
                  / NULLIF(COUNT(*), 0) * 100, 0)
      FROM `biz_order` o
     WHERE o.`buyer_id` = u.`id` OR o.`seller_id` = u.`id`)                              AS `fulfill_rate`
FROM `sys_user` u
WHERE u.`deleted` = 0;


-- ============================================================================
--  验证
-- ============================================================================
SELECT '视图创建完成' AS msg, COUNT(*) AS view_count
FROM information_schema.views
WHERE table_schema = 'village_rental';
