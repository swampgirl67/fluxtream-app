package com.fluxtream.connectors.fluxtream_capture;

import java.io.Serializable;
import javax.persistence.Entity;
import javax.persistence.Lob;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import com.fluxtream.connectors.Connector;
import com.fluxtream.connectors.ObjectType;
import com.fluxtream.connectors.annotations.ObjectTypeSpec;
import com.fluxtream.domain.AbstractFacet;
import org.hibernate.search.annotations.Indexed;
import org.jetbrains.annotations.NotNull;

/**
 * @author Chris Bartley (bartley@cmu.edu)
 */
@Entity(name = "Facet_FluxtreamCapturePhoto")
@ObjectTypeSpec(name = "photo", value = 1, isImageType = true, prettyname = "Photos")
@NamedQueries({
    @NamedQuery(name = "fluxtream_capture.photo.all", query = "SELECT facet FROM Facet_FluxtreamCapturePhoto facet WHERE facet.guestId=? ORDER BY facet.start ASC"),
    @NamedQuery(name = "fluxtream_capture.photo.deleteAll", query = "DELETE FROM Facet_FluxtreamCapturePhoto facet WHERE facet.guestId=?"),
    @NamedQuery(name = "fluxtream_capture.photo.between", query = "SELECT facet FROM Facet_FluxtreamCapturePhoto facet WHERE facet.guestId=? AND facet.start>=? AND facet.start<=? ORDER BY facet.start ASC"),
    @NamedQuery(name = "fluxtream_capture.photo.newest", query = "SELECT facet FROM Facet_FluxtreamCapturePhoto facet WHERE facet.guestId=? ORDER BY facet.start DESC LIMIT 1")
})
@Indexed
public class FluxtreamCapturePhotoFacet extends AbstractFacet implements Serializable {

    private String hash;
    private String title;
    private String captureYYYYDDD;
    private String latitude;
    private String longitude;

    @Lob
    private byte[] thumbnailSmall;

    @Lob
    private byte[] thumbnailLarge;

    @SuppressWarnings("UnusedDeclaration")
    public FluxtreamCapturePhotoFacet() {
        // need this for Hibernate
    }

    public FluxtreamCapturePhotoFacet(@NotNull final FluxtreamCapturePhoto photo) {
        guestId = photo.getGuestId();
        timeUpdated = System.currentTimeMillis();
        start = photo.getCaptureTimeMillisUtc();
        end = start;
        hash = photo.getPhotoHash();

        final Connector connector = Connector.getConnector(FluxtreamCaptureUpdater.CONNECTOR_NAME);
        this.api = connector.value();
        this.objectType = ObjectType.getObjectType(connector, "photo").value();

        captureYYYYDDD = photo.getCaptureYYYYDDD();

        thumbnailSmall = photo.getThumbnailSmall();
        thumbnailLarge = photo.getThumbnailLarge();

        // TODO: copy geolocation data
    }

    @Override
    protected void makeFullTextIndexable() {
        this.fullTextDescription = title;
    }

    public String getHash() {
        return hash;
    }

    public String getTitle() {
        return title;
    }

    public String getCaptureYYYYDDD() {
        return captureYYYYDDD;
    }

    public String getLatitude() {
        return latitude;
    }

    public String getLongitude() {
        return longitude;
    }

    public byte[] getThumbnailSmall() {
        return thumbnailSmall;
    }

    public byte[] getThumbnailLarge() {
        return thumbnailLarge;
    }
}
