package com.flashslot.resource.vo;

import com.flashslot.resource.entity.Resource;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceView {
    private Long id;
    private String name;
    private String location;
    private String status;
    private Integer defaultOpenStartSlot;
    private Integer defaultOpenEndSlot;
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    public static ResourceView from(Resource resource) {
        return new ResourceView(resource.getId(), resource.getName(), resource.getLocation(), resource.getStatus(),
            resource.getDefaultOpenStartSlot(), resource.getDefaultOpenEndSlot(), resource.getVersion(),
            resource.getCreatedAt(), resource.getUpdatedAt());
    }
}
