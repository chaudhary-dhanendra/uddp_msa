package gov.uddp.core.api.v1;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Makes the OpenAPI schemas of platform records match what Jackson writes: every record component is always present
 * (required), and components of reference types other than collections and maps may be null (nullable). The generated
 * TypeScript types then say exactly which values the UI must handle as missing. JSON-valued components (JsonNode)
 * accept any value.
 */
@Component
public class RecordSchemaConverter implements ModelConverter {
    private static final String PLATFORM_PACKAGE = "gov.uddp.";

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        Schema schema = chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
        if (schema == null || type.getType() == null) {
            return schema;
        }
        JavaType javaType = Json.mapper().constructType(type.getType());
        Class<?> raw = javaType.getRawClass();
        if (!raw.isRecord() || !raw.getPackageName().startsWith(PLATFORM_PACKAGE)) {
            return schema;
        }
        Schema target = schema;
        if (schema.get$ref() != null) {
            String name = schema.get$ref().substring(schema.get$ref().lastIndexOf('/') + 1);
            target = context.getDefinedModels().get(name);
        }
        if (target == null || target.getProperties() == null) {
            return schema;
        }
        Map<String, Schema> properties = target.getProperties();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : raw.getRecordComponents()) {
            Schema property = properties.get(component.getName());
            if (property == null) {
                continue;
            }
            required.add(component.getName());
            if (JsonNode.class.isAssignableFrom(component.getType())) {
                // Arbitrary JSON (payloads, evidence, values): any value, typed unknown in TypeScript.
                Schema any = new Schema();
                any.setNullable(true);
                any.setDescription(property.getDescription());
                properties.put(component.getName(), any);
            } else if (nullable(component.getType())) {
                if (property.get$ref() != null) {
                    // OpenAPI 3.0 ignores siblings of $ref, so a nullable reference is allOf [ref] + nullable.
                    var wrapper = new ComposedSchema().addAllOfItem(new Schema().$ref(property.get$ref()));
                    wrapper.setNullable(true);
                    properties.put(component.getName(), wrapper);
                } else {
                    property.setNullable(true);
                }
            }
        }
        target.setRequired(required);
        return schema;
    }

    private static boolean nullable(Class<?> type) {
        return !type.isPrimitive() && !Collection.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type);
    }
}
