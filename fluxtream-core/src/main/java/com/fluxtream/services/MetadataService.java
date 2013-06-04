package com.fluxtream.services;

import java.util.List;
import java.util.TimeZone;
import com.fluxtream.connectors.location.LocationFacet;
import com.fluxtream.domain.metadata.City;
import com.fluxtream.domain.metadata.WeatherInfo;
import com.fluxtream.metadata.DayMetadata;
import com.fluxtream.metadata.MonthMetadata;
import com.fluxtream.metadata.WeekMetadata;

public interface MetadataService {

	void setTimeZone(long guestId, String date, String timeZone);

    void setDayMainCity(long guestId, float latitude, float longitude, String date);

    void setWeekMainCity(long guestId, float latitude, float longitude, int year, int week);

    void setMonthMainCity(long guestId, float latitude, float longitude, int year, int month);

    void setDayMainCity(long guestId, long visitedCityId, String date);

    void setWeekMainCity(long guestId, long visitedCityId, int year, int week);

    void setMonthMainCity(long guestId, long visitedCityId, int year, int month);

	TimeZone getCurrentTimeZone(long guestId);

	TimeZone getTimeZone(long guestId, long time);

	TimeZone getTimeZone(long guestId, String date);

	DayMetadata getDayMetadata(long guestId, String date);

    WeekMetadata getWeekMetadata(long guestId, int year, int week);

    MonthMetadata getMonthMetadata(long guestId, int year, int month);

    List<DayMetadata> getAllDayMetadata(long guestId);

	LocationFacet getLastLocation(long guestId, long time);

	TimeZone getTimeZone(double latitude, double longitude);

    public City getClosestCity(double latitude, double longitude);

    List<City> getClosestCities(double latitude, double longitude,
                                double dist);

    List<WeatherInfo> getWeatherInfo(double latitude, double longitude,
                                     String date, int startMinute, int endMinute);

    public void rebuildMetadata(String username);

    public void updateLocationMetadata(long guestId, List<LocationFacet> locationResources);
}
