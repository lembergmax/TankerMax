package de.lembergmax.tankermax.client.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Tests für {@link FlexiblePriceDeserializer}: Das Preisfeld der Tankerkönig-API ist eine Zahl,
 * wenn ein Preis vorliegt, andernfalls {@code false} – genau diese Eigenheit wird hier abgesichert.
 */
class FlexiblePriceDeserializerTest {

    /** Jackson-Mapper (Jackson 3) für die Testdeserialisierung. */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Ein Zahlenwert wird als exakter {@link BigDecimal} übernommen.
     */
    @Test
    void zahlWirdZuBigDecimal() {
        final Holder holder = mapper.readValue("{\"price\": 1.509}", Holder.class);
        assertEquals(new BigDecimal("1.509"), holder.price);
    }

    /**
     * Das boolesche {@code false} (kein Preis verfügbar) ergibt {@code null}.
     */
    @Test
    void falseWirdZuNull() {
        final Holder holder = mapper.readValue("{\"price\": false}", Holder.class);
        assertNull(holder.price);
    }

    /**
     * Ein ausdrückliches {@code null} ergibt {@code null}.
     */
    @Test
    void nullWirdZuNull() {
        final Holder holder = mapper.readValue("{\"price\": null}", Holder.class);
        assertNull(holder.price);
    }

    /**
     * Eine als Zeichenkette kodierte Zahl gilt nicht als Zahlknoten und ergibt daher {@code null}.
     */
    @Test
    void zeichenketteWirdZuNull() {
        final Holder holder = mapper.readValue("{\"price\": \"1.50\"}", Holder.class);
        assertNull(holder.price);
    }

    /**
     * Testhülle mit einem über {@link FlexiblePriceDeserializer} deserialisierten Preisfeld.
     */
    static class Holder {

        /** Preisfeld, das die Zahl-oder-{@code false}-Eigenheit der API abbildet. */
        @JsonDeserialize(using = FlexiblePriceDeserializer.class)
        public BigDecimal price;

    }

}
