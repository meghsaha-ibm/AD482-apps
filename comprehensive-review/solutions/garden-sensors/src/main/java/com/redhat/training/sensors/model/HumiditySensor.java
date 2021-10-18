package com.redhat.training.sensors.model;

import com.redhat.training.sensors.util.SensorIdUtil;

public class HumiditySensor extends Sensor{

    @Override
    public int getId() {
        return SensorIdUtil.getRandomId(1, 3);
    }

    @Override
    public int getMinValue() {
        return 0;
    }

    @Override
    public int getMaxValue() {
        return 1;
    }

    @Override
    public SensorType getType() {
        return SensorType.HUMIDITY;
    }

}
