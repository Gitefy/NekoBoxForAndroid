package moe.matsuri.nb4a;

import static org.junit.Assert.assertEquals;

import java.util.Map;

import org.junit.Test;

public class SingBoxOptionsTest {
    @Test
    public void mapConversionUsesExplicitParameterizedType() {
        SingBoxOptions.CustomSingBoxOption options = new SingBoxOptions.CustomSingBoxOption(
                "{\"type\":\"direct\",\"nested\":{\"enabled\":true},\"port\":443}");

        Map<String, Object> map = options.asMap();

        assertEquals("direct", map.get("type"));
        assertEquals(443L, map.get("port"));
        assertEquals(true, ((Map<?, ?>) map.get("nested")).get("enabled"));
    }

    @Test
    public void customOptionAcceptsObjectMapWithoutGenericReflection() {
        SingBoxOptions.CustomSingBoxOption option =
                new SingBoxOptions.CustomSingBoxOption("{\"type\":\"direct\"}");

        assertEquals("direct", option.getBasicMap().get("type"));
    }
}
