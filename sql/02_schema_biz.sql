-- ============================================================================
--  乡村闲置资源租售平台 —— 建表脚本(二):资源域 + 交易域 + 互动域 + AI 域
--  共 17 张表
--
--  【重要】本脚本会 DROP 并重建这 17 张表,已有数据会丢失。
--
--  前置:必须先执行 01_schema_sys.sql(本脚本的外键指向 sys_user / sys_village 等表)
--  后续:03_view.sql → 04_seed.sql
-- ============================================================================

SET NAMES utf8mb4;
USE `village_rental`;

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `biz_board_like`;
DROP TABLE IF EXISTS `biz_board`;
DROP TABLE IF EXISTS `msg_notice`;
DROP TABLE IF EXISTS `msg_chat_message`;
DROP TABLE IF EXISTS `msg_conversation`;
DROP TABLE IF EXISTS `biz_announcement`;
DROP TABLE IF EXISTS `biz_dispute`;
DROP TABLE IF EXISTS `biz_withdraw`;
DROP TABLE IF EXISTS `biz_credit_record`;
DROP TABLE IF EXISTS `biz_evaluation`;
DROP TABLE IF EXISTS `biz_payment`;
DROP TABLE IF EXISTS `biz_order`;
DROP TABLE IF EXISTS `ai_user_behavior`;
DROP TABLE IF EXISTS `ai_chat_record`;
DROP TABLE IF EXISTS `biz_resource_favorite`;
DROP TABLE IF EXISTS `biz_resource`;
DROP TABLE IF EXISTS `biz_resource_category`;

SET FOREIGN_KEY_CHECKS = 1;


-- ============================================================================
--  资源域(3 张)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 10. biz_resource_category 资源分类表
--     risk_level 挂分类上,发布资源时自动继承到 biz_resource.risk_level ——
--     "高风险资源需培训"由分类驱动,不用人工逐条判断。
-- ----------------------------------------------------------------------------
CREATE TABLE `biz_resource_category` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '分类ID',
    `parent_id`     BIGINT       NULL     DEFAULT NULL   COMMENT '父分类ID,NULL=一级分类',
    `category_name` VARCHAR(50)  NOT NULL                COMMENT '分类名称',
    `category_code` VARCHAR(30)  NOT NULL                COMMENT '分类编码',
    `icon`          VARCHAR(255) NULL     DEFAULT NULL   COMMENT '图标',
    `risk_level`    TINYINT      NOT NULL DEFAULT 1      COMMENT '默认风险等级:1低 2中 3高',
    `sort_order`    INT          NOT NULL DEFAULT 0      COMMENT '排序号',
    `status`        TINYINT      NOT NULL DEFAULT 1      COMMENT '状态:1启用 0停用',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NULL     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_category_code` (`category_code`),
    KEY `idx_cat_parent_id` (`parent_id`),
    CONSTRAINT `fk_cat_parent` FOREIGN KEY (`parent_id`) REFERENCES `biz_resource_category` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源分类表';


-- ----------------------------------------------------------------------------
-- 11. biz_resource 闲置资源表(核心)
-- ----------------------------------------------------------------------------
CREATE TABLE `biz_resource` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '资源ID',
    `resource_no`       VARCHAR(32)   NOT NULL                COMMENT '资源编号,如 R20260916000001',
    `publisher_id`      BIGINT        NOT NULL                COMMENT '发布村民ID',
    `village_id`        BIGINT        NOT NULL                COMMENT '所在村组ID(数据级权限锚点)',
    `category_id`       BIGINT        NOT NULL                COMMENT '分类ID',
    `resource_name`     VARCHAR(100)  NOT NULL                COMMENT '资源名称',
    `cover_url`         VARCHAR(255)  NULL     DEFAULT NULL   COMMENT '封面图URL',
    `images`            VARCHAR(1500) NULL     DEFAULT NULL   COMMENT '多图URL,逗号分隔',
    `tags`              VARCHAR(200)  NULL     DEFAULT NULL   COMMENT '资源标签,逗号分隔(智能推荐与全文检索用)',
    `description`       TEXT          NULL     DEFAULT NULL   COMMENT '详细描述',
    `trade_type`        TINYINT       NOT NULL                COMMENT '交易方式:1出租 2出售',
    `price`             DECIMAL(10,2) NOT NULL                COMMENT '价格:日租金(出租)或售价(出售)',
    `price_unit`        TINYINT       NOT NULL DEFAULT 1      COMMENT '计价单位:1元/天 2元/次 3元/件(出租用)',
    `deposit`           DECIMAL(10,2) NOT NULL DEFAULT 0.00   COMMENT '押金(出租用)',
    `rent_start_date`   DATE          NULL     DEFAULT NULL   COMMENT '可租起始日',
    `rent_end_date`     DATE          NULL     DEFAULT NULL   COMMENT '可租截止日',
    `rentable_weekdays` VARCHAR(20)   NULL     DEFAULT '1,2,3,4,5,6,7' COMMENT '可租星期,1=周一',
    `use_rule`          VARCHAR(1000) NULL     DEFAULT NULL   COMMENT '使用规则(出租用)',
    `old_degree`        TINYINT       NULL     DEFAULT NULL   COMMENT '新旧程度:1全新 2九成新 3七成新 4五成新及以下(出售用)',
    `delivery_type`     TINYINT       NULL     DEFAULT NULL   COMMENT '交货方式:1自提 2协商(出售用)',
    `location`          VARCHAR(200)  NOT NULL                COMMENT '资源位置文字描述',
    `longitude`         DECIMAL(10,6) NULL     DEFAULT NULL   COMMENT '经度(可选)',
    `latitude`          DECIMAL(10,6) NULL     DEFAULT NULL   COMMENT '纬度(可选)',
    `risk_level`        TINYINT       NOT NULL DEFAULT 1      COMMENT '风险等级,继承自分类',
    `need_training`     TINYINT       NOT NULL DEFAULT 0      COMMENT '是否需培训确认:1是 0否',
    `training_content`  VARCHAR(1000) NULL     DEFAULT NULL   COMMENT '安全使用须知',
    `resource_status`   TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待审核 1已上架 2临时下架 3交易中 4已完成 5审核驳回 6违规下架',
    `offline_reason`    VARCHAR(255)  NULL     DEFAULT NULL   COMMENT '下架/驳回原因',
    `audit_user_id`     BIGINT        NULL     DEFAULT NULL   COMMENT '审核人ID',
    `audit_time`        DATETIME      NULL     DEFAULT NULL   COMMENT '审核时间',
    `view_count`        INT           NOT NULL DEFAULT 0      COMMENT '浏览量',
    `favorite_count`    INT           NOT NULL DEFAULT 0      COMMENT '收藏数',
    `order_count`       INT           NOT NULL DEFAULT 0      COMMENT '成单数',
    `deleted`           TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除:0正常 1已删除',
    `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发布时间',
    `update_time`       DATETIME      NULL     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_resource_no` (`resource_no`),
    -- 村管查"本村待审核资源"(village_id=? AND resource_status=0)完全命中
    KEY `idx_res_village_status` (`village_id`, `resource_status`),
    -- 前台按分类浏览
    KEY `idx_res_category_status` (`category_id`, `resource_status`),
    -- 前台首页"最新上架",避免全表扫描 + filesort
    KEY `idx_res_status_create` (`resource_status`, `create_time`),
    -- "按租售方式 + 价格区间"筛选,价格区间走索引范围扫描
    KEY `idx_res_trade_price` (`trade_type`, `price`),
    -- 村民"我的发布"按状态分页
    KEY `idx_res_publisher` (`publisher_id`, `resource_status`),
    CONSTRAINT `fk_res_publisher` FOREIGN KEY (`publisher_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_res_village` FOREIGN KEY (`village_id`) REFERENCES `sys_village` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_res_category` FOREIGN KEY (`category_id`) REFERENCES `biz_resource_category` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_res_audit_user` FOREIGN KEY (`audit_user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='闲置资源表';

-- 中文全文索引单独加:ngram 分词器要求 ngram_token_size(本机=2),查询词长度需 >= 2 才有效。
-- LIKE '%拖拉机%' 的前置通配符用不上任何索引,是全表扫描;MATCH...AGAINST 才走这个索引。
ALTER TABLE `biz_resource`
    ADD FULLTEXT KEY `ft_res_search` (`resource_name`, `description`, `tags`) WITH PARSER ngram;

-- ----------------------------------------------------------------------------
-- 12. biz_resource_favorite 资源收藏表
--     和 biz_board_like 是同一种结构:一行记录就是"这个人收藏了这个资源"
--     这个事实本身,没有额外状态,所以取消收藏是物理删除而不是软删 ——
--     收藏和留言不一样,它没有"留痕以备复核"的需求。
--
--     唯一约束 uk_favorite_user_resource 才是防重复收藏的那一层。
--     先查再插在并发下有两个请求同时查到"还没收藏"的窗口,唯一约束顶得住,
--     业务代码只需把 DuplicateKeyException 当成"已经收藏了"处理。
--
--     注意它刻意不带 village_id:收藏是个人行为,不需要按村过滤;
--     而"这个资源的村组"顺着 resource_id 就能拿到。多冗余一列就多一处
--     要和 biz_resource 保持一致的地方,没必要。
-- ----------------------------------------------------------------------------
CREATE TABLE `biz_resource_favorite` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT   NOT NULL                COMMENT '收藏人ID',
    `resource_id` BIGINT   NOT NULL                COMMENT '被收藏的资源ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_favorite_user_resource` (`user_id`, `resource_id`),
    -- 「我的收藏」按收藏时间倒序翻页
    KEY `idx_favorite_user_time` (`user_id`, `create_time`),
    -- 反向查询(这个资源被谁收藏了 / 统计收藏数)
    KEY `idx_favorite_resource` (`resource_id`),
    CONSTRAINT `fk_favorite_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_favorite_resource` FOREIGN KEY (`resource_id`) REFERENCES `biz_resource` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资源收藏表';



-- ============================================================================
--  交易域(6 张)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 13. biz_order 订单表(核心)
-- ----------------------------------------------------------------------------
CREATE TABLE `biz_order` (
    `id`                  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '订单ID',
    `order_no`            VARCHAR(32)   NOT NULL                COMMENT '订单号,如 LO20260916000001',
    `order_type`          TINYINT       NOT NULL                COMMENT '订单类型:1租赁 2购买',
    `resource_id`         BIGINT        NOT NULL                COMMENT '资源ID',
    `buyer_id`            BIGINT        NOT NULL                COMMENT '租户/买家ID',
    `seller_id`           BIGINT        NOT NULL                COMMENT '发布村民ID',
    `village_id`          BIGINT        NOT NULL                COMMENT '交易发生村组ID(数据级权限锚点)',
    `rent_start_date`     DATE          NULL     DEFAULT NULL   COMMENT '租期开始日(租赁必填)',
    `rent_end_date`       DATE          NULL     DEFAULT NULL   COMMENT '租期结束日',
    `rent_days`           INT           NULL     DEFAULT NULL   COMMENT '租期天数',
    `unit_price`          DECIMAL(10,2) NOT NULL                COMMENT '成交单价快照(村民后续调价不影响历史订单)',
    `total_amount`        DECIMAL(10,2) NOT NULL                COMMENT '租金/货款总额',
    `deposit_amount`      DECIMAL(10,2) NOT NULL DEFAULT 0.00   COMMENT '押金',
    `pay_amount`          DECIMAL(10,2) NOT NULL                COMMENT '应付总额 = total_amount + deposit_amount',
    `order_status`        TINYINT       NOT NULL DEFAULT 0      COMMENT '订单状态:0待确认 1待支付 2已支付待交付 3使用中 4已完成 5已取消 6纠纷中 7已拒绝',
    `pay_status`          TINYINT       NOT NULL DEFAULT 0      COMMENT '支付状态:0未支付 1已支付 2已退款',
    `delivery_type`       TINYINT       NULL     DEFAULT NULL   COMMENT '交货方式',
    `training_confirmed`  TINYINT       NOT NULL DEFAULT 0      COMMENT '高风险资源培训已确认:1是 0否',
    `buyer_remark`        VARCHAR(500)  NULL     DEFAULT NULL   COMMENT '买家备注',
    `cancel_reason`       VARCHAR(255)  NULL     DEFAULT NULL   COMMENT '取消原因',
    `confirm_time`        DATETIME      NULL     DEFAULT NULL   COMMENT '卖家确认时间',
    `pay_time`            DATETIME      NULL     DEFAULT NULL   COMMENT '支付时间',
    `deliver_time`        DATETIME      NULL     DEFAULT NULL   COMMENT '交付时间',
    `return_time`         DATETIME      NULL     DEFAULT NULL   COMMENT '归还/收货时间',
    `finish_time`         DATETIME      NULL     DEFAULT NULL   COMMENT '完成时间',
    `create_time`         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    `update_time`         DATETIME      NULL     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    -- 租赁时段冲突检测的核心索引。先用 resource_id 定位到一个资源的全部订单
    -- (通常几十条),再在极小集合上做区间重叠比较。
    KEY `idx_order_resource_time` (`resource_id`, `rent_start_date`, `rent_end_date`),
    -- "我的订单"最高频查询
    KEY `idx_order_buyer` (`buyer_id`, `order_status`, `create_time`),
    -- 村民"待我处理"
    KEY `idx_order_seller` (`seller_id`, `order_status`, `create_time`),
    -- 村级管理员本村订单
    KEY `idx_order_village` (`village_id`, `order_status`),
    CONSTRAINT `fk_order_resource` FOREIGN KEY (`resource_id`) REFERENCES `biz_resource` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_order_buyer` FOREIGN KEY (`buyer_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_order_seller` FOREIGN KEY (`seller_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_order_village` FOREIGN KEY (`village_id`) REFERENCES `sys_village` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单表';


-- ----------------------------------------------------------------------------
-- 14. biz_payment 支付流水表
--     一笔订单会产生多条资金记录(支付 + 押金退还 + 货款结算),
--     压在订单表里塞不下 —— 这是"订单-支付"一对多的标准建模。
-- ----------------------------------------------------------------------------
CREATE TABLE `biz_payment` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `pay_no`           VARCHAR(64)   NOT NULL                COMMENT '支付流水号',
    `order_id`         BIGINT        NULL     DEFAULT NULL   COMMENT '关联订单ID(提现类流水为NULL)',
    `user_id`          BIGINT        NOT NULL                COMMENT '付款/收款人ID',
    `pay_type`         TINYINT       NOT NULL                COMMENT '类型:1微信(模拟) 2支付宝(模拟) 3押金退还 4货款结算 5提现打款',
    `amount`           DECIMAL(10,2) NOT NULL                COMMENT '金额',
    `pay_status`       TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待支付 1成功 2失败 3已退款',
    `channel_trade_no` VARCHAR(64)   NULL     DEFAULT NULL   COMMENT '模拟第三方流水号',
    `pay_time`         DATETIME      NULL     DEFAULT NULL   COMMENT '支付时间',
    `refund_time`      DATETIME      NULL     DEFAULT NULL   COMMENT '退款时间',
    `remark`           VARCHAR(255)  NULL     DEFAULT NULL   COMMENT '备注',
    `village_id`       BIGINT        NULL     DEFAULT NULL   COMMENT '村组ID(数据级权限锚点)',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pay_no` (`pay_no`),
    KEY `idx_pay_order_id` (`order_id`),
    KEY `idx_pay_user_time` (`user_id`, `create_time`),
    CONSTRAINT `fk_pay_order` FOREIGN KEY (`order_id`) REFERENCES `biz_order` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_pay_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_pay_village` FOREIGN KEY (`village_id`) REFERENCES `sys_village` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付流水表';


-- ----------------------------------------------------------------------------
-- 15. biz_evaluation 评价表
--     uk_order_from 把"一单一评"的业务规则压到数据库层 ——
--     比 Service 里"先查再插"更可靠,能挡住并发重复提交。
-- ----------------------------------------------------------------------------
CREATE TABLE `biz_evaluation` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_id`       BIGINT       NOT NULL                COMMENT '订单ID',
    `resource_id`    BIGINT       NOT NULL                COMMENT '资源ID',
    `from_user_id`   BIGINT       NOT NULL                COMMENT '评价人ID',
    `to_user_id`     BIGINT       NOT NULL                COMMENT '被评价人ID',
    `score`          TINYINT      NOT NULL                COMMENT '综合评分 1-5',
    `resource_score` TINYINT      NULL     DEFAULT NULL   COMMENT '资源相符度 1-5(信用公式用)',
    `service_score`  TINYINT      NULL     DEFAULT NULL   COMMENT '服务态度 1-5',
    `content`        VARCHAR(500) NULL     DEFAULT NULL   COMMENT '评价文字',
    `images`         VARCHAR(1000) NULL    DEFAULT NULL   COMMENT '评价图片,逗号分隔',
    `is_anonymous`   TINYINT      NOT NULL DEFAULT 0      COMMENT '是否匿名:1是 0否',
    `reply_content`  VARCHAR(500) NULL     DEFAULT NULL   COMMENT '被评价人回复内容',
    `reply_time`     DATETIME     NULL     DEFAULT NULL   COMMENT '回复时间',
    `village_id`     BIGINT       NOT NULL                COMMENT '村组ID(数据级权限锚点)',
    `status`         TINYINT      NOT NULL DEFAULT 1      COMMENT '状态:1正常 2隐藏(违规)',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    `update_time`    DATETIME     NULL     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_from` (`order_id`, `from_user_id`),
    KEY `idx_eval_resource` (`resource_id`, `create_time`),
    KEY `idx_eval_to_user` (`to_user_id`),
    KEY `idx_eval_village` (`village_id`),
    CONSTRAINT `fk_eval_order` FOREIGN KEY (`order_id`) REFERENCES `biz_order` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_eval_resource` FOREIGN KEY (`resource_id`) REFERENCES `biz_resource` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_eval_from_user` FOREIGN KEY (`from_user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_eval_to_user` FOREIGN KEY (`to_user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_eval_village` FOREIGN KEY (`village_id`) REFERENCES `sys_village` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单评价表';


-- ----------------------------------------------------------------------------
-- 16. biz_credit_record 信用分变更流水表
--     只存 sys_user.credit_score 一个数字的话,用户问"我为什么被扣10分"就答不上来,
--     村管"维护本村信用分规则"也无从审计。before/after 双记是对账抓手。
-- ----------------------------------------------------------------------------
CREATE TABLE `biz_credit_record` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`      BIGINT       NOT NULL                COMMENT '用户ID',
    `change_score` INT          NOT NULL                COMMENT '变更值,正加负减',
    `before_score` INT          NOT NULL                COMMENT '变更前分数',
    `after_score`  INT          NOT NULL                COMMENT '变更后分数',
    `reason`       VARCHAR(200) NOT NULL                COMMENT '变更原因,如"按时履约"',
    `biz_type`     TINYINT      NOT NULL                COMMENT '业务类型:1订单履约 2资源完好 3用户评价 4管理员手动 5违规处罚',
    `biz_id`       BIGINT       NULL     DEFAULT NULL   COMMENT '关联订单ID(逻辑关联)',
    `operator_id`  BIGINT       NULL     DEFAULT NULL   COMMENT '操作人ID(管理员手动调整时)',
    `village_id`   BIGINT       NOT NULL                COMMENT '村组ID(数据级权限锚点)',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间',
    PRIMARY KEY (`id`),
    KEY `idx_credit_user_time` (`user_id`, `create_time`),
    KEY `idx_credit_village` (`village_id`, `create_time`),
    KEY `idx_credit_biz` (`biz_type`, `biz_id`),
    CONSTRAINT `fk_credit_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_credit_operator` FOREIGN KEY (`operator_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_credit_village` FOREIGN KEY (`village_id`) REFERENCES `sys_village` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='信用分变更流水表';


-- ----------------------------------------------------------------------------
-- 17. biz_dispute 纠纷协调表
--     后台处理时需"调取聊天记录与订单数据作为依据",所以纠纷必须能关联到订单。
--
--     【order_status_before 为什么必须存在】
--     申请纠纷会把订单状态覆盖成 6(纠纷中),这就把"订单原本走到哪一步"
--     这个信息冲掉了。调解结束后要恢复履约,就必须知道恢复到哪个状态 ——
--     是回到"待交付"还是"使用中",决定了双方接下来该做什么。
--     虽然隐约能从 deliver_time 是否为空推出来,但那是隐式约定,加入
--     别的流转路径(比如允许"待交付"直接交付)就会失效。显式记一列,
--     是让数据自己说明它该被怎么解释。
-- ----------------------------------------------------------------------------
CREATE TABLE `biz_dispute` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `dispute_no`      VARCHAR(32)   NOT NULL                COMMENT '纠纷编号',
    `order_id`        BIGINT        NOT NULL                COMMENT '关联订单ID',
    `applicant_id`    BIGINT        NOT NULL                COMMENT '申请人ID',
    `respondent_id`   BIGINT        NOT NULL                COMMENT '被申请人ID',
    `dispute_type`    TINYINT       NOT NULL                COMMENT '类型:1损坏赔偿 2押金/退款争议 3实物不符 4逾期未还 5其他',
    `description`     TEXT          NOT NULL                COMMENT '纠纷描述',
    `evidence_images` VARCHAR(1000) NULL     DEFAULT NULL   COMMENT '证据图片,逗号分隔',
    `apply_amount`    DECIMAL(10,2) NULL     DEFAULT NULL   COMMENT '诉求金额',
    `order_status_before` TINYINT   NOT NULL                COMMENT '申请时的订单状态(2待交付/3使用中)',
    `status`          TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待受理 1处理中 2已调解 3已关闭',
    `handler_id`      BIGINT        NULL     DEFAULT NULL   COMMENT '处理人ID(村级管理员)',
    `handle_result`   VARCHAR(1000) NULL     DEFAULT NULL   COMMENT '调解结果',
    `handle_time`     DATETIME      NULL     DEFAULT NULL   COMMENT '处理时间',
    `village_id`      BIGINT        NOT NULL                COMMENT '村组ID(数据级权限锚点)',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    `update_time`     DATETIME      NULL     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dispute_no` (`dispute_no`),
    KEY `idx_dispute_order` (`order_id`),
    KEY `idx_dispute_village` (`village_id`, `status`, `create_time`),
    KEY `idx_dispute_applicant` (`applicant_id`),
    CONSTRAINT `fk_dispute_order` FOREIGN KEY (`order_id`) REFERENCES `biz_order` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_dispute_applicant` FOREIGN KEY (`applicant_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_dispute_respondent` FOREIGN KEY (`respondent_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_dispute_handler` FOREIGN KEY (`handler_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_dispute_village` FOREIGN KEY (`village_id`) REFERENCES `sys_village` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='纠纷协调表';


-- ----------------------------------------------------------------------------
-- 18. biz_withdraw 提现申请表
--     这是全系统唯一涉及"余额扣减"的入口,Service 层必须 FOR UPDATE 锁行,
--     否则余额100、两个浏览器同时提100会双双通过校验,余额变 -100。
-- ----------------------------------------------------------------------------
CREATE TABLE `biz_withdraw` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `withdraw_no`   VARCHAR(32)   NOT NULL                COMMENT '提现单号',
    `user_id`       BIGINT        NOT NULL                COMMENT '申请人ID',
    `amount`        DECIMAL(10,2) NOT NULL                COMMENT '提现金额',
    `account_type`  TINYINT       NOT NULL                COMMENT '收款方式:1微信 2支付宝 3银行卡',
    `account_no`    VARCHAR(64)   NOT NULL                COMMENT '收款账号',
    `account_name`  VARCHAR(50)   NULL     DEFAULT NULL   COMMENT '收款人姓名',
    `status`        TINYINT       NOT NULL DEFAULT 0      COMMENT '状态:0待审核 1已打款 2已驳回',
    `audit_user_id` BIGINT        NULL     DEFAULT NULL   COMMENT '审核人ID',
    `audit_remark`  VARCHAR(255)  NULL     DEFAULT NULL   COMMENT '审核意见',
    `apply_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    `audit_time`    DATETIME      NULL     DEFAULT NULL   COMMENT '审核时间',
    `finish_time`   DATETIME      NULL     DEFAULT NULL   COMMENT '打款时间',
    `village_id`    BIGINT        NOT NULL                COMMENT '村组ID(数据级权限锚点)',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_withdraw_no` (`withdraw_no`),
    KEY `idx_wd_user_status` (`user_id`, `status`),
    KEY `idx_wd_village_status` (`village_id`, `status`),
    CONSTRAINT `fk_wd_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_wd_audit_user` FOREIGN KEY (`audit_user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_wd_village` FOREIGN KEY (`village_id`) REFERENCES `sys_village` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提现申请表';


-- ============================================================================
--  互动域(6 张)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 19. biz_announcement 公告表(政策宣传)
--     village_id 允许 NULL 表示"全平台公告":村级管理员发布时强制写自己的
--     village_id,超管发全局公告时为 NULL。前台查询条件:
--       WHERE village_id IS NULL OR village_id = #{myVillageId}
-- ----------------------------------------------------------------------------
CREATE TABLE `biz_announcement` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`        VARCHAR(200) NOT NULL                COMMENT '标题',
    `content`      LONGTEXT     NULL     DEFAULT NULL   COMMENT '正文(富文本)',
    `cover_url`    VARCHAR(255) NULL     DEFAULT NULL   COMMENT '封面图',
    `notice_type`  TINYINT      NOT NULL                COMMENT '类型:1村级共享倡议 2安全使用须知 3农机补贴政策 4节庆活动 5系统公告',
    `publisher_id` BIGINT       NOT NULL                COMMENT '发布人ID',
    `village_id`   BIGINT       NULL     DEFAULT NULL   COMMENT '村组ID,NULL=全局公告',
    `is_top`       TINYINT      NOT NULL DEFAULT 0      COMMENT '是否置顶:1是 0否',
    `view_count`   INT          NOT NULL DEFAULT 0      COMMENT '浏览量',
    `status`       TINYINT      NOT NULL DEFAULT 1      COMMENT '状态:0草稿 1已发布 2已下架',
    `publish_time` DATETIME     NULL     DEFAULT NULL   COMMENT '发布时间',
    `deleted`      TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除:0正常 1已删除',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     NULL     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_ann_village_status` (`village_id`, `status`, `publish_time`),
    KEY `idx_ann_type` (`notice_type`),
    CONSTRAINT `fk_ann_publisher` FOREIGN KEY (`publisher_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_ann_village` FOREIGN KEY (`village_id`) REFERENCES `sys_village` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公告表';


-- ----------------------------------------------------------------------------
-- 20. msg_conversation 站内信会话表
--
--    【必须遵守的约定】创建会话时统一 user_a_id = min(id1,id2)、
--    user_b_id = max(id1,id2)。否则 (1,2) 和 (2,1) 会被当成两个会话,
--    唯一约束形同虚设。
--
--    【已知设计权衡】MySQL 唯一索引不约束 NULL,所以当 resource_id IS NULL
--    (不针对具体资源的私聊)时 uk_conv 无法阻止重复会话,只能靠应用层
--    "先查后插"保证。"resource_id 用 0 占位"的方案行不通 —— 0 不存在于
--    biz_resource,会违反外键约束。这个取舍建议写进论文。
-- ----------------------------------------------------------------------------
CREATE TABLE `msg_conversation` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会话ID',
    `user_a_id`    BIGINT       NOT NULL                COMMENT '用户A(约定为较小的ID)',
    `user_b_id`    BIGINT       NOT NULL                COMMENT '用户B(约定为较大的ID)',
    `resource_id`  BIGINT       NULL     DEFAULT NULL   COMMENT '关联资源ID',
    `order_id`     BIGINT       NULL     DEFAULT NULL   COMMENT '关联订单ID',
    `last_message` VARCHAR(200) NULL     DEFAULT NULL   COMMENT '最后一条消息(冗余,列表展示用)',
    `last_time`    DATETIME     NULL     DEFAULT NULL   COMMENT '最后消息时间',
    `a_unread`     INT          NOT NULL DEFAULT 0      COMMENT '用户A未读数',
    `b_unread`     INT          NOT NULL DEFAULT 0      COMMENT '用户B未读数',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conv` (`user_a_id`, `user_b_id`, `resource_id`),
    KEY `idx_conv_a_time` (`user_a_id`, `last_time`),
    KEY `idx_conv_b_time` (`user_b_id`, `last_time`),
    CONSTRAINT `fk_conv_user_a` FOREIGN KEY (`user_a_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_conv_user_b` FOREIGN KEY (`user_b_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_conv_resource` FOREIGN KEY (`resource_id`) REFERENCES `biz_resource` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_conv_order` FOREIGN KEY (`order_id`) REFERENCES `biz_order` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内信会话表';


-- ----------------------------------------------------------------------------
-- 21. msg_chat_message 聊天消息表
--     后台处理纠纷要"调取聊天记录作为依据",所以消息不能物理删除,
--     命中敏感词也只能标记 is_filtered。
-- ----------------------------------------------------------------------------
CREATE TABLE `msg_chat_message` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` BIGINT        NOT NULL                COMMENT '会话ID',
    `from_user_id`    BIGINT        NOT NULL                COMMENT '发送人ID',
    `to_user_id`      BIGINT        NOT NULL                COMMENT '接收人ID',
    `content`         VARCHAR(1000) NOT NULL                COMMENT '消息内容',
    `msg_type`        TINYINT       NOT NULL DEFAULT 1      COMMENT '消息类型:1文本 2图片',
    `is_read`         TINYINT       NOT NULL DEFAULT 0      COMMENT '是否已读:1是 0否',
    `is_filtered`     TINYINT       NOT NULL DEFAULT 0      COMMENT '是否命中敏感词:1是 0否',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    PRIMARY KEY (`id`),
    KEY `idx_chat_conv_time` (`conversation_id`, `create_time`),
    KEY `idx_chat_to_read` (`to_user_id`, `is_read`),
    CONSTRAINT `fk_chat_conv` FOREIGN KEY (`conversation_id`) REFERENCES `msg_conversation` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_chat_from` FOREIGN KEY (`from_user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_chat_to` FOREIGN KEY (`to_user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='聊天消息表';


-- ----------------------------------------------------------------------------
-- 22. msg_notice 系统通知表
--     "到期提醒"由定时任务每天扫描 order_status=3 且 rent_end_date 为明天的
--     租赁单批量插入,演示"系统会自动提醒"很有说服力。
-- ----------------------------------------------------------------------------
CREATE TABLE `msg_notice` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT        NOT NULL                COMMENT '接收人ID',
    `notice_type` TINYINT       NOT NULL                COMMENT '类型:1实名审核 2资源审核 3订单状态 4到期提醒 5押金退还 6系统公告 7纠纷处理 8信用变动 9提现 10留言互动 11外来用户备案',
    `title`       VARCHAR(200)  NOT NULL                COMMENT '标题',
    `content`     VARCHAR(1000) NULL     DEFAULT NULL   COMMENT '内容',
    `biz_type`    TINYINT       NULL     DEFAULT NULL   COMMENT '业务类型(点击跳转用)',
    `biz_id`      BIGINT        NULL     DEFAULT NULL   COMMENT '业务ID(点击跳转用)',
    `is_read`     TINYINT       NOT NULL DEFAULT 0      COMMENT '是否已读:1是 0否',
    `read_time`   DATETIME      NULL     DEFAULT NULL   COMMENT '阅读时间',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_notice_user_read` (`user_id`, `is_read`, `create_time`),
    KEY `idx_notice_user_type` (`user_id`, `notice_type`),
    CONSTRAINT `fk_notice_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统通知表';


-- ----------------------------------------------------------------------------
-- 23. biz_board 留言板主表(含回复)
--     需求原文:"留言板功能,用户可对其他用户进行评价留言。支持留言点赞和回复功能。"
--
--     回复用 parent_id 自关联指向主帖,不再拆 biz_board_reply 表 ——
--     回复和主帖的字段完全同构,拆表只增加 JOIN。
--
--     注意:本表与 biz_evaluation 是两个不同的东西 ——
--     评价挂在订单上、有评分维度;留言板是公共讨论区、无订单绑定。
-- ----------------------------------------------------------------------------
CREATE TABLE `biz_board` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `parent_id`   BIGINT        NULL     DEFAULT NULL   COMMENT '父留言ID,NULL=主帖,非空=回复',
    `user_id`     BIGINT        NOT NULL                COMMENT '留言人ID',
    `village_id`  BIGINT        NOT NULL                COMMENT '村组ID(数据级权限锚点)',
    `content`     VARCHAR(1000) NOT NULL                COMMENT '留言内容',
    `images`      VARCHAR(1000) NULL     DEFAULT NULL   COMMENT '图片,逗号分隔',
    `like_count`  INT           NOT NULL DEFAULT 0      COMMENT '点赞数(冗余计数)',
    `reply_count` INT           NOT NULL DEFAULT 0      COMMENT '回复数(冗余计数)',
    `is_filtered` TINYINT       NOT NULL DEFAULT 0      COMMENT '是否命中敏感词:1是 0否',
    `status`      TINYINT       NOT NULL DEFAULT 1      COMMENT '状态:1正常 2已隐藏(违规)',
    `deleted`     TINYINT       NOT NULL DEFAULT 0      COMMENT '逻辑删除:0正常 1已删除',
    `create_time` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '留言时间',
    `update_time` DATETIME      NULL     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    -- 主帖列表:WHERE parent_id IS NULL ORDER BY create_time DESC
    KEY `idx_board_parent_time` (`parent_id`, `create_time`),
    KEY `idx_board_village` (`village_id`, `status`, `create_time`),
    KEY `idx_board_user` (`user_id`),
    CONSTRAINT `fk_board_parent` FOREIGN KEY (`parent_id`) REFERENCES `biz_board` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_board_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_board_village` FOREIGN KEY (`village_id`) REFERENCES `sys_village` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='留言板表(含回复)';


-- ----------------------------------------------------------------------------
-- 24. biz_board_like 留言点赞表
--     用唯一约束而非 Service 层的"先查再插"来防重复点赞 —— 更可靠。
-- ----------------------------------------------------------------------------
CREATE TABLE `biz_board_like` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `board_id`    BIGINT   NOT NULL                COMMENT '留言ID',
    `user_id`     BIGINT   NOT NULL                COMMENT '点赞人ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_board_user` (`board_id`, `user_id`),
    KEY `idx_like_user` (`user_id`),
    CONSTRAINT `fk_like_board` FOREIGN KEY (`board_id`) REFERENCES `biz_board` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_like_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='留言点赞表';


-- ============================================================================
--  AI 域(2 张)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 25. ai_chat_record AI 对话记录表
--     对应验收要求"集成 AI 接口时需做好接口封装、异步调用与异常处理"的落地载体。
--     status/degrade_reason 是 AI 专项测试(AI-02/AI-03)的直接证据。
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_chat_record` (
    `id`                BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`        VARCHAR(64)   NOT NULL                COMMENT '会话ID(前端UUID,多轮上下文按它聚合)',
    `user_id`           BIGINT        NOT NULL                COMMENT '提问用户ID',
    `village_id`        BIGINT        NULL     DEFAULT NULL   COMMENT '用户所属村组ID',
    `question`          VARCHAR(1000) NOT NULL                COMMENT '用户提问',
    `answer`            TEXT          NULL     DEFAULT NULL   COMMENT 'AI回答',
    `model`             VARCHAR(30)   NOT NULL DEFAULT 'deepseek-flash' COMMENT '使用的模型',
    `prompt_tokens`     INT           NOT NULL DEFAULT 0      COMMENT '提示token数',
    `completion_tokens` INT           NOT NULL DEFAULT 0      COMMENT '生成token数',
    `cost_time`         BIGINT        NOT NULL DEFAULT 0      COMMENT '耗时(毫秒)',
    `status`            TINYINT       NOT NULL DEFAULT 1      COMMENT '状态:1成功 2降级(本地兜底) 3失败',
    `degrade_reason`    VARCHAR(100)  NULL     DEFAULT NULL   COMMENT '降级原因:超时/限流429/余额不足402/网络异常',
    `is_resolved`       TINYINT       NOT NULL DEFAULT 0      COMMENT '用户是否点"已解决":1是 0否',
    `is_transfer`       TINYINT       NOT NULL DEFAULT 0      COMMENT '是否转人工:1是 0否',
    `create_time`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提问时间',
    PRIMARY KEY (`id`),
    KEY `idx_aichat_session` (`session_id`, `create_time`),
    KEY `idx_aichat_status` (`status`),
    KEY `idx_aichat_user_time` (`user_id`, `create_time`),
    KEY `idx_aichat_village` (`village_id`, `create_time`),
    CONSTRAINT `fk_aichat_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_aichat_village` FOREIGN KEY (`village_id`) REFERENCES `sys_village` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI对话记录表';


-- ----------------------------------------------------------------------------
-- 26. ai_user_behavior 用户行为记录表(智能推荐的数据源)
--
--     source 字段存"是否来自推荐位"很关键:没有它就无法评估推荐效果,
--     论文里也就画不出"有无推荐的点击率对比"。
--
--     本表同时是内容推荐的主数据源(idx_user_category),
--     协同过滤(共现统计)也直接在这张表上做,不需要额外建表。
-- ----------------------------------------------------------------------------
CREATE TABLE `ai_user_behavior` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`       BIGINT       NOT NULL                COMMENT '用户ID',
    `village_id`    BIGINT       NULL     DEFAULT NULL   COMMENT '用户所属村组ID',
    `behavior_type` TINYINT      NOT NULL                COMMENT '行为类型:1浏览 2搜索 3收藏 4下单 5评价 6推荐位曝光',
    `resource_id`   BIGINT       NULL     DEFAULT NULL   COMMENT '目标资源ID(搜索行为为NULL)',
    `category_id`   BIGINT       NULL     DEFAULT NULL   COMMENT '冗余分类ID(推荐计算免JOIN)',
    `keyword`       VARCHAR(100) NULL     DEFAULT NULL   COMMENT '搜索关键词',
    `stay_seconds`  INT          NOT NULL DEFAULT 0      COMMENT '停留时长(秒,浏览行为的权重信号)',
    `source`        TINYINT      NOT NULL DEFAULT 1      COMMENT '来源:1列表页 2详情页 3搜索 4推荐位',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '行为时间',
    PRIMARY KEY (`id`),
    -- 取用户最近 N 条行为做实时推荐
    KEY `idx_beh_user_time` (`user_id`, `create_time`),
    -- 基于内容的推荐主查询:"该用户最近浏览/搜索最多的分类是哪些"
    KEY `idx_beh_user_category` (`user_id`, `category_id`),
    KEY `idx_beh_resource` (`resource_id`),
    KEY `idx_beh_type_time` (`behavior_type`, `create_time`),
    CONSTRAINT `fk_beh_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_beh_village` FOREIGN KEY (`village_id`) REFERENCES `sys_village` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_beh_resource` FOREIGN KEY (`resource_id`) REFERENCES `biz_resource` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_beh_category` FOREIGN KEY (`category_id`) REFERENCES `biz_resource_category` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户行为记录表(推荐数据源)';
