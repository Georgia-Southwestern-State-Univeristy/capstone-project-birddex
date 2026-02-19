package com.birddex.app;

import com.google.firebase.firestore.Exclude;

import java.util.Map;

public class Location {
    private String id; // This will store the locationId
    private Map<String, Object> metadata; // Using Map for flexible metadata
    private double latitude;
    private double longitude;
    private String country;
    private String state;
    private String locality;

    public Location() {
        // Default constructor required for calls to DataSnapshot.getValue(Location.class)
    }

    public Location(String id, Map<String, Object> metadata, double latitude, double longitude, String country, String state, String locality) {
        this.id = id;
        this.metadata = metadata;
        this.latitude = latitude;
        this.longitude = longitude;
        this.country = country;
        this.state = state;
        this.locality = locality;
    }

    @Exclude
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getLocality() {
        return locality;
    }

    public void setLocality(String locality) {
        this.locality = locality;
    }
}
