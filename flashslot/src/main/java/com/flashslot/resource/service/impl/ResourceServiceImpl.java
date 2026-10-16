package com.flashslot.resource.service.impl;

import com.flashslot.auth.service.CurrentAdmin;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashslot.common.web.BusinessException;
import com.flashslot.operations.service.AdminAuditService;
import com.flashslot.resource.dto.*;
import com.flashslot.resource.entity.*;
import com.flashslot.resource.mapper.*;
import com.flashslot.resource.service.ResourceService;
import com.flashslot.resource.vo.*;
import java.time.*;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResourceServiceImpl extends ServiceImpl<ResourceMapper, Resource> implements ResourceService {
    private final ResourceDayMapper days;
    private final CurrentAdmin admin;
    private final AdminAuditService audit;
    private final Clock clock;

    /** 按设备 ID 查询详情，不存在时交给统一异常处理返回业务错误。 */
    @Override
    public ResourceView get(long id) { return ResourceView.from(required(id)); }

    /** ID 升序游标分页，多查一条用于判断 hasMore，最大每页 100 条。 */
    @Override
    public ResourcePage list(long cursor, int limit) {
        if (cursor < 0 || limit < 1 || limit > 100) invalid("分页参数超出范围");
        var found = baseMapper.selectList(Wrappers.<Resource>lambdaQuery()
            .eq(Resource::getStatus, "ACTIVE")
            .gt(Resource::getId, cursor)
            .orderByAsc(Resource::getId)
            // limit 已验证为 1–100，此处只拼接服务端计算的整数。
            .last("LIMIT " + (limit + 1)));
        boolean more = found.size() > limit;
        var items = found.stream().limit(limit).map(ResourceView::from).toList();
        String next = more ? Long.toString(items.getLast().getId()) : null;
        return new ResourcePage(items, next, more);
    }

    /** 新增设备、初始 14 天日历与审计同事务提交，权限失败时整体回滚。 */
    @Override
    @Transactional
    public ResourceView create(CreateResourceRequest request) {
        range(request.getDefaultOpenStartSlot(), request.getDefaultOpenEndSlot());
        Resource resource = new Resource();
        resource.setName(text(request.getName())); resource.setLocation(text(request.getLocation()));
        resource.setDefaultOpenStartSlot(request.getDefaultOpenStartSlot());
        resource.setDefaultOpenEndSlot(request.getDefaultOpenEndSlot());
        baseMapper.insert(resource);
        // 资源写锁在前，账号锁在后；任何权限或审计失败都回滚新增资源。
        long actor = admin.lockAndRequireAdmin();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai")));
        var calendars = new ArrayList<ResourceDay>();
        for (int i=0; i<14; i++) {
            ResourceDay day = new ResourceDay();
            day.setResourceId(resource.getId()); day.setBookDate(today.plusDays(i));
            day.setOpenStartSlot(resource.getDefaultOpenStartSlot()); day.setOpenEndSlot(resource.getDefaultOpenEndSlot());
            calendars.add(day);
        }
        days.insertDays(calendars);
        audit.record(actor,"RESOURCE_CREATE",resource.getId(),text(request.getReason()));
        return get(resource.getId());
    }

    /** DTO 中的 version 为用户读到的旧版本，MyBatis-Plus 乐观锁防止并发覆盖。 */
    @Override
    @Transactional
    public ResourceView update(long id, UpdateResourceRequest request) {
        if (request.getName()==null && request.getLocation()==null && request.getDefaultOpenStartSlot()==null && request.getDefaultOpenEndSlot()==null) {
            invalid("至少提供一个配置字段");
        }
        if (request.getVersion() == null || request.getVersion() < 0) {
            invalid("version 必须为非负整数");
        }
        long expectedVersion = request.getVersion();
        Resource resource = required(id);
        if (resource.getVersion() != expectedVersion) {
            throw new BusinessException(HttpStatus.CONFLICT,"VERSION_CONFLICT","设备配置版本不一致，请刷新后重试");
        }
        if (request.getName()!=null) resource.setName(text(request.getName()));
        if (request.getLocation()!=null) resource.setLocation(text(request.getLocation()));
        if (request.getDefaultOpenStartSlot()!=null) resource.setDefaultOpenStartSlot(request.getDefaultOpenStartSlot());
        if (request.getDefaultOpenEndSlot()!=null) resource.setDefaultOpenEndSlot(request.getDefaultOpenEndSlot());
        range(resource.getDefaultOpenStartSlot(),resource.getDefaultOpenEndSlot());
        if (baseMapper.updateById(resource)!=1) {
            throw new BusinessException(HttpStatus.CONFLICT,"VERSION_CONFLICT","设备配置已被修改，请刷新后重试");
        }
        long actor = admin.lockAndRequireAdmin();
        audit.record(actor,"RESOURCE_UPDATE",id,text(request.getReason()));
        return get(id);
    }

    /** 查询必须存在的设备，避免后续业务对 null 继续操作。 */
    private Resource required(long id) {
        Resource resource = baseMapper.selectById(id);
        if (resource==null) throw new BusinessException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","设备不存在");
        return resource;
    }
    /** 校验半小时时间格的左闭右开区间，48 表示次日零点这一结束边界。 */
    private static void range(int start, int end) {
        if (start < 0 || start >= end || end > 48) throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_SLOT_RANGE","开放时间需满足 0 <= 起始格 < 结束格 <= 48");
    }
    /** 清理文本两侧空白，并拒绝空文本；长度由请求 DTO 的校验注解限制。 */
    private static String text(String value) {
        if (value==null || value.isBlank()) invalid("文本字段不能为空");
        return value.strip();
    }
    /** 抛出统一的参数错误，由全局处理器转换为 Result 与 HTTP 400。 */
    private static void invalid(String message) {
        throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_ARGUMENT",message);
    }
}
