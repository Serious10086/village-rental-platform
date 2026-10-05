package com.gjf.village.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 后台账号列表项。
 *
 * <p>与 {@link UserListVO} 的区别:这是"管理后台账号"的视角,
 * 只需要登录、归属、角色、状态这些管理信息 ——
 * 信用分、余额、实名状态对管理员账号没有意义,不带。
 *
 * <p>{@code roleIds} 是给编辑弹窗回填多选用的,{@code roleNames} 是给表格展示用的。
 * 两个都带:只带名字则编辑时要把名字反查成 ID,只带 ID 则表格里还得前端查表。
 */
@Data
public class AccountVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String phone;
    private String realName;
    private String avatar;

    private Long villageId;
    private String villageName;

    private Integer accountStatus;

    private List<Long> roleIds;
    private Set<String> roleNames;

    private LocalDateTime createTime;
    private LocalDateTime lastLoginTime;
}
