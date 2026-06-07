package de.lembergmax.tankermax.polling.client.support;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

import java.math.BigDecimal;

/**
 * Jackson-Deserializer für Preisfelder der Tankerkönig-Preisschnittstelle.
 *
 * <p>Die API liefert Preise als Zahl, wenn ein Preis vorliegt, andernfalls als
 * boolesches {@code false}. Dieser Deserializer wandelt einen Zahlenwert in einen
 * {@link BigDecimal} und jeden anderen Wert in {@code null} um.</p>
 */
public class FlexiblePriceDeserializer extends ValueDeserializer<BigDecimal> {

    /**
     * Liest das aktuelle JSON-Token als Preis ein.
     *
     * @param parser  JSON-Parser an der Position des Preisfeldes
     * @param context Deserialisierungskontext
     * @return der Preis als {@link BigDecimal} oder {@code null}, falls kein Preis vorliegt
     */
    @Override
    public BigDecimal deserialize(final JsonParser parser, final DeserializationContext context) {
        final JsonNode node = parser.readValueAsTree();
        if (node != null && node.isNumber()) {
            return node.decimalValue();
        }
        return null;
    }

}
