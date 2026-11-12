package com.smartfarm360.livestock;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public final class HatcheryDtos {
    private HatcheryDtos() {}
    public record CreateRequest(@NotNull Long lineId, @NotNull LocalDate collectedOn, @NotNull LocalDate incubatedOn,
                                LocalDate expectedOn, @NotNull @Positive @Max(1000000) Integer eggs,
                                @Size(max=300) String notes) {}
    public record ResultRequest(@NotNull LocalDate resultOn, @NotNull @PositiveOrZero @Max(1000000) Integer fertile,
                                @NotNull @PositiveOrZero @Max(1000000) Integer hatched,
                                @NotNull @PositiveOrZero @Max(1000000) Integer failed,
                                @Size(max=300) String notes) {}
    public record Hatch(long id, long groupId, long lineId, String variety, LocalDate collectedOn, LocalDate incubatedOn,
                        LocalDate expectedOn, int eggs, Integer fertile, Integer hatched, Integer failed,
                        LocalDate resultOn, String notes, boolean completed) {}
}
