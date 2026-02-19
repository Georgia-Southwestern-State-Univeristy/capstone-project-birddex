package com.birddex.app;

import com.google.firebase.firestore.Exclude;
// import java.util.Date; // No longer needed for timestamp field

public class Bird {
    private String id; // This will store the birdId (eBird speciesCode)
    private String commonName;
    private String scientificName;
    private String family;
    private String species;
    private boolean isEndangered;
    private boolean canHunt;
    private String lastSeenLocationIdGeorgia; // Reference to Location document
    private Long lastSeenTimestampGeorgia; // CHANGED FROM Date TO Long
    private Double lastSeenLatitudeGeorgia;
    private Double lastSeenLongitudeGeorgia;


    public Bird() {
        // Default constructor required for calls to DataSnapshot.getValue(Bird.class)
    }

    public Bird(String id, String commonName, String scientificName, String family, String species, boolean isEndangered, boolean canHunt, String lastSeenLocationIdGeorgia, Long lastSeenTimestampGeorgia, Double lastSeenLatitudeGeorgia, Double lastSeenLongitudeGeorgia) { // CHANGED FROM Date TO Long
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

    public boolean isEndangered() {
        return isEndangered;
    }

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

    public Long getLastSeenTimestampGeorgia() { // CHANGED FROM Date TO Long
        return lastSeenTimestampGeorgia;
    }

    public void setLastSeenTimestampGeorgia(Long lastSeenTimestampGeorgia) { // CHANGED FROM Date TO Long
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
