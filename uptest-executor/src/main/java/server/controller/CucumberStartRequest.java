package server.controller;

import java.util.UUID;

public class CucumberStartRequest {

    private UUID id;

    private String feature;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getFeature() {
        return feature;
    }

    public void setFeature(String feature) {
        this.feature = feature;
    }
}
 