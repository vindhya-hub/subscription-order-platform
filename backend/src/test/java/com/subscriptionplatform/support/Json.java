package com.subscriptionplatform.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.subscriptionplatform.events.OrderEventCodec;

/** JSON setup equivalent to Spring Boot's auto-configured ObjectMapper, for plain unit tests. */
public final class Json {

    private Json() {
    }

    public static ObjectMapper mapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    public static OrderEventCodec codec() {
        return new OrderEventCodec(mapper());
    }
}
