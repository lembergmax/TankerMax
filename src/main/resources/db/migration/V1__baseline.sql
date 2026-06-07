-- Flyway-Baseline der Datenbank `tankermax` (3. Normalform).
--
-- Erzeugt aus den JPA-Entitaeten mit MariaDBDialect (siehe Werkzeug
-- SchemaBaselineGenerator). Die Spaltentypen entsprechen exakt den Erwartungen von
-- spring.jpa.hibernate.ddl-auto=validate und duerfen daher nur zusammen mit der
-- zugehoerigen Entitaet (und einer neuen Vxx-Migration) geaendert werden.
--
-- Auf einer bereits bestehenden Datenbank wird diese Baseline nicht ausgefuehrt
-- (spring.flyway.baseline-on-migrate=true): Flyway markiert den vorhandenen Stand als
-- Version 1 und wendet nur spaetere Migrationen an.

create table brand (
    id bigint not null auto_increment,
    name varchar(128) not null,
    primary key (id),
    constraint uq_brand_name unique (name)
) engine=InnoDB;

create table fuel_type (
    id bigint not null auto_increment,
    code varchar(16) not null,
    label varchar(255) not null,
    primary key (id),
    constraint uq_fuel_type_code unique (code)
) engine=InnoDB;

create table station (
    id varchar(36) not null,
    brand_id bigint,
    name varchar(255) not null,
    street varchar(255),
    house_number varchar(255),
    post_code varchar(5),
    place varchar(255),
    latitude float(53) not null,
    longitude float(53) not null,
    state varchar(255),
    whole_day bit,
    first_imported_at datetime(6),
    last_updated_at datetime(6),
    details_fetched_at datetime(6),
    primary key (id),
    constraint fk_station_brand foreign key (brand_id) references brand (id)
) engine=InnoDB;

create index idx_station_brand on station (brand_id);

create table opening_time (
    id bigint not null auto_increment,
    station_id varchar(36) not null,
    description varchar(255) not null,
    start_time time(0),
    end_time time(0),
    primary key (id),
    constraint fk_opening_time_station foreign key (station_id) references station (id)
) engine=InnoDB;

create index idx_opening_time_station on opening_time (station_id);

create table opening_override (
    id bigint not null auto_increment,
    station_id varchar(36) not null,
    description varchar(512) not null,
    primary key (id),
    constraint fk_opening_override_station foreign key (station_id) references station (id)
) engine=InnoDB;

create index idx_opening_override_station on opening_override (station_id);

create table price_observation (
    id bigint not null auto_increment,
    station_id varchar(36) not null,
    observed_at datetime(6) not null,
    status enum ('CLOSED','OPEN') not null,
    primary key (id),
    constraint uq_observation_station_time unique (station_id, observed_at),
    constraint fk_observation_station foreign key (station_id) references station (id)
) engine=InnoDB;

create table fuel_price (
    id bigint not null auto_increment,
    observation_id bigint not null,
    fuel_type_id bigint not null,
    amount decimal(6,3) not null,
    primary key (id),
    constraint uq_fuel_price_observation_fuel unique (observation_id, fuel_type_id),
    constraint fk_fuel_price_observation foreign key (observation_id) references price_observation (id),
    constraint fk_fuel_price_fuel_type foreign key (fuel_type_id) references fuel_type (id)
) engine=InnoDB;
