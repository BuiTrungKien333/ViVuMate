package com.vivumate.coreapi.dto.response;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {
    private int currentPage;
    private int totalPage;
    private int pageSize;
    private long totalElements;
    private List<T> data;
}
