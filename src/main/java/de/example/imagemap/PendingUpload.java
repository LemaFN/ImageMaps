package de.example.imagemap;

import java.util.UUID;

/**
 * Repräsentiert einen offenen Upload-Link, den ein Spieler per /imagemap upload
 * bekommen hat, aber noch nicht auf der Website eingelöst hat.
 */
public class PendingUpload {

    public final String token;
    public final UUID ownerId;
    public final String ownerName;
    public final long expiresAt;

    public PendingUpload(String token, UUID ownerId, String ownerName, long expiresAt) {
        this.token = token;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }
}
