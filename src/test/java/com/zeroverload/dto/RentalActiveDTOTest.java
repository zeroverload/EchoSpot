package com.zeroverload.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RentalActiveDTOTest {
    @Test
    void orderIdShouldSerializeAsString() throws Exception {
        // 大于 JS Number.MAX_SAFE_INTEGER(2^53-1)，如果按数字返回前端会丢精度
        long orderId = 40_000_000_000_000_001L;

        RentalActiveDTO dto = new RentalActiveDTO();
        dto.setOrderId(orderId);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(mapper.writeValueAsString(dto));

        assertTrue(json.get("orderId").isTextual(), "orderId should be serialized as string");
    }
}

