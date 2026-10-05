-- ============================================================================
--  乡村闲置资源租售平台 —— 初始化数据(种子数据)
--
--  前置:先执行 01_schema_sys.sql、02_schema_biz.sql、03_view.sql
--
--  【重要】本脚本会清空并重建初始化数据。开发期可反复执行,生产不要跑。
--
--  说明:
--   · 所有测试账号密码统一为 123456(BCrypt 加密存储)
--   · BCrypt 哈希用 Python bcrypt 生成并校验通过
--     Spring Security 的 BCryptPasswordEncoder 支持 $2a$/$2b$/$2y$ 三种前缀
--   · 菜单树 ID 手工指定(不用自增),这样角色授权可以精确写死
-- ============================================================================

SET NAMES utf8mb4;
USE `village_rental`;

-- 清空(按外键依赖倒序)
DELETE FROM `sys_role_menu`;
DELETE FROM `sys_user_role`;
DELETE FROM `sys_sensitive_word`;
DELETE FROM `biz_resource_category`;
DELETE FROM `sys_menu`;
DELETE FROM `sys_user`;
DELETE FROM `sys_role`;
DELETE FROM `sys_village`;


-- ============================================================================
--  1. 村组(两级:乡镇 → 村)
-- ============================================================================
INSERT INTO `sys_village` (`id`, `parent_id`, `village_name`, `village_code`, `town_name`, `region_code`, `sort_order`, `status`) VALUES
(1, NULL, '唐昌镇',  'CD-PD-TC',     '唐昌镇', '510124', 1, 1),
(2, 1,    '战旗村',  'CD-PD-TC-ZQ',  '唐昌镇', '510124', 2, 1),
(3, 1,    '先锋村',  'CD-PD-TC-XF',  '唐昌镇', '510124', 3, 1),
(4, 1,    '金星村',  'CD-PD-TC-JX',  '唐昌镇', '510124', 4, 1);


-- ============================================================================
--  2. 角色
--     data_scope: 1全部 2本镇 3本村 4仅本人
-- ============================================================================
INSERT INTO `sys_role` (`id`, `role_code`, `role_name`, `data_scope`, `sort_order`, `remark`) VALUES
(1, 'SUPER_ADMIN',   '超级管理员',   1, 1, '平台级管理员,可管理全部村组数据与系统配置'),
(2, 'VILLAGE_ADMIN', '村级管理员',   3, 2, '只能管理本村数据(数据级权限演示的核心角色)'),
(3, 'VILLAGER',      '资源发布者',   4, 3, '村民,发布闲置资源、处理订单、查看收益'),
(4, 'BUYER',         '资源使用者',   4, 4, '租户/买家,浏览搜索、下单、评价');


-- ============================================================================
--  3. 用户
--     密码统一 123456
-- ============================================================================
INSERT INTO `sys_user`
(`id`, `phone`, `password`, `user_type`, `real_name`, `id_card`, `village_id`, `address`,
 `credit_score`, `realname_status`, `account_status`, `balance`, `is_outside`) VALUES
-- 管理员
(1, '13800000000', '$2b$10$43ktMbSdOo9WBasiMIyWReXCcYTMbcqQlMvsPDJJZXHcRydHgUwDa', 3, '平台管理员', NULL, 1,                '唐昌镇政府',        100, 2, 1, 0.00,   0),
(2, '13800000001', '$2b$10$43ktMbSdOo9WBasiMIyWReXCcYTMbcqQlMvsPDJJZXHcRydHgUwDa', 3, '战旗村支书', '510124199001011234', 2, '战旗村村委会',      100, 2, 1, 0.00,   0),
(3, '13800000002', '$2b$10$43ktMbSdOo9WBasiMIyWReXCcYTMbcqQlMvsPDJJZXHcRydHgUwDa', 3, '先锋村主任', '510124199002021234', 3, '先锋村村委会',      100, 2, 1, 0.00,   0),
-- 村民(资源发布者)
--
-- 【余额与信用分统一从 0 / 100 起步】
-- 这两个值不再在这里"编"一个数,而是由 05_mock_data.sql 按真实订单算出来。
-- 原先给李秀兰写了 98 分、张建国写了 1280 元余额 —— 但没有任何流水能解释
-- 这两个数字从哪来,演示时被问"这 98 分怎么扣的"就答不上。
-- 演示数据的每一处派生状态,都应该能追溯到某条业务记录。
(4, '13800000003', '$2b$10$43ktMbSdOo9WBasiMIyWReXCcYTMbcqQlMvsPDJJZXHcRydHgUwDa', 1, '张建国',   '510124197005051234', 2, '战旗村3组12号',     100, 2, 1, 0.00,    0),
(5, '13800000004', '$2b$10$43ktMbSdOo9WBasiMIyWReXCcYTMbcqQlMvsPDJJZXHcRydHgUwDa', 1, '李秀兰',   '510124197508081234', 2, '战旗村5组7号',       100, 2, 1, 0.00,    0),
(6, '13800000007', '$2b$10$43ktMbSdOo9WBasiMIyWReXCcYTMbcqQlMvsPDJJZXHcRydHgUwDa', 1, '王大山',   '510124196803031234', 4, '金星村2组31号',     100, 2, 1, 0.00,    0),
-- 待审核实名(用于演示村级管理员的审核待办)
(7, '13800000009', '$2b$10$43ktMbSdOo9WBasiMIyWReXCcYTMbcqQlMvsPDJJZXHcRydHgUwDa', 1, '赵春梅',   '510124198012121234', 2, '战旗村7组4号',      100, 1, 1, 0.00,    0),
-- 买家(资源使用者)
(8, '13800000005', '$2b$10$43ktMbSdOo9WBasiMIyWReXCcYTMbcqQlMvsPDJJZXHcRydHgUwDa', 2, '刘小明',   '510124199505051234', 2, '战旗村1组9号',      100, 2, 1, 0.00,    0),
(9, '13800000006', '$2b$10$43ktMbSdOo9WBasiMIyWReXCcYTMbcqQlMvsPDJJZXHcRydHgUwDa', 2, '陈晓芳',   '510124199606061234', 3, '先锋村4组2号',      100, 2, 1, 0.00,    0),
-- 外来用户(非本村,用于演示"外来用户备案")
-- 周伟:备案已通过 —— is_outside 仍为 1,归属村组已经写上(先锋村)
(10, '13800000010', '$2b$10$43ktMbSdOo9WBasiMIyWReXCcYTMbcqQlMvsPDJJZXHcRydHgUwDa', 2, '周伟',    '510105199303031234', 3, '成都市青羊区',       100, 2, 1, 0.00,    1),
-- 孙志强:已建号但备案未通过 —— village_id 为 NULL 才是"外来且还没落户"的样子,
-- 他的那条待审核备案单在 05_mock_data.sql 里。少了这一行,备案页进去是空的
(11, '13800000011', '$2b$10$43ktMbSdOo9WBasiMIyWReXCcYTMbcqQlMvsPDJJZXHcRydHgUwDa', 2, '孙志强',  NULL,                 NULL, '成都市锦江区',      100, 0, 1, 0.00,    1);


-- ============================================================================
--  4. 用户角色关联
--     注意用户 4(张建国)同时是 VILLAGER —— 他既是村民也可能当买家
-- ============================================================================
INSERT INTO `sys_user_role` (`user_id`, `role_id`) VALUES
(1, 1),   -- 平台管理员  → SUPER_ADMIN
(2, 2),   -- 战旗村支书  → VILLAGE_ADMIN
(2, 3),   -- 战旗村支书  → VILLAGER(村长自己也有农机要出租,多角色)
(3, 2),   -- 先锋村主任  → VILLAGE_ADMIN
(4, 3),   -- 张建国      → VILLAGER
(4, 4),   -- 张建国      → BUYER(他也会买别人的东西)
(5, 3),   -- 李秀兰      → VILLAGER
(6, 3),   -- 王大山      → VILLAGER
(7, 3),   -- 赵春梅      → VILLAGER(待实名审核)
(8, 4),   -- 刘小明      → BUYER
(9, 4),   -- 陈晓芳      → BUYER
(10, 4),  -- 周伟        → BUYER(外来用户,备案已通过)
(11, 4);  -- 孙志强      → BUYER(外来用户,备案待审核)


-- ============================================================================
--  5. 菜单(含按钮级权限)
--     menu_type: M目录 / C菜单 / F按钮
--     ID 手工指定 —— 这样下面的角色授权可以精确写死
-- ============================================================================
INSERT INTO `sys_menu` (`id`, `parent_id`, `menu_name`, `menu_type`, `perms`, `path`, `component`, `icon`, `sort_order`, `visible`, `status`) VALUES
-- 工作台
(1,    NULL, '工作台',       'C', 'dashboard:view',      '/admin/dashboard',      'dashboard/index',      'HomeFilled',  1, 1, 1),

-- 审核管理
(10,   NULL, '审核管理',     'M', NULL,                  '/admin/audit',          NULL,                   'DocumentChecked', 2, 1, 1),
(101,  10,   '实名认证审核', 'C', 'user:realname:audit', '/admin/audit/realname', 'audit/realname/index', 'UserFilled',  1, 1, 1),
(1011, 101,  '审核通过/驳回','F', 'user:realname:audit', NULL,                    NULL,                   NULL,          1, 1, 1),
(102,  10,   '资源审核',     'C', 'resource:audit',      '/admin/audit/resource', 'audit/resource/index', 'Goods',       2, 1, 1),
(1021, 102,  '审核通过/驳回','F', 'resource:audit',      NULL,                    NULL,                   NULL,          1, 1, 1),
(1022, 102,  '标记安全隐患', 'F', 'resource:mark',       NULL,                    NULL,                   NULL,          2, 1, 1),

-- 资源监管
(20,   NULL, '资源监管',     'M', NULL,                  '/admin/resource',       NULL,                   'Files',       3, 1, 1),
(201,  20,   '资源管理',     'C', 'resource:list',       '/admin/resource/list',  'resource/index',       'List',        1, 1, 1),
(2011, 201,  '违规下架',     'F', 'resource:offline',    NULL,                    NULL,                   NULL,          1, 1, 1),

-- 用户管理
(30,   NULL, '用户管理',     'M', NULL,                  '/admin/user',           NULL,                   'User',        4, 1, 1),
(301,  30,   '用户列表',     'C', 'user:list',           '/admin/user/list',      'user/index',           'UserFilled',  1, 1, 1),
(3011, 301,  '封禁/解封',    'F', 'user:ban',            NULL,                    NULL,                   NULL,          1, 1, 1),
(302,  30,   '外来用户备案', 'C', 'user:outside',        '/admin/user/outside',   'user/outside',         'Link',        2, 1, 1),

-- 交易管理
(40,   NULL, '交易管理',     'M', NULL,                  '/admin/trade',          NULL,                   'ShoppingCart', 5, 1, 1),
(401,  40,   '订单管理',     'C', 'order:list',          '/admin/trade/order',    'order/index',          'Tickets',     1, 1, 1),
(4011, 401,  '查看订单详情', 'F', 'order:detail',        NULL,                    NULL,                   NULL,          1, 1, 1),
(4012, 401,  '强制取消订单', 'F', 'order:cancel',        NULL,                    NULL,                   NULL,          2, 1, 1),
(402,  40,   '纠纷处理',     'C', 'dispute:handle',      '/admin/trade/dispute',  'dispute/index',        'Warning',     2, 1, 1),
(4021, 402,  '调解处理',     'F', 'dispute:handle',      NULL,                    NULL,                   NULL,          1, 1, 1),
(403,  40,   '提现审核',     'C', 'withdraw:audit',      '/admin/trade/withdraw', 'withdraw/index',       'Money',       3, 1, 1),
(4031, 403,  '打款/驳回',    'F', 'withdraw:audit',      NULL,                    NULL,                   NULL,          1, 1, 1),

-- 信用管理
(50,   NULL, '信用管理',     'M', NULL,                  '/admin/credit',         NULL,                   'Medal',       6, 1, 1),
(501,  50,   '信用列表',     'C', 'credit:list',         '/admin/credit/list',    'credit/index',         'DataLine',    1, 1, 1),
(5011, 501,  '手动加减分',   'F', 'credit:adjust',       NULL,                    NULL,                   NULL,          1, 1, 1),

-- 内容管理
(60,   NULL, '内容管理',     'M', NULL,                  '/admin/content',        NULL,                   'ChatDotSquare', 7, 1, 1),
(601,  60,   '公告管理',     'C', 'announcement:list',   '/admin/content/notice', 'announcement/index',   'Bell',        1, 1, 1),
(6011, 601,  '发布公告',     'F', 'announcement:add',    NULL,                    NULL,                   NULL,          1, 1, 1),
(6012, 601,  '编辑公告',     'F', 'announcement:edit',   NULL,                    NULL,                   NULL,          2, 1, 1),
(6013, 601,  '下架公告',     'F', 'announcement:offline',NULL,                    NULL,                   NULL,          3, 1, 1),
(602,  60,   '留言管理',     'C', 'board:list',          '/admin/content/board',  'board/index',          'Comment',     2, 1, 1),
(6021, 602,  '删除留言',     'F', 'board:delete',        NULL,                    NULL,                   NULL,          1, 1, 1),
(603,  60,   '评价管理',     'C', 'evaluation:list',     '/admin/content/evaluation', 'evaluation/index', 'Star',        3, 1, 1),
(6031, 603,  '隐藏违规评价', 'F', 'evaluation:hide',     NULL,                    NULL,                   NULL,          1, 1, 1),

-- 数据统计
(70,   NULL, '数据统计',     'M', NULL,                  '/admin/stat',           NULL,                   'TrendCharts', 8, 1, 1),
(701,  70,   '村级统计',     'C', 'stat:village',        '/admin/stat/village',   'stat/village',         'PieChart',    1, 1, 1),
(702,  70,   '全局统计',     'C', 'stat:global',         '/admin/stat/global',    'stat/global',          'Histogram',   2, 1, 1),

-- AI 管理
(80,   NULL, 'AI 管理',      'M', NULL,                  '/admin/ai',             NULL,                   'MagicStick',  9, 1, 1),
(801,  80,   'AI会话记录',   'C', 'ai:record:list',      '/admin/ai/record',      'ai/index',             'Service',     1, 1, 1),
(8011, 801,  '转人工处理',   'F', 'ai:transfer',         NULL,                    NULL,                   NULL,          1, 1, 1),
(802,  80,   '推荐效果',     'C', 'recommend:stat',      '/admin/ai/recommend',   'ai/recommend/index',   'TrendCharts', 2, 1, 1),

-- 系统管理(仅超管)
(90,   NULL, '系统管理',     'M', NULL,                  '/admin/system',         NULL,                   'Setting',    10, 1, 1),
(901,  90,   '角色管理',     'C', 'role:list',           '/admin/system/role',    'system/role/index',    'Avatar',      1, 1, 1),
(9011, 901,  '角色编辑授权', 'F', 'role:edit',           NULL,                    NULL,                   NULL,          1, 1, 1),
(902,  90,   '菜单管理',     'C', 'menu:list',           '/admin/system/menu',    'system/menu/index',    'Menu',        2, 1, 1),
(9021, 902,  '菜单编辑',     'F', 'menu:edit',           NULL,                    NULL,                   NULL,          1, 1, 1),
(903,  90,   '村组管理',     'C', 'village:list',        '/admin/system/village', 'system/village/index', 'OfficeBuilding', 3, 1, 1),
(9031, 903,  '村组编辑',     'F', 'village:edit',        NULL,                    NULL,                   NULL,          1, 1, 1),
(904,  90,   '后台账号',     'C', 'account:list',        '/admin/system/account', 'system/account/index', 'Key',         4, 1, 1),
(9041, 904,  '分配角色',     'F', 'account:edit',        NULL,                    NULL,                   NULL,          1, 1, 1),
(905,  90,   '敏感词管理',   'C', 'sensitive:list',      '/admin/system/sensitive','system/sensitive/index','Filter',    5, 1, 1),
(9051, 905,  '批量导入',     'F', 'sensitive:edit',      NULL,                    NULL,                   NULL,          1, 1, 1),
(906,  90,   '操作日志',     'C', 'log:list',            '/admin/system/log',     'system/log/index',     'Notebook',    6, 1, 1);

-- 组件路径统一加 admin/ 前缀。
--
-- 理由:前端 src/views 下按端分目录 —— views/front/** 是前台门户,
-- views/admin/** 是后台管理端。上面写的 'audit/realname/index'
-- 加上前缀后对应 src/views/admin/audit/realname/index.vue,
-- 目录结构和"前台/后台"的划分一致,不会混在一起。
UPDATE `sys_menu`
SET `component` = CONCAT('admin/', `component`)
WHERE `component` IS NOT NULL
  AND `component` <> ''
  AND `component` NOT LIKE 'admin/%';


-- ============================================================================
--  6. 角色菜单授权
-- ============================================================================

-- 超级管理员:全部菜单(用子查询一次授权,新增菜单自动覆盖)
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
SELECT 1, `id` FROM `sys_menu`;

-- 村级管理员:本村相关的目录 + 其下菜单和按钮
-- 注意:不含"系统管理"(角色/菜单/村组/账号/敏感词/日志)和"全局统计"
--
-- 【不含 user:ban(菜单 3011)是刻意的】
--   封禁账号是平台级操作,村级管理员只能看到本村用户、审核实名,
--   不能封人。这条差异也是前端按钮级权限(v-hasPerm)最直观的演示点:
--   同一个用户列表页,超管有"封禁"按钮,村管那里这一列是空的。
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`) VALUES
(2, 1),
(2, 10),  (2, 101), (2, 1011), (2, 102), (2, 1021), (2, 1022),
(2, 20),  (2, 201), (2, 2011),
(2, 30),  (2, 301), (2, 302),
(2, 40),  (2, 401), (2, 4011), (2, 4012), (2, 402), (2, 4021), (2, 403), (2, 4031),
(2, 50),  (2, 501), (2, 5011),
(2, 60),  (2, 601), (2, 6011), (2, 6012), (2, 6013),
          (2, 602), (2, 6021), (2, 603), (2, 6031),
(2, 70),  (2, 701),
(2, 80),  (2, 801), (2, 8011), (2, 802);

-- VILLAGER / BUYER 不授后台菜单 —— 他们走前台门户,不走后台。
-- 这也正是"菜单级权限"的体现:这两类账号登录后台会看到空菜单。


-- ============================================================================
--  7. 资源分类(两级)
--     risk_level 在分类上,发布资源时自动继承:
--       电力设备 = 3(高风险)→ 发布时自动带出"需培训"
-- ============================================================================
INSERT INTO `biz_resource_category` (`id`, `parent_id`, `category_name`, `category_code`, `risk_level`, `sort_order`, `status`) VALUES
-- 一级分类
(1, NULL, '农机具',   'NJX',  1, 1, 1),
(2, NULL, '交通工具', 'JTGJ', 1, 2, 1),
(3, NULL, '房屋场地', 'FWCD', 2, 3, 1),
(4, NULL, '工具设备', 'GJSB', 1, 4, 1),
(5, NULL, '电力设备', 'DLSB', 3, 5, 1),
-- 农机具
(11, 1, '拖拉机', 'NJX-TLJ', 1, 1, 1),
(12, 1, '收割机', 'NJX-SGJ', 1, 2, 1),
(13, 1, '播种机', 'NJX-BZJ', 1, 3, 1),
(14, 1, '旋耕机', 'NJX-XGJ', 1, 4, 1),
-- 交通工具
(21, 2, '三轮车',   'JTGJ-SLC', 1, 1, 1),
(22, 2, '农用货车', 'JTGJ-NYHC',1, 2, 1),
(23, 2, '电动车',   'JTGJ-DDC', 1, 3, 1),
-- 房屋场地
(31, 3, '仓库',   'FWCD-CK', 2, 1, 1),
(32, 3, '宅基地', 'FWCD-ZJD',2, 2, 1),
(33, 3, '院落',   'FWCD-YL', 2, 3, 1),
(34, 3, '晒场',   'FWCD-SC', 2, 4, 1),
-- 工具设备
(41, 4, '电焊机',   'GJSB-DHJ', 1, 1, 1),
(42, 4, '水泵',     'GJSB-SB',  1, 2, 1),
(43, 4, '发电机',   'GJSB-FDJ', 1, 3, 1),
(44, 4, '木工工具', 'GJSB-MGGJ',1, 4, 1),
-- 电力设备(高风险)
(51, 5, '变压器', 'DLSB-BYG', 3, 1, 1),
(52, 5, '配电箱', 'DLSB-PDX', 3, 2, 1);


-- ============================================================================
--  8. 敏感词
--     action: 1替换为*  2直接拦截
-- ============================================================================
INSERT INTO `sys_sensitive_word` (`word`, `category`, `action`, `status`) VALUES
-- 广告类(替换)
('加微信',     '广告', 1, 1),
('私聊我',     '广告', 1, 1),
('扫码',       '广告', 1, 1),
('刷单',       '广告', 1, 1),
('代开发票',   '广告', 1, 1),
('高利贷',     '广告', 1, 1),
('办证',       '广告', 1, 1),
('兼职日结',   '广告', 1, 1),
-- 辱骂类(拦截)
('傻逼',       '辱骂', 2, 1),
('滚蛋',       '辱骂', 2, 1),
('废物',       '辱骂', 2, 1),
-- 违法类(拦截)
('赌博',       '违法', 2, 1),
('毒品',       '违法', 2, 1),
('枪支',       '违法', 2, 1),
('诈骗',       '违法', 2, 1);


-- ============================================================================
--  验证
-- ============================================================================
SELECT '村组' AS 表, COUNT(*) AS 条数 FROM `sys_village`
UNION ALL SELECT '角色',     COUNT(*) FROM `sys_role`
UNION ALL SELECT '用户',     COUNT(*) FROM `sys_user`
UNION ALL SELECT '用户角色', COUNT(*) FROM `sys_user_role`
UNION ALL SELECT '菜单',     COUNT(*) FROM `sys_menu`
UNION ALL SELECT '角色菜单', COUNT(*) FROM `sys_role_menu`
UNION ALL SELECT '资源分类', COUNT(*) FROM `biz_resource_category`
UNION ALL SELECT '敏感词',   COUNT(*) FROM `sys_sensitive_word`;
