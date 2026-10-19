package com.vtnexus.notebook.common.config;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.GenericConverter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@ConfigurationPropertiesBinding
public class EmptyMapToListConverter implements GenericConverter {

    @Override
    public Set<ConvertiblePair> getConvertibleTypes() {
        return Set.of(new ConvertiblePair(Map.class, List.class));
    }

    @Override
    public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
        if (source == null) {
            return new ArrayList<>();
        }
        Map<?, ?> map = (Map<?, ?>) source;
        if (!map.isEmpty()) {
            throw new IllegalArgumentException("expected a list, got a non-empty map");
        }
        return new ArrayList<>();
    }
}
