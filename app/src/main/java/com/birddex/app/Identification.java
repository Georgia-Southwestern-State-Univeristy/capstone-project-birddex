package com.birddex.app;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;

public class Identification {
    private String id; // This will store the identificationId
    private String commonName;
    private String scientificName;
    private String family;
    private String species;
    private String locationId;
    private boolean verified;
    private String imageUrl;
    private Date timestamp;

    public Identification() {
        // Default constructor required for calls to DataSnapshot.getValue(Identification.class)
    }

    public Identification(String id, String commonName, String scientificName, String family, String species, String locationId, boolean verified, String imageUrl, Date timestamp) {
        this.id = id;
        this.commonName = commonName;
        this.scientificName = scientificName;
        this.family = family;
        this.species = species;
        this.locationId = locationId;
        this.verified = verified;
        this.imageUrl = imageUrl;
        this.timestamp = timestamp;
    }

    @Exclude
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCommonName() {
        return commonName;
    }

    public void setCommonName(String commonName) {
        this.commonName = commonName;
    }

    public String getScientificName() {
        return scientificName;
    }

    public void setScientificName(String scientificName) {
        this.scientificName = scientificName;
    }

    public String getFamily() {
        return family;
    }

    public void setFamily(String family) {
        this.family = family;
    }

    public String getSpecies() {
        return species;
    }

    public void setSpecies(String species) {
        this.species = species;
    }

    public String getLocationId() {
        return locationId;
    }

    public void setLocationId(String locationId) {
        this.locationId = locationId;
    }

    public boolean isVerified() {
        return verified;
    }

    public void setVerified(boolean verified) {
        this.verified = verified;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    @ServerTimestamp
    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }
}
