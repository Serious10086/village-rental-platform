package com.gjf.village.service;

import com.gjf.village.common.result.PageResult;
import com.gjf.village.dto.AuditDTO;
import com.gjf.village.dto.AuditQueryDTO;
import com.gjf.village.dto.OutsideUserCreateDTO;
import com.gjf.village.dto.RealnameSubmitDTO;
import com.gjf.village.vo.AuditRecordVO;

/**
 * 通用审核服务。
 *
 * <p>实名认证和资源发布两条审核流共用这一套接口 ——
 * 它们的流程完全同构:<b>提交 → 生成待办 → 管理员审核 → 改业务状态 + 记流水 + 发通知</b>。
 * 靠 bizType 分发到不同的业务处理逻辑。
 */
public interface SysAuditService {

    /**
     * 提交实名认证。
     *
     * <p>会把用户状态置为「待审核」并生成一条审核待办。
     * 重复提交时覆盖上一次的资料并复用待办(而不是堆积多条)。
     */
    void submitRealname(RealnameSubmitDTO dto);

    /**
     * 提交外来用户备案(管理员代外来人员提交)。
     *
     * <p>建账号 + 生成审核待办,必须原子 —— 只建了账号没建单,
     * 管理员就不知道要审什么,而这个人会一直停在"无归属"状态。
     *
     * <p>目标村组必须落在当前管理员的管辖范围内,否则村管改一下请求体里的
     * villageId 就能替别的村做决定。
     *
     * @return 新用户ID
     */
    Long submitOutside(OutsideUserCreateDTO dto);

    /**
     * 审核(通过 / 驳回)。
     *
     * <p>这是本模块最核心的方法,一次调用要改动三处:
     * 业务表状态、审核流水、给申请人发通知 —— 必须在一个事务里。
     */
    void audit(AuditDTO dto);

    /**
     * 审核待办 / 记录列表(受数据级权限约束)。
     */
    PageResult<AuditRecordVO> pageAudits(AuditQueryDTO query);

    /**
     * 审核详情(含完整身份证号,仅用于管理员核对身份)。
     */
    AuditRecordVO getAuditDetail(Long auditId);

    /**
     * 待办数量,供后台工作台的角标使用。
     *
     * @param bizType 业务类型;null 表示全部
     */
    long countPending(Integer bizType);
}
