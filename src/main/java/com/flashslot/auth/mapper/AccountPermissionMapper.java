package com.flashslot.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashslot.auth.entity.AppUser;

import org.apache.ibatis.annotations.*;

@Mapper
public interface AccountPermissionMapper extends BaseMapper<AppUser> {
    /** 已认证请求按用户 ID 核对当前账号状态，不再用用户名定位身份。 */
    @Select("SELECT id,username,role,enabled FROM app_user WHERE id=#{id}")
    AppUser findCurrentAccountById(long id);

    /** 仅用于密码认证，不复用到普通权限查询，也不直接返回实体。 */
    @Select(
            "SELECT id,username,password_hash,role,enabled FROM app_user WHERE"
                    + " username=#{username}")
    AppUser findForLogin(String username);

    /** 预约取得资源/日历/候补锁后锁定用户，读取当前处罚，不读取密码。 */
    @Select(
            "SELECT id,username,role,enabled,credit_score,restriction_until FROM app_user WHERE"
                    + " id=#{id} FOR UPDATE")
    AppUser lockBookingAccount(long id);

    /** 登录前定位限流账号；已认证请求应使用 ID，不读取密码。 */
    @Select("SELECT id,username,role,enabled FROM app_user WHERE username=#{username}")
    AppUser findCurrentAccount(String username);
}
