package com.birddex.app;

import com.google.firebase.firestore.PropertyName;

public class Bird {
    private String id; 
    private String commonName;
    private String scientificName;
    private String family;
    private String species;
    private boolean isEndangered;
    private boolean canHunt;
    private String lastSeenLocationIdGeorgia; 
    private Long lastSeenTimestampGeorgia; 
    private Double lastSeenLatitudeGeorgia;
    private Double lastSeenLongitudeGeorgia;

    public Bird() {
        // Default constructor required for calls to DataSnapshot.getValue(Bird.class)
    }

    public Bird(String id, String commonName, String scientificName, String family, String species, boolean isEndangered, boolean canHunt, String lastSeenLocationIdGeorgia, Long lastSeenTimestampGeorgia, Double lastSeenLatitudeGeorgia, Double lastSeenLongitudeGeorgia) {
        this.id = id;
        this.commonName = commonName;
        this.scientificName = scientificName;
        this.family = family;
        this.species = species;
        this.isEndangered = isEndangered;
        this.canHunt = canHunt;
        this.lastSeenLocationIdGeorgia = lastSeenLocationIdGeorgia;
        this.lastSeenTimestampGeorgia = lastSeenTimestampGeorgia;
        this.lastSeenLatitudeGeorgia = lastSeenLatitudeGeorgia;
        this.lastSeenLongitudeGeorgia = lastSeenLongitudeGeorgia;
    }

    // Use PropertyName to ensure Firestore maps the document ID to this field if desired,
    // or just to avoid the "No setter" warning if 'id' exists in the document data.
    @PropertyName("id")
    public String getId() {
        return id;
    }

    @PropertyName("id")
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

    // Changed to standard getter/setter for boolean fields to fix Firestore warnings
    @PropertyName("isEndangered")
    public boolean isEndangered() {
        return isEndangered;
    }

    @PropertyName("isEndangered")
    public void setEndangered(boolean endangered) {
        isEndangered = endangered;
    }

    public boolean isCanHunt() {
        return canHunt;
    }

    public void setCanHunt(boolean canHunt) {
        this.canHunt = canHunt;
    }

    public String getLastSeenLocationIdGeorgia() {
        return lastSeenLocationIdGeorgia;
    }

    public void setLastSeenLocationIdGeorgia(String lastSeenLocationIdGeorgia) {
        this.lastSeenLocationIdGeorgia = lastSeenLocationIdGeorgia;
    }

    public Long getLastSeenTimestampGeorgia() {
        return lastSeenTimestampGeorgia;
    }

    public void setLastSeenTimestampGeorgia(Long lastSeenTimestampGeorgia) {
        this.lastSeenTimestampGeorgia = lastSeenTimestampGeorgia;
    }

    public Double getLastSeenLatitudeGeorgia() {
        return lastSeenLatitudeGeorgia;
    }

    public void setLastSeenLatitudeGeorgia(Double lastSeenLatitudeGeorgia) {
        this.lastSeenLatitudeGeorgia = lastSeenLatitudeGeorgia;
    }

    public Double getLastSeenLongitudeGeorgia() {
        return lastSeenLongitudeGeorgia;
    }

    public void setLastSeenLongitudeGeorgia(Double lastSeenLongitudeGeorgia) {
        this.lastSeenLongitudeGeorgia = lastSeenLongitudeGeorgia;
    }
}
