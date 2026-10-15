package com.flashslot.resource.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/** 排期只读投影：SQL 不读取其他用户身份，仅条件投影本人的订单号。 */
@Data
@NoArgsConstructor
public class ScheduleOccupation {
    private Integer slotIndex;
    private String state;
    private String ownOrderNo;
}
