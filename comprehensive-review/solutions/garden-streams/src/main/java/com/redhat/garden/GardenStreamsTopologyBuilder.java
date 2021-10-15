package com.redhat.garden;

import javax.enterprise.inject.Produces;

import java.time.Duration;

import javax.enterprise.context.ApplicationScoped;

import com.redhat.garden.entities.GardenStatus;
import com.redhat.garden.entities.Sensor;
import com.redhat.garden.entities.SensorMeasurement;
import com.redhat.garden.entities.SensorMeasurementEnriched;
import com.redhat.garden.entities.SensorMeasurementType;
import com.redhat.garden.events.DryConditionsDetected;
import com.redhat.garden.events.StrongWindDetected;
import com.redhat.garden.events.LowTemperatureDetected;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.GlobalKTable;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.state.WindowStore;

import io.quarkus.kafka.client.serialization.ObjectMapperSerde;



@ApplicationScoped
public class GardenStreamsTopologyBuilder {

    private static final double LOW_TEMPERATURE_THRESHOLD_CELSIUS = 5.0;
    private static final double LOW_HUMIDITY_THRESHOLD_PERCENT = 0.2;
    private static final double STRONG_WIND_THRESHOLD_MS = 10;
    public static final String SENSORS_TOPIC = "sensors";
    public static final String SENSOR_MEASUREMENTS_TOPIC = "sensor-measurements";
    public static final String LOW_TEMPERATURE_EVENTS_TOPIC = "low-temperature-events";
    public static final String LOW_HUMIDITY_EVENTS_TOPIC = "low-humidity-events";
    public static final String STRONG_WIND_EVENTS_TOPIC = "strong-wind-events";
    public static final String GARDEN_STATUS_EVENTS_TOPIC = "garden-status-events";

    private final ObjectMapperSerde<Sensor> sensorSerde = new ObjectMapperSerde<>(Sensor.class);
    private final ObjectMapperSerde<SensorMeasurement> sensorMeasurementSerde = new ObjectMapperSerde<>(SensorMeasurement.class);
    private final ObjectMapperSerde<SensorMeasurementEnriched> sensorMeasurementEnrichedSerde = new ObjectMapperSerde<>(SensorMeasurementEnriched.class);
    private final ObjectMapperSerde<LowTemperatureDetected> lowTemperatureEventSerde = new ObjectMapperSerde<>(LowTemperatureDetected.class);
    private final ObjectMapperSerde<DryConditionsDetected> dryConditionsEventSerde = new ObjectMapperSerde<>(DryConditionsDetected.class);
    private final ObjectMapperSerde<StrongWindDetected> strongWindEventSerde = new ObjectMapperSerde<>(StrongWindDetected.class);
    private final ObjectMapperSerde<GardenStatus> gardenStatusSerde = new ObjectMapperSerde<>(GardenStatus.class);

    @Produces
    public Topology build() {
        StreamsBuilder builder = new StreamsBuilder();

        GlobalKTable<Integer, Sensor> sensors = builder.globalTable(
            SENSORS_TOPIC,
            Consumed.with(Serdes.Integer(), sensorSerde));

        KStream<Integer, SensorMeasurementEnriched> enrichedSensorMeasurements = builder
            .stream(SENSOR_MEASUREMENTS_TOPIC, Consumed.with(Serdes.Integer(), sensorMeasurementSerde))
            .join(
                sensors,
                (sensorId, measurement) -> sensorId,
                (measurement, sensor) -> new SensorMeasurementEnriched(measurement, sensor)
            );

        enrichedSensorMeasurements
            .groupBy(
                (sensorId, measurement) -> measurement.gardenName,
                Grouped.with(Serdes.String(), sensorMeasurementEnrichedSerde)
            )
            .windowedBy(TimeWindows.of(Duration.ofMinutes(60)).advanceBy(Duration.ofMinutes(60)))
            .aggregate(
                GardenStatus::new,
                (gardenName, measurement, gardenStatus) -> gardenStatus.updateWith(measurement),
                Materialized
                    .<String, GardenStatus, WindowStore<Bytes, byte[]>>as("garden-status-store")
                        .withKeySerde(Serdes.String())
                        .withValueSerde(gardenStatusSerde))
            .toStream()
            .map((windowedGardenName, gardenStatus) ->
                new KeyValue<>(windowedGardenName.key(), gardenStatus))
            .to(
                GARDEN_STATUS_EVENTS_TOPIC,
                Produced.with(Serdes.String(), gardenStatusSerde));

        enrichedSensorMeasurements.split()
                .branch(
                    (sensorId, measurement) -> measurement.type.equals(SensorMeasurementType.TEMPERATURE),
                    Branched.withConsumer(this::proccessTemperature))
                .branch(
                    (sensorId, measurement) -> measurement.type.equals(SensorMeasurementType.HUMIDITY),
                    Branched.withConsumer(this::processHumidity))
                .branch(
                    (sensorId, measurement) -> measurement.type.equals(SensorMeasurementType.WIND),
                    Branched.withConsumer(this::processWind));

        return builder.build();
    }

    private void proccessTemperature(KStream<Integer, SensorMeasurementEnriched> temperatureMeasurements) {
        temperatureMeasurements
            .filter((sensorId, measurement) -> measurement.value < LOW_TEMPERATURE_THRESHOLD_CELSIUS)
            .mapValues((measurement) -> new LowTemperatureDetected(
                measurement.gardenName, measurement.sensorId, measurement.value, measurement.timestamp))
            .to(LOW_TEMPERATURE_EVENTS_TOPIC, Produced.with(Serdes.Integer(), lowTemperatureEventSerde));
    }

    private void processHumidity(KStream<Integer, SensorMeasurementEnriched> humidityMeasurements) {
        humidityMeasurements
            .filter((sensorId, measurement) -> measurement.value < LOW_HUMIDITY_THRESHOLD_PERCENT)
            .mapValues((measurement) -> new DryConditionsDetected(
                measurement.gardenName, measurement.sensorId, measurement.value, measurement.timestamp))
            .to(LOW_HUMIDITY_EVENTS_TOPIC, Produced.with(Serdes.Integer(), dryConditionsEventSerde));
    }

    private void processWind(KStream<Integer, SensorMeasurementEnriched> windMeasurements) {
        windMeasurements
            .filter((sensorId, measurement) -> measurement.value > STRONG_WIND_THRESHOLD_MS)
            .mapValues((measurement) -> new StrongWindDetected(
                measurement.gardenName, measurement.sensorId, measurement.value, measurement.timestamp))
            .to(STRONG_WIND_EVENTS_TOPIC, Produced.with(Serdes.Integer(), strongWindEventSerde));
    }

}
