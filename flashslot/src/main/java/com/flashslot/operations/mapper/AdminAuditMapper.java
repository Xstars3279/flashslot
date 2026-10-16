package com.flashslot.operations.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flashslot.operations.dto.AuditQueryRequest;
import com.flashslot.operations.entity.AdminAudit;
import com.flashslot.operations.vo.AuditView;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AdminAuditMapper extends BaseMapper<AdminAudit> {
    List<AuditView> findPage(
            @Param("request") AuditQueryRequest request,
            @Param("anchor") AdminAudit anchor,
            @Param("fetchSize") int fetchSize);
}
