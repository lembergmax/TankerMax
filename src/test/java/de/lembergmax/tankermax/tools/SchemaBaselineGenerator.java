package de.lembergmax.tankermax.tools;

import de.lembergmax.tankermax.domain.Brand;
import de.lembergmax.tankermax.domain.FuelPrice;
import de.lembergmax.tankermax.domain.FuelType;
import de.lembergmax.tankermax.domain.OpeningOverride;
import de.lembergmax.tankermax.domain.OpeningTime;
import de.lembergmax.tankermax.domain.PriceObservation;
import de.lembergmax.tankermax.domain.Station;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Werkzeug (kein regulärer Test), das die Flyway-Baseline für die Datenbank
 * {@code tankermax} aus den JPA-Entitäten erzeugt – mit demselben Dialekt, den
 * Hibernate zur Laufzeit für die Schema-Validierung verwendet. So entspricht die
 * Baseline exakt den Erwartungen von {@code ddl-auto=validate}.
 *
 * <p>Verwendet die JPA-standardisierte Schema-Skripterzeugung (keine Datenbank-Verbindung
 * nötig; der Dialekt wird fest vorgegeben). Bewusst mit {@link Disabled} versehen, damit es
 * nicht bei jedem Build läuft. Nach einer Änderung an den Entitäten manuell ausführen
 * ({@code mvnw -Dtest=SchemaBaselineGenerator -DfailIfNoTests=false "-Djunit.jupiter.conditions.deactivate=*DisabledCondition" test})
 * und das erzeugte {@code target/generated-schema.sql} in die Flyway-Migration übernehmen.</p>
 */
@Disabled("Werkzeug zum Erzeugen der Flyway-Baseline; bei Bedarf manuell ausführen")
class SchemaBaselineGenerator {

    /**
     * Erzeugt das CREATE-Skript der Datenbank nach {@code target/generated-schema.sql}.
     */
    @Test
    void generateSchema() {
        final StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", "org.hibernate.dialect.MariaDBDialect")
                .applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false")
                .applySetting("hibernate.hbm2ddl.delimiter", ";")
                .applySetting("hibernate.format_sql", "true")
                .applySetting("jakarta.persistence.schema-generation.scripts.action", "create")
                .applySetting("jakarta.persistence.schema-generation.scripts.create-target",
                        "target/generated-schema.sql")
                .build();
        try {
            final Metadata metadata = new MetadataSources(registry)
                    .addAnnotatedClass(Brand.class)
                    .addAnnotatedClass(FuelType.class)
                    .addAnnotatedClass(Station.class)
                    .addAnnotatedClass(OpeningTime.class)
                    .addAnnotatedClass(OpeningOverride.class)
                    .addAnnotatedClass(PriceObservation.class)
                    .addAnnotatedClass(FuelPrice.class)
                    .buildMetadata();
            metadata.buildSessionFactory().close();
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

}
