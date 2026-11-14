package com.vulnscan.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

/**
 * The two Jackson mappers used everywhere (config files and run artefacts).
 * snake_case keys, unknown keys rejected, map keys sorted so saved files are deterministic.
 */
public final class Json {

    private static final ObjectMapper JSON = configure(JsonMapper.builder().build());
    private static final ObjectMapper YAML = configure(new ObjectMapper(
            YAMLFactory.builder().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).build()));

    private Json() {
    }

    public static ObjectMapper json() {
        return JSON;
    }

    public static ObjectMapper yaml() {
        return YAML;
    }

    private static ObjectMapper configure(ObjectMapper mapper) {
        return mapper
                .registerModule(new Jdk8Module())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.INDENT_OUTPUT, true)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }
}
