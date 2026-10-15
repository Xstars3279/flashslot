package com.flashslot.resource.service;

import com.flashslot.resource.dto.*;
import com.baomidou.mybatisplus.extension.service.IService;
import com.flashslot.resource.entity.Resource;
import com.flashslot.resource.vo.*;

public interface ResourceService extends IService<Resource> {
    ResourceView get(long id);
    ResourcePage list(long cursor, int limit);
    ResourceView create(CreateResourceRequest request);
    ResourceView update(long id, UpdateResourceRequest request);
}
