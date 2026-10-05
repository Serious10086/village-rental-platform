-- ============================================================================
--  乡村闲置资源租售平台 —— 建表脚本(一):用户与权限域 + 系统域
--  共 9 张表:sys_village / sys_role / sys_user / sys_user_role / sys_menu
--             sys_role_menu / sys_audit_record / sys_operation_log / sys_sensitive_word
--
--  【重要】本脚本会 DROP 并重建这 9 张表,已有数据会丢失。
--          仅用于首次建库或开发期重置,生产环境不要直接执行。
--
--  约定:
--    · 引擎 InnoDB,字符集 utf8mb4,排序规则 utf8mb4_0900_ai_ci
--    · 主键统一 BIGINT AUTO_INCREMENT
--    · 时间字段统一 create_time / update_time,自动填充
--    · 外键统一 ON DELETE RESTRICT ON UPDATE CASCADE
--      (业务数据永不级联删除 —— 订单和评价是纠纷处理的依据)
--    · 需软删的表带 deleted 字段,对应 MyBatis-Plus 的 @TableLogic
--    · 表名全小写(本机 lower_case_table_names=1)
--
--  执行顺序:本脚本 → 02_schema_biz.sql → 03_view.sql → 04_seed.sql
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS `village_rental`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `village_rental`;

DROP TABLE IF EXISTS `sys_role_menu`;
DROP TABLE IF EXISTS `sys_user_role`;
DROP TABLE IF EXISTS `sys_audit_record`;
DROP TABLE IF EXISTS `sys_operation_log`;
DROP TABLE IF EXISTS `sys_sensitive_word`;
DROP TABLE IF EXISTS `sys_menu`;
DROP TABLE IF EXISTS `sys_user`;
DROP TABLE IF EXISTS `sys_role`;
DROP TABLE IF EXISTS `sys_village`;

SET FOREIGN_KEY_CHECKS = 1;


-- ----------------------------------------------------------------------------
-- 1. sys_village 村组表
--    整张表存在的唯一理由:让"村级管理员只看本村数据"有一个可 JOIN 的实体,
--    它是全系统数据级权限的物理锚点(village_id 会出现在 9 张业务表里)。
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_village` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT           COMMENT '村组ID',
    `parent_id`    BIGINT       NULL     DEFAULT NULL            COMMENT '上级行政区ID,NULL=顶级乡镇',
    `village_name` VARCHAR(50)  NOT NULL                         COMMENT '村组名称,如"战旗村"',
    `village_code` VARCHAR(20)  NOT NULL                         COMMENT '村组编码,如 CD-PD-ZQ',
    `town_name`    VARCHAR(50)  NULL     DEFAULT NULL            COMMENT '所属乡镇名称',
    `region_code`  VARCHAR(12)  NULL     DEFAULT NULL            COMMENT '行政区划代码',
    `sort_order`   INT          NOT NULL DEFAULT 0               COMMENT '排序号',
    `status`       TINYINT      NOT NULL DEFAULT 1               COMMENT '状态:1启用 0停用',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`  DATETIME     NULL     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_village_code` (`village_code`),
    KEY `idx_parent_id` (`parent_id`),
    CONSTRAINT `fk_village_parent` FOREIGN KEY (`parent_id`) REFERENCES `sys_village` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村组表(数据级权限锚点)';

-- 注意:parent_id 用 NULL 表示顶级,不能用 0 —— 0 不存在于本表,会违反外键约束


-- ----------------------------------------------------------------------------
-- 2. sys_role 角色表
--    data_scope 挂在角色上而非用户上:数据范围是角色的定义属性
--    ("村级管理员"这个角色就等于"只能看本村"),不是个人属性。
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_role` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT           COMMENT '角色ID',
    `role_code`   VARCHAR(30)  NOT NULL                          COMMENT '角色编码:SUPER_ADMIN/TOWN_ADMIN/VILLAGE_ADMIN/VILLAGER/BUYER',
    `role_name`   VARCHAR(50)  NOT NULL                          COMMENT '角色名称',
    `data_scope`  TINYINT      NOT NULL DEFAULT 4                COMMENT '数据范围:1全部 2本镇 3本村 4仅本人',
    `sort_order`  INT          NOT NULL DEFAULT 0                COMMENT '排序号',
    `status`      TINYINT      NOT NULL DEFAULT 1                COMMENT '状态:1启用 0停用',
    `remark`      VARCHAR(255) NULL     DEFAULT NULL             COMMENT '备注',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NULL     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表';


-- ----------------------------------------------------------------------------
-- 3. sys_user 用户表
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_user` (
    `id`              BIGINT        NOT NULL AUTO_INCREMENT        COMMENT '用户ID',
    `phone`           VARCHAR(11)   NOT NULL                       COMMENT '手机号(登录账号)',
    `password`        VARCHAR(100)  NOT NULL                       COMMENT '密码(BCrypt密文)',
    `user_type`       TINYINT       NOT NULL                       COMMENT '用户类型:1村民 2租户/买家 3管理员',
    `real_name`       VARCHAR(20)   NULL     DEFAULT NULL          COMMENT '真实姓名',
    `id_card`         VARCHAR(18)   NULL     DEFAULT NULL          COMMENT '身份证号',
    `id_card_front`   VARCHAR(255)  NULL     DEFAULT NULL          COMMENT '身份证正面照URL',
    `id_card_back`    VARCHAR(255)  NULL     DEFAULT NULL          COMMENT '身份证反面照URL',
    `village_id`      BIGINT        NULL     DEFAULT NULL          COMMENT '所属村组ID',
    `address`         VARCHAR(200)  NULL     DEFAULT NULL          COMMENT '家庭住址',
    `avatar`          VARCHAR(255)  NOT NULL DEFAULT ''            COMMENT '头像URL',
    `credit_score`    INT           NOT NULL DEFAULT 100           COMMENT '信用分,初始100',
    `realname_status` TINYINT       NOT NULL DEFAULT 0             COMMENT '实名状态:0未提交 1待审核 2已认证 3已驳回',
    `account_status`  TINYINT       NOT NULL DEFAULT 1             COMMENT '账号状态:0禁用 1正常 2封禁',
    `balance`         DECIMAL(12,2) NOT NULL DEFAULT 0.00          COMMENT '账户余额(提现用)',
    `is_outside`      TINYINT       NOT NULL DEFAULT 0             COMMENT '是否外来用户:1是(需村级管理员备案) 0否',
    `last_login_time` DATETIME      NULL     DEFAULT NULL          COMMENT '最后登录时间',
    `deleted`         TINYINT       NOT NULL DEFAULT 0             COMMENT '逻辑删除:0正常 1已删除',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME      NULL     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`),
    -- 唯一索引容许 NULL,所以未实名(未填身份证)的用户不会互相冲突
    UNIQUE KEY `uk_id_card` (`id_card`),
    -- 村级管理员查"本村待审核实名列表"专用:最左前缀命中数据级过滤
    KEY `idx_village_audit` (`village_id`, `realname_status`),
    KEY `idx_credit_score` (`credit_score`),
    CONSTRAINT `fk_user_village` FOREIGN KEY (`village_id`) REFERENCES `sys_village` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';


-- ----------------------------------------------------------------------------
-- 4. sys_user_role 用户角色关联表
--    用多对多而非在 sys_user 上加 role_id:因为村长自己也有农机要出租,
--    他同时是 VILLAGE_ADMIN 和 VILLAGER,一个字段装不下。
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_user_role` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT   NOT NULL                COMMENT '用户ID',
    `role_id`     BIGINT   NOT NULL                COMMENT '角色ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_sur_role_id` (`role_id`),
    CONSTRAINT `fk_sur_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_sur_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关联表';


-- ----------------------------------------------------------------------------
-- 5. sys_menu 菜单权限表(菜单 + 按钮统一一张表)
--    menu_type 区分:M目录 / C菜单 / F按钮。按钮天然挂在某个菜单下,
--    用 parent_id 表达从属关系比额外建关联表更自然。
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_menu` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
    `parent_id`   BIGINT       NULL     DEFAULT NULL   COMMENT '父节点ID,NULL=顶级',
    `menu_name`   VARCHAR(50)  NOT NULL                COMMENT '菜单/按钮名称',
    `menu_type`   CHAR(1)      NOT NULL                COMMENT '类型:M目录 C菜单 F按钮',
    `perms`       VARCHAR(100) NULL     DEFAULT NULL   COMMENT '权限标识,如 resource:audit',
    `path`        VARCHAR(200) NULL     DEFAULT NULL   COMMENT '前端路由路径',
    `component`   VARCHAR(200) NULL     DEFAULT NULL   COMMENT '前端组件路径',
    `icon`        VARCHAR(50)  NULL     DEFAULT NULL   COMMENT '图标',
    `sort_order`  INT          NOT NULL DEFAULT 0      COMMENT '排序号',
    `visible`     TINYINT      NOT NULL DEFAULT 1      COMMENT '是否显示:1显示 0隐藏',
    `status`      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态:1启用 0停用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NULL     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_menu_parent_id` (`parent_id`),
    -- 后端 @RequiresPerm 每次请求都按权限码校验,不建索引就是每请求全表扫描
    KEY `idx_menu_perms` (`perms`),
    CONSTRAINT `fk_menu_parent` FOREIGN KEY (`parent_id`) REFERENCES `sys_menu` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单权限表(含按钮级)';


-- ----------------------------------------------------------------------------
-- 6. sys_role_menu 角色菜单关联表
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_role_menu` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `role_id`     BIGINT   NOT NULL                COMMENT '角色ID',
    `menu_id`     BIGINT   NOT NULL                COMMENT '菜单ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`),
    KEY `idx_srm_menu_id` (`menu_id`),
    CONSTRAINT `fk_srm_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_srm_menu` FOREIGN KEY (`menu_id`) REFERENCES `sys_menu` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色菜单关联表';


-- ----------------------------------------------------------------------------
-- 7. sys_audit_record 审核流水表
--    实名认证、资源发布、外来用户备案、提现四条审核流共用一张表。
--
--    【设计权衡】biz_id 是多态关联,无法建物理外键 —— 这是用一张表覆盖
--    四条同构审核流的代价。四条流的字段完全一致,拆成 4 张表会有 90% 重复。
--    为保证主体完整性,applicant_id / village_id / auditor_id 三个仍建物理外键。
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_audit_record` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `biz_type`     TINYINT      NOT NULL                COMMENT '业务类型:1实名认证 2资源发布 3外来用户备案 4提现',
    `biz_id`       BIGINT       NOT NULL                COMMENT '业务主键(多态,逻辑关联)',
    `applicant_id` BIGINT       NOT NULL                COMMENT '申请人ID',
    `village_id`   BIGINT       NOT NULL                COMMENT '归属村组ID(数据级权限锚点)',
    `auditor_id`   BIGINT       NULL     DEFAULT NULL   COMMENT '审核人ID',
    `audit_status` TINYINT      NOT NULL DEFAULT 0      COMMENT '审核状态:0待审核 1通过 2驳回',
    `audit_remark` VARCHAR(500) NULL     DEFAULT NULL   COMMENT '审核意见',
    `submit_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
    `audit_time`   DATETIME     NULL     DEFAULT NULL   COMMENT '审核时间',
    PRIMARY KEY (`id`),
    -- 后台"审核待办"列表的唯一查询入口
    KEY `idx_audit_todo` (`village_id`, `audit_status`, `submit_time`),
    KEY `idx_audit_biz` (`biz_type`, `biz_id`),
    KEY `idx_audit_applicant` (`applicant_id`),
    CONSTRAINT `fk_audit_applicant` FOREIGN KEY (`applicant_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_audit_village` FOREIGN KEY (`village_id`) REFERENCES `sys_village` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_audit_auditor` FOREIGN KEY (`auditor_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通用审核流水表';


-- ----------------------------------------------------------------------------
-- 8. sys_operation_log 操作日志表
--    由 OperationLogAspect 切 @Log 注解自动写入,不侵入业务代码。
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_operation_log` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NULL     DEFAULT NULL   COMMENT '操作人ID',
    `username`    VARCHAR(50)  NULL     DEFAULT NULL   COMMENT '操作人用户名(冗余,便于改名后日志仍可读)',
    `module`      VARCHAR(50)  NULL     DEFAULT NULL   COMMENT '模块名',
    `operation`   VARCHAR(100) NOT NULL                COMMENT '操作描述',
    `method`      VARCHAR(255) NULL     DEFAULT NULL   COMMENT '类方法名',
    `request_url` VARCHAR(255) NULL     DEFAULT NULL   COMMENT '请求URL',
    `params`      TEXT         NULL     DEFAULT NULL   COMMENT '请求参数(密码字段需脱敏)',
    `ip`          VARCHAR(50)  NULL     DEFAULT NULL   COMMENT '操作IP',
    `status`      TINYINT      NOT NULL DEFAULT 1      COMMENT '状态:1成功 0失败',
    `error_msg`   TEXT         NULL     DEFAULT NULL   COMMENT '异常信息',
    `cost_time`   BIGINT       NOT NULL DEFAULT 0      COMMENT '耗时(毫秒)',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (`id`),
    KEY `idx_olog_user_time` (`user_id`, `create_time`),
    KEY `idx_olog_create_time` (`create_time`),
    CONSTRAINT `fk_olog_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作日志表';


-- ----------------------------------------------------------------------------
-- 9. sys_sensitive_word 敏感词表
--    落地方案:启动时 @PostConstruct 全量加载进内存 Trie(DFA),过滤是 O(n) 字符扫描。
--    不要写成 for(word : allWords) content.contains(word) ——
--    1000 个词 × 1000 字留言 = 100 万次匹配,性能不可接受。
-- ----------------------------------------------------------------------------
CREATE TABLE `sys_sensitive_word` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `word`        VARCHAR(50) NOT NULL                COMMENT '敏感词',
    `category`    VARCHAR(20) NOT NULL DEFAULT '广告' COMMENT '分类:政治/辱骂/广告/违法',
    `action`      TINYINT     NOT NULL DEFAULT 1      COMMENT '处理方式:1替换为* 2直接拦截',
    `status`      TINYINT     NOT NULL DEFAULT 1      COMMENT '状态:1启用 0停用',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sensitive_word` (`word`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='敏感词表';
