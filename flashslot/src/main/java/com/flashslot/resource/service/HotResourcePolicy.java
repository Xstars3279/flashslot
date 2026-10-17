package com.flashslot.resource.service;

import com.flashslot.resource.entity.Resource;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 新设备首日优先缓存；可额外指定固定热点ID，不影响预约资格。 */
@Component
public class HotResourcePolicy {
    private final Set<Long> fixed;
    private final Clock clock;
    public HotResourcePolicy(@Value("${app.schedule-cache.hot-resource-ids:}") String ids,Clock clock) {
        this.clock=clock; fixed=new HashSet<>();
        for(String id:ids.split(",")) if(!id.isBlank()) fixed.add(Long.parseLong(id.strip()));
    }
    public boolean isHot(Resource resource) {
        return fixed.contains(resource.getId()) || resource.getCreatedAt()!=null && !resource.getCreatedAt().isBefore(clock.instant().minus(Duration.ofDays(1)));
    }
}
