package com.flashslot.resource.service;

import com.flashslot.resource.model.ScheduleOccupation;
import java.time.LocalDate;
import java.util.List;

public interface ScheduleCacheService {
    List<ScheduleOccupation> get(long resourceId, LocalDate date, long revision);
    void put(long resourceId, LocalDate date, long revision, List<ScheduleOccupation> occupations);
}
