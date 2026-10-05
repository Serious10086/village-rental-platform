package com.gjf.village.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gjf.village.common.enums.DataScopeEnum;
import com.gjf.village.common.exception.BusinessException;
import com.gjf.village.common.result.PageResult;
import com.gjf.village.common.result.ResultCode;
import com.gjf.village.common.util.JwtUtil;
import com.gjf.village.common.util.UserContext;
import com.gjf.village.dto.ChangePasswordDTO;
import com.gjf.village.dto.LoginDTO;
import com.gjf.village.dto.OutsideUserCreateDTO;
import com.gjf.village.dto.RegisterDTO;
import com.gjf.village.dto.UserQueryDTO;
import com.gjf.village.entity.*;
import com.gjf.village.mapper.*;
import com.gjf.village.security.LoginUser;
import com.gjf.village.service.DataScopeHelper;
import com.gjf.village.service.SysMenuService;
import com.gjf.village.service.SysUserService;
import com.gjf.village.vo.LoginVO;
import com.gjf.village.vo.UserInfoVO;
import com.gjf.village.vo.UserListVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户与认证服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl implements SysUserService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysVillageMapper villageMapper;
    private final SysMenuService menuService;
    private final DataScopeHelper dataScopeHelper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /** 账号状态:正常 */
    private static final int ACCOUNT_NORMAL = 1;
    /** 实名状态:已认证 */
    private static final int REALNAME_VERIFIED = 2;

    /** 用户类型:村民(资源发布者) */
    private static final int USER_TYPE_VILLAGER = 1;
    /** 用户类型:租户/买家(资源使用者) */
    private static final int USER_TYPE_BUYER = 2;

    /**
     * 管理员代建外来用户时的固定初始密码。
     *
     * <p>不做随机生成:备案是管理员在村委会当面代办的场景,
     * 随机密码需要额外一次"展示并抄走"的交互,而抄错的概率比固定密码更高。
     */
    private static final String OUTSIDE_INIT_PASSWORD = "123456";

    // ═══════════════════════════════════════════════════════════
    //  登录
    // ═══════════════════════════════════════════════════════════

    @Override
    public LoginVO login(LoginDTO dto) {
        // 1. 按手机号取用户(这一次查询包含密码字段,否则没法比对)
        SysUser user = userMapper.selectByPhoneForLogin(dto.getPhone());

        // 2. 统一提示"手机号或密码错误",不区分"用户不存在"和"密码错误"。
        //
        //    如果分开提示,攻击者可以用它来枚举系统里已注册的手机号 ——
        //    输入一个号码,看返回的是"用户不存在"还是"密码错误",
        //    就能判断这个号有没有注册过。这是账号枚举漏洞。
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.LOGIN_FAILED);
        }

        // 3. 账号状态检查(密码对但账号被禁用,要给明确提示,否则用户不知道为什么进不去)
        if (!Objects.equals(user.getAccountStatus(), ACCOUNT_NORMAL)) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }

        // 4. 更新最后登录时间。失败不影响登录,所以单独 try 一下
        try {
            SysUser update = new SysUser();
            update.setId(user.getId());
            update.setLastLoginTime(LocalDateTime.now());
            userMapper.updateById(update);
        } catch (Exception e) {
            log.warn("更新最后登录时间失败: userId={}", user.getId(), e);
        }

        // 5. 签发 token
        String token = jwtUtil.generateToken(user.getId(), user.getPhone());

        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setExpiresIn(jwtUtil.getExpireMinutes());
        vo.setUserInfo(buildUserInfo(user.getId()));
        return vo;
    }

    // ═══════════════════════════════════════════════════════════
    //  修改密码
    // ═══════════════════════════════════════════════════════════

    /**
     * 修改当前登录用户自己的密码。
     *
     * <p>改谁从登录态取,参数里根本没有 userId —— 想越权改别人也没有入口。
     *
     * <p>原密码必须验对。token 是长期有效的凭证,如果只要有 token 就能改密码,
     * 那么一次 token 泄露就不只是"被冒用一会儿",而是被永久占有账号:
     * 冒用者改掉密码,真正的所有者反而进不去了。
     */
    @Override
    public void changePassword(ChangePasswordDTO dto) {
        LoginUser me = UserContext.get();

        // selectByPhoneForLogin 是全项目唯一会把 password 列查出来的方法 ——
        // SysUser.password 上标着 @TableField(select = false),
        // 普通 selectById 拿到的是 null,没法比对
        SysUser user = userMapper.selectByPhoneForLogin(me.getPhone());

        if (user == null || !passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "原密码不正确");
        }

        // 只更新 password 一列:updateById 会忽略 null 字段,
        // 传整个对象会把前端没传的字段(如 balance)覆盖掉
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        userMapper.updateById(update);

        // 注意旧 token 到这里仍然有效 —— 无状态 JWT 没有会话可作废,
        // 详见 SysUserService#changePassword 的说明
        log.info("密码已修改: userId={}", user.getId());
    }

    // ═══════════════════════════════════════════════════════════
    //  注册
    // ═══════════════════════════════════════════════════════════

    /**
     * 注册。
     *
     * <p>用 {@code @Transactional} 是因为要写两张表(用户表 + 用户角色关联表),
     * 必须原子 —— 只写成功用户表而角色表失败,会产生一个登录后什么权限都没有的
     * "孤儿账号",用户会以为系统坏了。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long register(RegisterDTO dto) {
        // 1. 手机号查重。
        //    这里先查一次是为了给出友好的错误提示;
        //    真正的防重靠数据库上的 uk_phone 唯一索引 —— 两个请求同时注册同一个号码时,
        //    这一行查询都会通过,最终由唯一索引拦下其中一个。
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, dto.getPhone()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.PHONE_EXISTS);
        }

        // 2. 村组校验(填了就必须是有效村组)
        if (dto.getVillageId() != null) {
            SysVillage village = villageMapper.selectById(dto.getVillageId());
            if (village == null) {
                throw BusinessException.badRequest("所选村组不存在");
            }
        }

        // 3. 写用户
        SysUser user = new SysUser();
        user.setPhone(dto.getPhone());
        // 密码必须加密存储。BCrypt 每次加密同一密码得到的密文都不同(自带随机盐),
        // 所以撞库时无法用彩虹表反查
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setUserType(dto.getUserType() == null ? USER_TYPE_BUYER : dto.getUserType());
        user.setRealName(dto.getRealName());
        user.setVillageId(dto.getVillageId());
        user.setAddress(dto.getAddress());
        user.setAvatar("");
        user.setCreditScore(100);          // 初始信用分 100
        user.setRealnameStatus(0);         // 未提交实名,后续在个人中心提交(批次 4)
        user.setAccountStatus(ACCOUNT_NORMAL);
        user.setBalance(java.math.BigDecimal.ZERO);
        user.setIsOutside(0);
        userMapper.insert(user);

        // 4. 授默认角色
        String roleCode = Objects.equals(user.getUserType(), USER_TYPE_VILLAGER)
                ? "VILLAGER" : "BUYER";
        SysRole role = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, roleCode));
        if (role == null) {
            // 角色表没初始化就注册,属于部署配置问题,必须让事务回滚并报错
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "系统角色未初始化,请联系管理员");
        }

        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getId());
        userRoleMapper.insert(userRole);

        log.info("新用户注册: userId={}, phone={}, userType={}, villageId={}",
                user.getId(), dto.getPhone(), user.getUserType(), dto.getVillageId());
        return user.getId();
    }

    // ═══════════════════════════════════════════════════════════
    //  后台代建外来用户
    // ═══════════════════════════════════════════════════════════

    /**
     * 管理员代外来人员建账号。
     *
     * <p>和 {@link #register} 的关键区别是归属村组<b>留空</b>:
     * 备案通过之前这个人不属于任何村。目标村组只写在审核单的
     * {@code village_id} 上,等村管点了「通过」才落到这里。
     *
     * <p>这个先后顺序是有意义的 —— 系统里好几处按"有没有归属村"来区分
     * "能不能办事":比如 {@code WalletServiceImpl.applyWithdraw} 会直接拒绝
     * 没有归属村的账号提现。如果建号时就把村写上,这些限制就形同虚设,
     * 一个还没被任何人确认过的外来人员立刻就能走完整流程。
     *
     * <p>{@code is_outside} 置 1,并且以后也不改回 0 —— 它是永久身份标记,
     * 和"现在归属哪个村"是两件事。抹掉之后就再也分不出谁是外来人员了。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createOutsideUser(OutsideUserCreateDTO dto) {
        // 手机号查重。和 register 同样的两道:先查一次给友好提示,
        // 真正的防重靠 uk_phone 唯一索引
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, dto.getPhone()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.PHONE_EXISTS);
        }

        SysUser user = new SysUser();
        user.setPhone(dto.getPhone());
        user.setPassword(passwordEncoder.encode(OUTSIDE_INIT_PASSWORD));
        user.setUserType(USER_TYPE_BUYER);
        user.setRealName(dto.getRealName());
        user.setVillageId(null);            // 备案通过前无归属,理由见方法注释
        user.setAddress(dto.getAddress());
        user.setAvatar("");
        user.setCreditScore(100);
        user.setRealnameStatus(0);          // 实名是另一条流,备案不替代它
        user.setAccountStatus(ACCOUNT_NORMAL);
        user.setBalance(java.math.BigDecimal.ZERO);
        user.setIsOutside(1);
        userMapper.insert(user);

        // 授默认角色。外来人员也要能发布/租用资源,所以给 BUYER 而不是什么都不给 ——
        // 没有角色的账号登录后处处 403,看起来像系统坏了
        SysRole role = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, "BUYER"));
        if (role == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR,
                    "系统角色未初始化,请联系管理员");
        }
        SysUserRole userRole = new SysUserRole();
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getId());
        userRoleMapper.insert(userRole);

        log.info("代建外来用户: userId={}, phone={}, 目标村组={}",
                user.getId(), dto.getPhone(), dto.getVillageId());
        return user.getId();
    }

    // ═══════════════════════════════════════════════════════════
    //  装配登录上下文
    // ═══════════════════════════════════════════════════════════

    @Override
    public LoginUser buildLoginUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || !Objects.equals(user.getAccountStatus(), ACCOUNT_NORMAL)) {
            // 用户被删或被禁用:不装配身份,后续需要登录的接口会返回 401
            return null;
        }

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(user.getId());
        loginUser.setPhone(user.getPhone());
        loginUser.setRealName(user.getRealName());
        loginUser.setUserType(user.getUserType());
        loginUser.setVillageId(user.getVillageId());
        loginUser.setRealnameStatus(user.getRealnameStatus());

        // 角色
        List<Long> roleIds = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId)
                        .select(SysUserRole::getRoleId))
                .stream().map(SysUserRole::getRoleId).toList();
        loginUser.setRoleIds(roleIds);

        if (roleIds.isEmpty()) {
            // 没有任何角色:数据范围按最严格处理,权限码为空
            loginUser.setDataScope(DataScopeEnum.SELF.getCode());
            return loginUser;
        }

        List<SysRole> roles = roleMapper.selectBatchIds(roleIds);
        loginUser.setRoleCodes(roles.stream()
                .map(SysRole::getRoleCode)
                .collect(java.util.stream.Collectors.toSet()));

        // 数据范围:多角色取最宽松者。
        // 比如同时是 SUPER_ADMIN(1 全部)和 VILLAGER(4 仅本人),应按 1 处理 ——
        // 否则加了个普通角色反而把管理员权限压掉了,不符合直觉
        loginUser.setDataScope(DataScopeEnum.merge(
                roles.stream().map(SysRole::getDataScope).toList()));

        // 权限码:从被授权的菜单里提取非空的 perms
        Set<String> perms = new HashSet<>();
        menuService.listMenusByUserId(userId).stream()
                .map(SysMenu::getPerms)
                .filter(p -> p != null && !p.isBlank())
                .forEach(perms::add);
        loginUser.setPerms(perms);

        return loginUser;
    }

    // ═══════════════════════════════════════════════════════════
    //  用户信息
    // ═══════════════════════════════════════════════════════════

    @Override
    public UserInfoVO getUserInfo(Long userId) {
        return buildUserInfo(userId);
    }

    // ═══════════════════════════════════════════════════════════
    //  后台:用户列表(数据级权限示范)
    // ═══════════════════════════════════════════════════════════

    /**
     * 后台用户列表。
     *
     * <p><b>数据级权限的示范实现</b>,顺序很关键:
     * <ol>
     *   <li>先调 {@code dataScopeHelper.apply(query)} 注入可见范围 ——
     *       这一步会<b>覆盖</b>前端传来的 villageId,是防越权的关键</li>
     *   <li>再拿注入后的条件去拼 SQL</li>
     * </ol>
     * 顺序反了(先拼条件再注入)就等于没做权限控制。
     */
    @Override
    public PageResult<UserListVO> pageUsers(UserQueryDTO query) {
        // ① 注入数据范围(村级管理员 → villageIds=[本村],超管 → 不限)
        dataScopeHelper.apply(query);

        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                // 村组范围:列表为空表示"不限"(超管);非空则限定在这些村里
                .in(query.getVillageIds() != null && !query.getVillageIds().isEmpty(),
                        SysUser::getVillageId, query.getVillageIds())
                // "仅本人"范围:只看自己
                .eq(query.getPublisherId() != null, SysUser::getId, query.getPublisherId())
                // 以下都是用户主动选择的筛选条件
                .eq(query.getUserType() != null, SysUser::getUserType, query.getUserType())
                .eq(query.getRealnameStatus() != null, SysUser::getRealnameStatus, query.getRealnameStatus())
                .eq(query.getAccountStatus() != null, SysUser::getAccountStatus, query.getAccountStatus())
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(SysUser::getPhone, query.getKeyword())
                        .or().like(SysUser::getRealName, query.getKeyword()))
                .orderByDesc(SysUser::getCreateTime);

        Page<SysUser> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<SysUser> result = userMapper.selectPage(page, wrapper);

        if (result.getRecords().isEmpty()) {
            return PageResult.empty(query.getPageNum(), query.getPageSize());
        }

        // 批量补村组名和角色名 —— 逐条查会产生 N+1 查询,
        // 100 个用户就是 200 次数据库往返
        List<SysUser> users = result.getRecords();
        List<Long> userIds = users.stream().map(SysUser::getId).toList();

        Map<Long, String> villageNameMap = villageMapper.selectList(
                        new LambdaQueryWrapper<SysVillage>()
                                .in(SysVillage::getId, users.stream()
                                        .map(SysUser::getVillageId)
                                        .filter(Objects::nonNull)
                                        .distinct()
                                        .toList()))
                .stream()
                .collect(Collectors.toMap(SysVillage::getId, SysVillage::getVillageName));

        Map<Long, Set<String>> userRoleMap = loadUserRoleNames(userIds);

        List<UserListVO> voList = users.stream().map(user -> {
            UserListVO vo = new UserListVO();
            vo.setId(user.getId());
            vo.setPhone(user.getPhone());
            vo.setRealName(user.getRealName());
            vo.setAvatar(user.getAvatar());
            vo.setUserType(user.getUserType());
            vo.setVillageId(user.getVillageId());
            vo.setVillageName(villageNameMap.get(user.getVillageId()));
            vo.setAddress(user.getAddress());
            vo.setCreditScore(user.getCreditScore());
            vo.setRealnameStatus(user.getRealnameStatus());
            vo.setAccountStatus(user.getAccountStatus());
            vo.setBalance(user.getBalance());
            vo.setIsOutside(user.getIsOutside());
            vo.setRoleNames(userRoleMap.getOrDefault(user.getId(), Set.of()));
            vo.setCreateTime(user.getCreateTime());
            vo.setLastLoginTime(user.getLastLoginTime());
            return vo;
        }).toList();

        return PageResult.of(result, voList);
    }

    /**
     * 批量查询"用户 → 角色名集合"。
     *
     * <p>用两次 IN 查询代替逐用户查询,避免 N+1。
     */
    private Map<Long, Set<String>> loadUserRoleNames(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<SysUserRole> userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getUserId, userIds));
        if (userRoles.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> roleNameMap = roleMapper.selectBatchIds(
                        userRoles.stream().map(SysUserRole::getRoleId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(SysRole::getId, SysRole::getRoleName));

        // userId → 角色名集合
        return userRoles.stream().collect(Collectors.groupingBy(
                SysUserRole::getUserId,
                Collectors.mapping(ur -> roleNameMap.getOrDefault(ur.getRoleId(), "未知角色"),
                        Collectors.toSet())));
    }

    private UserInfoVO buildUserInfo(Long userId) {
        LoginUser loginUser = buildLoginUser(userId);
        if (loginUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        SysUser user = userMapper.selectById(userId);

        UserInfoVO vo = new UserInfoVO();
        vo.setUserId(user.getId());
        vo.setPhone(user.getPhone());
        vo.setRealName(user.getRealName());
        // 身份证号脱敏后再返回,见 UserInfoVO.idCard 的说明
        vo.setIdCard(maskIdCard(user.getIdCard()));
        vo.setAvatar(user.getAvatar());
        vo.setUserType(user.getUserType());
        vo.setVillageId(user.getVillageId());
        vo.setAddress(user.getAddress());
        vo.setCreditScore(user.getCreditScore());
        vo.setRealnameStatus(user.getRealnameStatus());
        vo.setAccountStatus(user.getAccountStatus());
        vo.setBalance(user.getBalance());
        vo.setDataScope(loginUser.getDataScope());
        vo.setRoles(loginUser.getRoleCodes());
        vo.setPerms(loginUser.getPerms());
        vo.setSuperAdmin(loginUser.isSuperAdmin());
        vo.setRealnameVerified(Objects.equals(user.getRealnameStatus(), REALNAME_VERIFIED));

        // 村组名称直接带出来,省得前端再发一次请求
        if (user.getVillageId() != null) {
            SysVillage village = villageMapper.selectById(user.getVillageId());
            if (village != null) {
                vo.setVillageName(village.getVillageName());
                vo.setTownName(village.getTownName());
            }
        }
        return vo;
    }

    /**
     * 身份证号脱敏:保留前 6 位(地区码)和后 4 位。
     *
     * <p>长度不足 10 位时原样返回 —— 那种数据本身就不合法,
     * 强行截取会得到一个更奇怪的结果。
     */
    private String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 10) {
            return idCard;
        }
        return idCard.substring(0, 6) + "********" + idCard.substring(idCard.length() - 4);
    }
}
