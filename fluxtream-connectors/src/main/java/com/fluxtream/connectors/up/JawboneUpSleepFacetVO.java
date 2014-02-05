package com.fluxtream.connectors.up;

import com.fluxtream.OutsideTimeBoundariesException;
import com.fluxtream.TimeInterval;
import com.fluxtream.connectors.vos.AbstractFacetVO;
import com.fluxtream.connectors.vos.TimeOfDayVO;
import com.fluxtream.domain.GuestSettings;
import com.fluxtream.mvc.models.DurationModel;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDateTime;

/**
 * User: candide
 * Date: 04/02/14
 * Time: 13:50
 */
public class JawboneUpSleepFacetVO extends AbstractFacetVO<JawboneUpSleepFacet> {

    public String title;
    public String date;
    public double place_lat;
    public double place_lon;
    public int place_acc;
    public String place_name;
    public String snapshot_image;
    public long smart_alarm_fire;
    public long awake_time;
    public long asleep_time;
    public int awakenings;
    public int rem;
    public int light;
    public int deep;
    public int awake;
    public int duration;
    public int quality;
    public DurationModel timeSleeping;
    public DurationModel timeAwake;
    public TimeOfDayVO startTime;
    public TimeOfDayVO timeAsleep;
    public TimeOfDayVO wakeUpTime;
    public TimeOfDayVO endTime;

    @Override
    protected void fromFacet(final JawboneUpSleepFacet facet, final TimeInterval timeInterval, final GuestSettings settings) throws OutsideTimeBoundariesException {
        this.title = facet.title;
        this.date = facet.date;
        this.place_acc = facet.place_acc;
        this.place_lat = facet.place_lat;
        this.place_lon = facet.place_lon;
        this.place_name = facet.place_name;
        this.snapshot_image = facet.snapshot_image;
        this.smart_alarm_fire = facet.smart_alarm_fire;
        this.awake_time = facet.awake_time;
        this.asleep_time = facet.asleep_time;
        this.awakenings = facet.awakenings;
        this.rem = facet.rem;
        this.light = facet.light;
        this.deep = facet.deep;
        this.awake = facet.awake;
        this.duration = facet.duration;
        this.quality = facet.quality;

        LocalDateTime localStartTime = new LocalDateTime(facet.start, DateTimeZone.forID(facet.tz));
        startTime = new TimeOfDayVO(localStartTime.getHourOfDay()*60+localStartTime.getMinuteOfHour(), true);

        LocalDateTime localTimeAsleep= new LocalDateTime(asleep_time*1000, DateTimeZone.forID(facet.tz));
        timeAsleep = new TimeOfDayVO(localTimeAsleep.getHourOfDay()*60+localTimeAsleep.getMinuteOfHour(), true);

        LocalDateTime localTimeAwake= new LocalDateTime(awake_time*1000, DateTimeZone.forID(facet.tz));
        wakeUpTime = new TimeOfDayVO(localTimeAwake.getHourOfDay()*60+localTimeAwake.getMinuteOfHour(), true);

        LocalDateTime localEndTime = new LocalDateTime(facet.end, DateTimeZone.forID(facet.tz));
        endTime = new TimeOfDayVO(localEndTime.getHourOfDay()*60+localEndTime.getMinuteOfHour(), true);

        timeSleeping = new DurationModel(duration);
        timeAwake = new DurationModel(awake);
    }

}
