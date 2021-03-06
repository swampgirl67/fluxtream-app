package org.fluxtream.connectors.moves;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicResponseHandler;
import org.fluxtream.core.aspects.FlxLogger;
import org.fluxtream.core.connectors.Connector;
import org.fluxtream.core.connectors.annotations.Updater;
import org.fluxtream.core.connectors.location.LocationFacet;
import org.fluxtream.core.connectors.updaters.AbstractUpdater;
import org.fluxtream.core.connectors.updaters.RateLimitReachedException;
import org.fluxtream.core.connectors.updaters.UpdateFailedException;
import org.fluxtream.core.connectors.updaters.UpdateInfo;
import org.fluxtream.core.domain.AbstractLocalTimeFacet;
import org.fluxtream.core.domain.ApiKey;
import org.fluxtream.core.domain.Notification;
import org.fluxtream.core.domain.UpdateWorkerTask;
import org.fluxtream.core.services.ApiDataService;
import org.fluxtream.core.services.ConnectorUpdateService;
import org.fluxtream.core.services.JPADaoService;
import org.fluxtream.core.services.MetadataService;
import org.fluxtream.core.services.impl.BodyTrackHelper;
import org.fluxtream.core.services.impl.BodyTrackHelper.ChannelStyle;
import org.fluxtream.core.services.impl.BodyTrackHelper.MainTimespanStyle;
import org.fluxtream.core.services.impl.BodyTrackHelper.TimespanStyle;
import org.fluxtream.core.utils.JPAUtils;
import org.fluxtream.core.utils.TimeUtils;
import org.fluxtream.core.utils.UnexpectedHttpResponseCodeException;
import org.fluxtream.core.utils.Utils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.*;

/**
 * User: candide
 * Date: 17/06/13
 * Time: 16:50
 */
@Component
@Updater(prettyName = "Moves", value = 144, objectTypes = {LocationFacet.class, MovesMoveFacet.class, MovesPlaceFacet.class}, bodytrackResponder = MovesBodytrackResponder.class,
         defaultChannels = {"Moves.data"})
public class MovesUpdater extends AbstractUpdater {
    static FlxLogger logger = FlxLogger.getLogger(AbstractUpdater.class);

    @Autowired
    BodyTrackHelper bodyTrackHelper;

    final static String host = "https://api.moves-app.com/api/1.1";
    final static String updateDateKeyName = "lastDate";

    public static DateTimeFormatter compactDateFormat = DateTimeFormat.forPattern("yyyyMMdd");
    public final DateTimeFormatter localTimeStorageFormat = DateTimeFormat.forPattern("yyyyMMdd'T'HHmmssZ");
    public static final DateTimeFormatter httpResponseDateFormat = DateTimeFormat.forPattern("EEE, dd MMM yyyy HH:mm:ss ZZZ");

    // This holds onto the next time that we know quota is available.  The quota for Moves is global
    // across all the instances using a given consumer key.  The MovesUpdater is a singleton, so
    // all the Moves updates in a given system will share this same object.  The access to this variable is
    // synchronized such that the first thread that finds out that the quota has been exceeded for now
    // can be treated specially with respect to handling rescheduling.  Subsequent threads that find out
    // that quotaAvailableTime has already been updated beyond the present can either wait until more quota
    // is available or yield until a later time, but should not try to handle rescheduling.
    private volatile long quotaAvailableTime=0;

    // This is the maximum number of millis we're willing to wait for quota to become available.  By default it's a
    // minute
    private long maxQuotaWaitMillis=DateTimeConstants.MILLIS_PER_MINUTE;

    @Autowired
    MovesController controller;

    @Autowired
    MetadataService metadataService;

    @Autowired
    JPADaoService jpaDaoService;

    @Autowired
    ConnectorUpdateService connectorUpdateService;


    @Override
    protected void updateConnectorDataHistory(final UpdateInfo updateInfo) throws Exception, UpdateFailedException {
        // Get the date for starting the update.  This will either be a stored date from a previous run
        // of the updater or the user's registration date.
        String updateStartDate = getUpdateStartDate(updateInfo);

        updateMovesData(updateInfo, updateStartDate, false);
    }

    // Get/update moves data for the range of dates starting from the stored date of the last update.
    // Do a maximum of pastDaysToUpdatePlaces days of fixup on earlier dates to pickup user-initiated changes
    @Override
    protected void updateConnectorData(final UpdateInfo updateInfo) throws Exception, UpdateFailedException {
        // Get the date for starting the update.  This will either be a stored date from a previous run
        // of the updater or the user's registration date.
        String updateStartDate = getUpdateStartDate(updateInfo);

        updateMovesData(updateInfo, updateStartDate, true);
    }

    public String getUserRegistrationDate(UpdateInfo updateInfo) throws Exception, UpdateFailedException {
        // Check first if we already have a user registration date stored in apiKeyAttributes as userRegistrationDate.
        // userRegistrationDate is stored in storage format (yyyy-mm-dd)
        String userRegistrationKeyName = "userRegistrationDate";
        String userRegistrationDate = (String)updateInfo.getContext(userRegistrationKeyName);

        // The first time we do this there won't be a stored userRegistrationDate yet.  In that case get the
        // registration date from a Moves API call
        if(userRegistrationDate == null) {
            long currentTime = System.currentTimeMillis();
            String accessToken = controller.getAccessToken(updateInfo.apiKey);
            String query = host + "/user/profile?access_token=" + accessToken;
            try {
                final String fetched = fetchMovesAPI(updateInfo, query);
                countSuccessfulApiCall(updateInfo.apiKey, updateInfo.objectTypes, currentTime, query);
                JSONObject json = JSONObject.fromObject(fetched);
                if (!json.has("profile"))
                    throw new Exception("no profile");
                final JSONObject profile = json.getJSONObject("profile");
                if (!profile.has("firstDate"))
                    throw new Exception("no firstDate in profile");
                String compactRegistrationDate = profile.getString("firstDate");

                if(compactRegistrationDate!=null) {
                    // The format of firstDate returned by the Moves API is compact (yyyymmdd).  Convert to
                    // the storage format (yyyy-mm-dd) for consistency
                    userRegistrationDate = toStorageFormat(compactRegistrationDate);

                    // Cache registrationDate so we don't need to do an API call next time
                    final String storedUserRegistrationDate = guestService.getApiKeyAttribute(updateInfo.apiKey, userRegistrationKeyName);
                    if (storedUserRegistrationDate!=null&&!storedUserRegistrationDate.equals(userRegistrationDate)) {
                        logger.warn("Moves userRegistrationDate has changed (was " +
                                    storedUserRegistrationDate + ", is now " + userRegistrationDate + ") " +
                                    "apiKeyId=" + updateInfo.apiKey.getId());
                        guestService.setApiKeyAttribute(updateInfo.apiKey, userRegistrationKeyName, userRegistrationDate);
                    }
                    updateInfo.setContext(userRegistrationKeyName, userRegistrationDate);
                }
            } catch (UnexpectedHttpResponseCodeException e) {
                // Couldn't get user registration date
                StringBuilder sb = new StringBuilder("module=updateQueue component=updater action=MovesUpdater.getUserRegistrationDate")
                        .append(" message=\"exception while retrieving UserRegistrationDate\" connector=")
                        .append(updateInfo.apiKey.getConnector().toString()).append(" guestId=")
                        .append(updateInfo.apiKey.getGuestId())
                        .append(" stackTrace=<![CDATA[").append(Utils.stackTrace(e)).append("]]>");;
                logger.info(sb.toString());

                countFailedApiCall(updateInfo.apiKey, updateInfo.objectTypes, currentTime, query, Utils.stackTrace(e),
                                   e.getHttpResponseCode(), e.getHttpResponseMessage());

                // The update failed.  We don't know if this is permanent or temporary.
                // let's assume that it is permanent if it's our fault (4xx)
                if (e.getHttpResponseCode()>=400&&e.getHttpResponseCode()<500)
                    throw new UpdateFailedException("Unexpected response code: " + e.getHttpResponseCode(), new Exception(), true,
                                                    ApiKey.PermanentFailReason.clientError(e.getHttpResponseCode(), e.getHttpResponseMessage()));
                throw new UpdateFailedException(e);

            } catch (RateLimitReachedException e) {
                // Couldn't get user registration date, rate limit reached
                StringBuilder sb = new StringBuilder("module=updateQueue component=updater action=MovesUpdater.getUserRegistrationDate")
                        .append(" message=\"rate limit reached while retrieving UserRegistrationDate\" connector=")
                        .append(updateInfo.apiKey.getConnector().toString()).append(" guestId=")
                        .append(updateInfo.apiKey.getGuestId())
                        .append(" stackTrace=<![CDATA[").append(Utils.stackTrace(e)).append("]]>");;
                logger.info(sb.toString());

                countFailedApiCall(updateInfo.apiKey, updateInfo.objectTypes, currentTime, query, Utils.stackTrace(e),
                                   429, "Rate limit reached");

                // Rethrow the rate limit reached exception
                throw e;

            } catch (IOException e) {
                // Couldn't get user registration date
                StringBuilder sb = new StringBuilder("module=updateQueue component=updater action=MovesUpdater.getUserRegistrationDate")
                        .append(" message=\"exception while retrieving UserRegistrationDate\" connector=")
                        .append(updateInfo.apiKey.getConnector().toString()).append(" guestId=")
                        .append(updateInfo.apiKey.getGuestId())
                        .append(" stackTrace=<![CDATA[").append(Utils.stackTrace(e)).append("]]>");
                logger.info(sb.toString());

                reportFailedApiCall(updateInfo.apiKey, updateInfo.objectTypes, currentTime, query, Utils.stackTrace(e), "I/O");

                // The update failed.  We don't know if this is permanent or temporary.
                // Throw the appropriate exception.
                throw new UpdateFailedException(e);
            }
        }
        return userRegistrationDate;
    }

    public String getUpdateStartDate(UpdateInfo updateInfo) throws Exception
    {
        // Check first if we already have a date stored in apiKeyAttributes as updateDateKeyName.
        // In the case of a failure the updater will store the date
        // that failed and start there next time.  In the case of a successfully completed update it will store
        // the date of the last day that returned partial data
        // The updateDateKeyName attribute is stored in storage format (yyyy-mm-dd)
        String updateStartDate = guestService.getApiKeyAttribute(updateInfo.apiKey, updateDateKeyName);

        // The first time we do this there won't be an apiKeyAttribute yet.  In that case get the
        // registration date for the user and store that.
        if(updateStartDate == null) {
            updateStartDate = getUserRegistrationDate(updateInfo);

            // Store in the apiKeyAttribute for next time
            guestService.setApiKeyAttribute(updateInfo.apiKey, updateDateKeyName, updateStartDate);
        }
        return updateStartDate;
    }


    @Override
    public void setDefaultChannelStyles(ApiKey apiKey) {
        ChannelStyle channelStyle = new ChannelStyle();
        channelStyle.timespanStyles = new MainTimespanStyle();
        channelStyle.timespanStyles.defaultStyle = new TimespanStyle();
        channelStyle.timespanStyles.defaultStyle.fillColor = "#e9e9e9";
        channelStyle.timespanStyles.defaultStyle.borderColor = "#c9c9c9";
        channelStyle.timespanStyles.defaultStyle.borderWidth = 2;
        channelStyle.timespanStyles.defaultStyle.top = 0.0;
        channelStyle.timespanStyles.defaultStyle.bottom = 1.0;
        channelStyle.timespanStyles.values = new HashMap();

        TimespanStyle stylePart = new TimespanStyle();
        stylePart.top = 0.25;
        stylePart.bottom = 0.75;
        stylePart.fillColor = "#23ee70";
        stylePart.borderColor = "#03ce50";
        channelStyle.timespanStyles.values.put("wlk",stylePart);

        stylePart = new TimespanStyle();
        stylePart.top = 0.25;
        stylePart.bottom = 0.75;
        stylePart.fillColor = "#e674ec";
        stylePart.borderColor = "#c654cc";
        channelStyle.timespanStyles.values.put("run",stylePart);

        stylePart = new TimespanStyle();
        stylePart.top = 0.25;
        stylePart.bottom = 0.75;
        stylePart.fillColor = "#68abef";
        stylePart.borderColor = "#488bcf";
        channelStyle.timespanStyles.values.put("cyc",stylePart);

        stylePart = new TimespanStyle();
        stylePart.top = 0.25;
        stylePart.bottom = 0.75;
        stylePart.fillColor = "#8f8f8d";
        stylePart.borderColor = "#6f6f6d";
        channelStyle.timespanStyles.values.put("trp",stylePart);

        bodyTrackHelper.setBuiltinDefaultStyle(apiKey.getGuestId(), apiKey.getConnector().getName(), "data", channelStyle);
    }


    // Get/update moves data for the range of dates start
    protected void updateMovesData(final UpdateInfo updateInfo, String fullUpdateStartDate, boolean performDataFixup) throws Exception {
        // Calculate the lists of days to update. Moves only updates its data for a given day when either the user
        // manually opens the application or when the phone notices that it's past midnight local time.  The former
        // action generates partial data for the day and the latter generates finalized data for that day with respect
        // to the parsing of the move and place segments and the generation of the GPS data points.  However, the
        // user is able to go back and modify things like place IDs and movemet segment types ("wlk', 'trp', or 'cyc').
        //
        // fullUpdateStartDate is the last date that we had partial data for previous time we did an update (user had opened app
        // but the phone presumably hadn't done the cross-midnight recompute (NOTE: this assumption may be flawed if our
        // inferred user timezone differs from the phone's idea of its own timezone).  We will do a full update
        // including GPS points, for that date and all future dates up to and including today as computed in the
        // timezone we currently infer for the user.
        //
        // The days prior to fullUpdateStartDate should presumably have imported
        // complete updates during a previous update so we don't need to reimport the GPS data points.  However,
        // we do need to import the move/place segments and reconcile them with our stored versions since the
        // user may have tweaked some of them.  We currently arbitrarily re-import the pastDaysToUpdatePlaces prior days to do this
        // fixup operation.

        // getDatesSince and getDatesBefore both take their arguments and return their list of dates in storage
        // format (yyyy-mm-dd).  The list returned by getDatesSince includes the date passed in (in this case fullUpdateStartDate)
        // but getDatesBefore does not, so fullUpdateStartDate is processed as a full update.

        final List<String> fullUpdateDates = getDatesSince(fullUpdateStartDate);

        // For the dates that aren't yet completed (fullUpdateStartDate through today), createOrUpdate with trackpoints.
        // createOrUpdateData will also update updateDateKeyName to set the start time for the next update as it goes
        // to be the last date that had non-empty data when withTrackpoints is true (meaning we're moving forward in time)
        //String maxDateWithData = createOrUpdateData(fullUpdateDates, updateInfo, true);
        forwardUpdateDataWithTrackPoints(fullUpdateDates, updateInfo);

        // If fixupDateNum>0, do createOrUpdate without trackpoints for the fixupDateNum dates prior to
        // fullUpdateStartDate
        if(performDataFixup) {
            backwardFixupDataNoTrackPoints(fullUpdateStartDate, updateInfo);
        }
    }

    /**
     * Fetches storyLine, including location data (trackPoints), for the <code>fullUpdateDates</code> list
     * of dates, creating batches of 7 days (max number of days as of v1.1). Upon receiving the data, this method
     * will reorder the data in asc order and keep track of the last successfully processed day of data to minimize
     * the amount of API calls in case of a failure and import has to be re-initiated at a later time.
     * @param fullUpdateDates dates to fetch data for, in ASC order
     * @param updateInfo
     */
    private void forwardUpdateDataWithTrackPoints(final List<String> fullUpdateDates, final UpdateInfo updateInfo) throws Exception {
        // Create or update the data for a list of dates.  Returns the date of the latest day with non-empty data,
        // or null if no dates had data

        // Get the user registration date for comparison.  There's no point in trying to update data from before then.
        String userRegistrationDate = getUserRegistrationDate(updateInfo);
        String maxDateWithData=getMaxDateWithDataInDB(updateInfo);

        try {
            List<String> filteredDates = new ArrayList<String>();
            for (String date : fullUpdateDates) {
                if(date==null || (userRegistrationDate!=null && date.compareTo(userRegistrationDate)<0)) {
                    // This date is either invalid or would be before the registration date, skip it
                    continue;
                }
                filteredDates.add(date);
            }
            List<List<String>> weeks = subListsOf(filteredDates, 7);
            for (List<String> week : weeks) {
                String fromDate = week.get(0);
                String toDate = week.get(week.size()-1);
                String fetched = fetchStorylineForDates(updateInfo, fromDate, toDate, true, null);
                if(fetched!=null) {
                    // put the results in ascending order
                    JSONArray storyline = JSONArray.fromObject(fetched);
                    TreeMap<String, JSONObject> dayStorylines = new TreeMap<String,JSONObject>();
                    for (int i=0;i<storyline.size();i++) {
                        JSONObject dayStoryline = storyline.getJSONObject(i);
                        dayStorylines.put(dayStoryline.getString("date"), dayStoryline);
                    }

                    for (String date : dayStorylines.keySet()) {
                        JSONObject dayStoryline = dayStorylines.get(date);
                        final Object segmentsObject = dayStoryline.get("segments");
                        if (segmentsObject!=null) {
                            JSONArray segments = new JSONArray();
                            if (segmentsObject instanceof JSONObject) {
                                if (((JSONObject) segmentsObject).isNullObject()) continue;
                                segments.add(segmentsObject);
                            } if (segmentsObject instanceof JSONArray) {
                                JSONArray tempSegments = (JSONArray) segmentsObject;
                                for (int i=0; i<tempSegments.size(); i++) {
                                    JSONObject nextSegment = tempSegments.getJSONObject(i);
                                    if (nextSegment.isNullObject()) continue;
                                    segments.add(nextSegment);
                                }
                            }
                            date = toStorageFormat(date);

                            boolean dateHasData=createOrUpdateDataForDate(updateInfo, segments, date);
                            // Save maxDateWithData only if there was data for this date
                            if(dateHasData && (maxDateWithData==null || maxDateWithData.compareTo(date)<0)) {
                                maxDateWithData = date;
                                saveMaxDateWithData(updateInfo, maxDateWithData);
                            }
                        }
                    }
                }
            }
        }
        catch (UpdateFailedException e) {
            // The update failed and whoever threw the error knew enough to have all the details.
            // Rethrow the error
            System.out.println("MOVES: guestId=" + updateInfo.getGuestId() + ", UPDATE FAILED");

            throw e;
        }
        catch (RateLimitReachedException e) {
            // We reached rate limit and whoever threw the error knew enough to have all the details.
            // Rethrow the error
            System.out.println("MOVES: guestId=" + updateInfo.getGuestId() + ", RATE LIMIT REACHED");

            throw e;
        }
        catch (Exception e) {
            StringBuilder sb = new StringBuilder("module=updateQueue component=updater action=MovesUpdater.getUserRegistrationDate")
                    .append(" message=\"exception while in createOrUpdateData\" connector=")
                    .append(updateInfo.apiKey.getConnector().toString()).append(" guestId=")
                    .append(updateInfo.apiKey.getGuestId())
                    .append(" stackTrace=<![CDATA[").append(Utils.stackTrace(e)).append("]]>");
            logger.info(sb.toString());

            System.out.println("MOVES: guestId=" + updateInfo.getGuestId() + ", UPDATE FAILED (don't know why)");

            // The update failed.  We don't know if this is permanent or temporary.
            // Throw the appropriate exception.
            throw new UpdateFailedException(e);
        }

    }

    // In the case that maxDateWithData is set to non-null and we're moving forward in time and we completed successfully,
    // record the maxDateWithData date to start with next time.  This has the unfortunate effect that we may end up
    // reading in the dates since the user last had access to wireless or since they gave up on Moves many
    // times.  The alternative would be to potentially fail to update a range of dates that don't have complete
    // data on the Moves server.  This may set updateDateKeyName to an earlier date than the place above where updateDateKeyName
    // is set.  This looks a bit strange, but it's the best I could come up with to both handle the
    // case where the Moves server doesn't have data for the most recent of days and where the
    // user registration date is set to way before the earliest real data.  In the former case, the above
    // set of updateDateKeyName will now be overridden with an earlier date.  In the latter case, the
    // above set or updateDateKeyName will stand as-is and continue moving forward on restarts until we have
    // seen a date that really has some data.  To prevent excessive updating in the case where a user
    // has given up on Moves and is really never going to update, limit the setback to a maximum of 7 days.
    private void saveMaxDateWithData(final UpdateInfo updateInfo, final String maxDateWithData) {
        if(maxDateWithData!=null) {
            String dateToStore = maxDateWithData;
            DateTime maxDateTimeWithData = TimeUtils.dateFormatterUTC.parseDateTime(maxDateWithData);
            DateTime nowMinusSevenDays = new DateTime().minusDays(7);
            if(maxDateTimeWithData.isBefore(nowMinusSevenDays)) {
                // maxDateWithData is too long ago.  Use 7 days ago instead.
                dateToStore = TimeUtils.dateFormatterUTC.print(nowMinusSevenDays);
                System.out.println("MOVES: guestId=" + updateInfo.getGuestId() + ", maxDateWithData=" + maxDateWithData + " < 7 days ago, using " + dateToStore);
            }
            else {
                System.out.println("MOVES: guestId=" + updateInfo.getGuestId() + ", storing maxDateWithData=" + maxDateWithData);
            }
            guestService.setApiKeyAttribute(updateInfo.apiKey, updateDateKeyName, dateToStore);
        }
    }

    List<List<String>> subListsOf(final List<String> filteredDates, final int n) {
        int nSublists = filteredDates.size()/n;
        List<List<String>> subLists = new ArrayList<List<String>>();
        for (int i=0; i<nSublists; i++) {
            List<String> subList = filteredDates.subList(i*n, (i+1)*n);
            subLists.add(subList);
        }
        int lastItems = filteredDates.size()%n;
        if (filteredDates.size()%n>0) {
            List<String> subList = filteredDates.subList(filteredDates.size() - lastItems, filteredDates.size());
            subLists.add(subList);
        }
        return subLists;
    }

    /**
     * Retrieves data that was updated since <code>updatedSinceParam</code> for 31 days before <code>fullUpdateStartDate</code>
     * and uses that data to fixup the data that we already have in store
     * @param fullUpdateStartDate
     * @param updateInfo
     */
    private void backwardFixupDataNoTrackPoints(final String fullUpdateStartDate, final UpdateInfo updateInfo) throws Exception {
        DateTime toDate = TimeUtils.dateFormatterUTC.parseDateTime(fullUpdateStartDate);
        DateTime fromDate = toDate.minusDays(30);
        final DateTime userRegistrationDate = TimeUtils.dateFormatterUTC.parseDateTime(getUserRegistrationDate(updateInfo));
        if (userRegistrationDate.isAfter(fromDate))
            fromDate = userRegistrationDate;
        try {
            // use lastSyncTime to reduce the data returned by this call to contain only stuff that has actually
            // been updated since last time we checked
            //String lastSyncTimeAtt = guestService.getApiKeyAttribute(updateInfo.apiKey, "lastSyncTime");
            //final long millis = ISODateTimeFormat.dateHourMinuteSecondFraction().withZoneUTC().parseDateTime(lastSyncTimeAtt).getMillis();
            //final String updatedSinceDate = localTimeStorageFormat.withZoneUTC().print(millis);
            String fetched = fetchStorylineForDates(updateInfo, TimeUtils.dateFormatterUTC.print(fromDate),
                                                    fullUpdateStartDate, false, null);
            if(fetched!=null) {
                // put the results in ascending order
                JSONArray storyline = JSONArray.fromObject(fetched);
                TreeMap<String, JSONObject> dayStorylines = new TreeMap<String,JSONObject>();
                for (int i=0;i<storyline.size();i++) {
                    JSONObject dayStoryline = storyline.getJSONObject(i);
                    dayStorylines.put(dayStoryline.getString("date"), dayStoryline);
                }

                for (String date : dayStorylines.keySet()) {
                    JSONObject dayStoryline = dayStorylines.get(date);
                    date = toStorageFormat(date);
                    final Object segmentsObject = dayStoryline.get("segments");
                    if (segmentsObject!=null) {
                        JSONArray segments = new JSONArray();
                        if (segmentsObject instanceof JSONObject) {
                            if (((JSONObject)segmentsObject).isNullObject()) continue;
                            segments.add(segmentsObject);
                        } if (segmentsObject instanceof JSONArray) {
                            JSONArray tempSegments = (JSONArray) segmentsObject;
                            for (int i=0; i<tempSegments.size(); i++) {
                                JSONObject nextSegment = tempSegments.getJSONObject(i);
                                if (nextSegment.isNullObject()) continue;
                                segments.add(nextSegment);
                            }
                        }
                        createOrUpdateDataForDate(updateInfo, segments, date);
                    }
                }
            }
        }
        catch (UpdateFailedException e) {
            // The update failed and whoever threw the error knew enough to have all the details.
            // Rethrow the error
            System.out.println("MOVES: guestId=" + updateInfo.getGuestId() + ", UPDATE FAILED");

            throw e;
        }
        catch (RateLimitReachedException e) {
            // We reached rate limit and whoever threw the error knew enough to have all the details.
            // Rethrow the error
            System.out.println("MOVES: guestId=" + updateInfo.getGuestId() + ", RATE LIMIT REACHED");

            throw e;
        }
        catch (Exception e) {
            StringBuilder sb = new StringBuilder("module=updateQueue component=updater action=MovesUpdater.getUserRegistrationDate")
                    .append(" message=\"exception while in createOrUpdateData\" connector=")
                    .append(updateInfo.apiKey.getConnector().toString()).append(" guestId=")
                    .append(updateInfo.apiKey.getGuestId())
                    .append(" stackTrace=<![CDATA[").append(Utils.stackTrace(e)).append("]]>");;
            logger.info(sb.toString());

            System.out.println("MOVES: guestId=" + updateInfo.getGuestId() + ", UPDATE FAILED (don't know why)");

            // The update failed.  We don't know if this is permanent or temporary.
            // Throw the appropriate exception.
            throw new UpdateFailedException(e);
        }
    }

    private String fetchStorylineForDates(final UpdateInfo updateInfo, final String fromDate, final String toDate,
                                          final boolean withTrackpoints, final String updatedSinceDate) throws Exception {
        long then = System.currentTimeMillis();
        String fetched;
        String compactFromDate = toCompactDateFormat(fromDate);
        String compactToDate = toCompactDateFormat(toDate);
        String fetchUrl = "not set yet";
        try {
            String accessToken = controller.getAccessToken(updateInfo.apiKey);
            fetchUrl = String.format(host + "/user/storyline/daily?from=%s&to=%s&trackPoints=%s",
                                            compactFromDate, compactToDate, withTrackpoints);
            if (updatedSinceDate!=null)
                fetchUrl += "&updatedSince=" + URLEncoder.encode(updatedSinceDate, "utf-8");
            fetchUrl += "&access_token=" + accessToken;
            fetched = fetchMovesAPI(updateInfo, fetchUrl);
            countSuccessfulApiCall(updateInfo.apiKey, updateInfo.objectTypes, then, fetchUrl);
        } catch (UnexpectedHttpResponseCodeException e) {
            countFailedApiCall(updateInfo.apiKey, updateInfo.objectTypes, then, fetchUrl, Utils.stackTrace(e),
                               e.getHttpResponseCode(), e.getHttpResponseMessage());

            // The update failed.  We don't know if this is permanent or temporary but
            // let's assume that it is permanent if it's our fault (4xx)
            if (e.getHttpResponseCode()>=400&&e.getHttpResponseCode()<500)
                throw new UpdateFailedException("Unexpected response code: " + e.getHttpResponseCode(), new Exception(), true,
                                                ApiKey.PermanentFailReason.clientError(e.getHttpResponseCode(), e.getHttpResponseMessage()));
            throw new UpdateFailedException(e);
        } catch (RateLimitReachedException e) {
            // Couldn't fetch storyline, rate limit reached
            countFailedApiCall(updateInfo.apiKey, updateInfo.objectTypes, then, fetchUrl, Utils.stackTrace(e),
                               429, "Rate limit reached");

            // Rethrow the rate limit reached exception
            throw e;


        } catch (IOException e) {
            countFailedApiCall(updateInfo.apiKey, updateInfo.objectTypes, then, fetchUrl, Utils.stackTrace(e), -1, "I/O");

            // The update failed.  We don't know if this is permanent or temporary.
            // Throw the appropriate exception.
            throw new UpdateFailedException(e);
        }
        return fetched;
    }

    // Return true if we are the first to update the quotaAvailableTime to the new value, and
    // false otherwise.  In the case that nextQuotaAvailableTime is in the future, the first instance to
    // update it has the responsibility to deal with rescheduling.  The other instances should wait or defer.
    private boolean tryUpdateQuotaAvailableTime(final long nextQuotaAvailableTime) {
        boolean retVal = false;

        // First check if we're obviously not the first to set the most up-to-date quota time.  We
        // don't need to lock quotaAvailableTime to do that since we'll check it again if we're
        // possibly the first
        if(quotaAvailableTime >= nextQuotaAvailableTime)
            return false;

        // We're potentially the first, check again inside a synchronized block.
        // If we're still the first, set quotaAvailableTime and return true.  If another
        // instance beat us, return false.
        synchronized (this) {
            if(quotaAvailableTime >= nextQuotaAvailableTime)
                return false;
            else {
                quotaAvailableTime = nextQuotaAvailableTime;
                return true;
            }

        }
    }

    private long getQuotaAvailableTime()
    {
        synchronized (this) {
            return(quotaAvailableTime);
        }
    }

    // Check if we would expect quota to be currently available for making a Moves API call.
    // If so, return 0.  If not, return the milliseconds between now and when we'd expect quota to
    // be available.  This isn't a guarantee that we won't run out of quota before the call happens,
    // it's just an optimization in the case where there's a current thread and a short quota delay so
    // we may avoid a 429/retry cycle
    private long getQuotaWaitTime() {
        long now = System.currentTimeMillis();
        long quotaAvailableIn = getQuotaAvailableTime()-now;
        if(quotaAvailableIn<0)
            return 0;
        return quotaAvailableIn;
    }

    // Generate a time that's randomly distributed through the hour after quotaAvailableTime
    // to do some load balancing
    private long getRandomRescheduleTime() {
        Random generator = new Random();
        long randomDelayMillis = (long)(generator.nextInt(DateTimeConstants.MILLIS_PER_HOUR));
        return getQuotaAvailableTime() + randomDelayMillis;
    }

    // Before call:  Check quotaAvailableTime to see if we can reasonably expect a call to succeed.
    // After call: Check for X-RateLimit-MinuteRemaining and X-RateLimit-HourRemaining to determine
    // if we need to change quotaAvailableTime.  If we try to change it and succeed, we should take care
    // of rescheduling.  If we try to change it and someone else beat us to it, we should just defer
    private String fetchMovesAPI(final UpdateInfo updateInfo, final String url)
            throws UnexpectedHttpResponseCodeException, UpdateFailedException, RateLimitReachedException, IOException {
        String content=null;
        HttpClient client = env.getHttpClient();

        // Check if we would expect to have enough quota to make this call
        long waitTime = getQuotaWaitTime();
        if (waitTime>0) {
            do {
                // We don't currently have quota available.  If it'll be available in < 1 minute, just wait.
                // Otherwise, quit and retry later
                if(waitTime > maxQuotaWaitMillis) {
                    // We're not willing to wait that long, reschedule
                    System.out.println(new StringBuilder().append("MOVES: guestId=").append(updateInfo.getGuestId()).append(", waitTime=").append(waitTime).append(", RESCHEDULING").toString());
                    // Set the reset time info in updateInfo so that we get scheduled for when the quota becomes available
                    // + a random number of minutes in the range of 0 to 60 to spread the load of lots of competing
                    // updaters across the next hour
                    updateInfo.setResetTime("moves", getRandomRescheduleTime());
                    throw new RateLimitReachedException();
                }
                // We are willing to wait that long
                System.out.println(new StringBuilder().append("MOVES: guestId=").append(updateInfo.getGuestId()).append(", waitTime=").append(waitTime).append(", WAITING").toString());

                try { Thread.currentThread().sleep(waitTime); }
                catch(Throwable e) {
                    e.printStackTrace();
                    throw new RuntimeException("Unexpected error waiting to enforce rate limits.");
                }
                waitTime = getQuotaWaitTime();
            } while (waitTime>0);
        }

        // By the time we get to here, we should likely have quota available
        try {
            HttpGet get = new HttpGet(url);

            HttpResponse response = client.execute(get);

            // Get the millisecond time of the next available bit of quota.  These fields will be populated for
            // status 200 or status 429 (over quota).  Other responses may not have them, in which case
            // responseToQuotaAvailableTime will return -1.  Ignore that case.
            long nextQuotaAvailableTime = responseToQuotaAvailableTime(updateInfo, response);

            // Update the quotaAvailableTime and check if we're the first to learn that we just blew quota
            boolean firstToUpdate = tryUpdateQuotaAvailableTime(nextQuotaAvailableTime);
            long now = System.currentTimeMillis();

            if(firstToUpdate && nextQuotaAvailableTime>now) {
                // We're the first to find out that quota is gone.  We may or may not have succeeded on this call,
                // depending on the status code.  Regardless of the status code, fix the scheduling of moves updates
                // that would otherwise happen before the next quota window opens up.
                List<UpdateWorkerTask> updateWorkerTasks = connectorUpdateService.getScheduledUpdateWorkerTasksForConnectorNameBeforeTime("moves", nextQuotaAvailableTime);
                for (int i=0; i<updateWorkerTasks.size(); i++) {
                    UpdateWorkerTask updateWorkerTask = updateWorkerTasks.get(i);
                    // Space the tasks 30 seconds apart so they don't all try to start at the same time
                    long rescheduleTime = nextQuotaAvailableTime + i*(DateTimeConstants.MILLIS_PER_SECOND*30);

                    // Update the scheduled execution time for any moves tasks that would otherwise happen during
                    // the current quota outage far enough into the future that we should have quota available by then.
                    // If there's more than one pending, stagger them by a few minutes so that they don't all try to
                    // happen at once.  The reason the "incrementRetries" arg is true is that that appears to be the
                    // way to prevent spawning a duplicate entry in the UpdateWorkerTask table.
                    connectorUpdateService.reScheduleUpdateTask(updateWorkerTask.getId(),
                                                                rescheduleTime,
                                                                true,null);

                    logger.info("module=movesUpdater component=fetchMovesAPI action=fetchMovesAPI" +
                                " message=\"Rescheduling due to quota limit: " +
                                updateWorkerTask + "\" newUpdateTime=" + rescheduleTime);
                }
            }


            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == HttpStatus.SC_OK) {
                ResponseHandler<String> responseHandler = new BasicResponseHandler();
                content = responseHandler.handleResponse(response);
            }
            else {
                // attempt to get a human-readable message
                String message = null;
                try {
                    message = IOUtils.toString(response.getEntity().getContent());
                } catch (Throwable t) {}
                if(statusCode == 401) {
                    // Unauthorized, so this is never going to work
                    // Notify the user that the tokens need to be manually renewed
                    notificationsService.addNamedNotification(updateInfo.getGuestId(), Notification.Type.WARNING, connector().statusNotificationName(),
                                                              "Heads Up. We failed in our attempt to update your Moves connector.<br>" +
                                                              "Please head to <a href=\"javascript:App.manageConnectors()\">Manage Connectors</a>,<br>" +
                                                              "scroll to the Moves connector, and renew your tokens (look for the <i class=\"icon-resize-small icon-large\"></i> icon)");
                    // Record permanent failure since this connector won't work again until
                    // it is reauthenticated
                    guestService.setApiKeyStatus(updateInfo.apiKey.getId(), ApiKey.Status.STATUS_PERMANENT_FAILURE, null, ApiKey.PermanentFailReason.NEEDS_REAUTH);
                    throw new UpdateFailedException("Unauthorized access", true, ApiKey.PermanentFailReason.NEEDS_REAUTH);
                }
                else if(statusCode == 429) {
                    // Over quota, so this API attempt didn't work
                    // Set the reset time info in updateInfo so that we get scheduled for when the quota becomes available
                    updateInfo.setResetTime("moves", getQuotaAvailableTime());
                    throw new RateLimitReachedException();
                }
                else if (statusCode>=400 && statusCode<500) {
                    String message40x = "Unexpected response code: " + statusCode;
                    if (message!=null) message40x += " message: " + message;
                    throw new UpdateFailedException(message40x, new Exception(), true, ApiKey.PermanentFailReason.clientError(statusCode, message));
                }
                else {
                    throw new UnexpectedHttpResponseCodeException(response.getStatusLine().getStatusCode(),
                                                                  response.getStatusLine().getReasonPhrase());
                }
            }
        }
        finally {
            client.getConnectionManager().shutdown();
        }
        return content;
    }

    // Check for Date, X-RateLimit-MinuteRemaining, and X-RateLimit-HourRemaining to determine
    // the quotaAvailableTime.  If X-RateLimit-MinuteRemaining and X-RateLimit-HourRemaining are
    // both > 0, then quotaAvailableTime is the start of the minute represented by the Date header.
    // If X-RateLimit-HourRemaining is zero, then quotaAvailableTime is the start of the next hour
    // after Date.  If X-RateLimit-HourRemaining is > 0 but X-RateLimit-MinuteRemaining is zero,
    // then quotaAvailableTime is the start of the minute after Date.
    // Returns -1 if there's a problem.
    private static long responseToQuotaAvailableTime(final UpdateInfo updateInfo, final HttpResponse response)
    {
        final Header[] dateHeader = response.getHeaders("Date");
        final Header[] minuteRemainingHeader = response.getHeaders("X-RateLimit-MinuteRemaining");
        final Header[] hourRemainingHeader = response.getHeaders("X-RateLimit-HourRemaining");

        DateTime headerDate = null;
        long retMillis=-1;

        if (dateHeader!=null&&dateHeader.length>0) {
            final String value = dateHeader[0].getValue();
            if (value!=null) {
                try {
                    headerDate = httpResponseDateFormat.parseDateTime(value);
                } catch(Throwable e) {
                    logger.warn("Could not parse Date Moves API header, its value is [" + value + "]");
                }
            }
        }

        if(headerDate==null) {
            return -1;
        }

        if (minuteRemainingHeader!=null&&minuteRemainingHeader.length>0 &&
            hourRemainingHeader!=null&&hourRemainingHeader.length>0) {
            final String minuteRemValue = minuteRemainingHeader[0].getValue();
            final String hourRemValue = hourRemainingHeader[0].getValue();
            if (minuteRemValue==null || hourRemValue==null) {
                return -1;
            }
            // Determine if either or both of the minute and hour quotas are gone
            // by comparing their values to "0"
            final boolean hourQuotaGone= hourRemValue.equals("0");
            final boolean minuteQuotaGone= minuteRemValue.equals("0");

            // At this point we know that minuteRemValue and hourRemValue have non-null values
            // Compute the top of the minute by setting the seconds of minute for
            // a copy of headerDate to zero
            DateTime topOfThisMinute = headerDate.secondOfMinute().setCopy(0);

            if(!minuteQuotaGone && !hourQuotaGone) {
                // We still have quota left for now, return topOfThisMinute
                retMillis = topOfThisMinute.getMillis();
            }
            else if(hourQuotaGone) {
                // We need to start again at the top of the next hour
                DateTime topOfThisHour = topOfThisMinute.minuteOfHour().setCopy(0);
                DateTime topOfNextHour = topOfThisHour.plusHours(1);
                retMillis = topOfNextHour.getMillis();
            }
            else {
                // We need to start again at the top of the next minute
                // However, experimentally it didn't reset quota until ~14 sec after the top of the next minute,
                // so pad forward by 20 seconds
                DateTime topOfNextMinutePlusPadding = topOfThisMinute.plusMinutes(1).plusSeconds(20);
                retMillis = topOfNextMinutePlusPadding.getMillis();
            }

            long now = System.currentTimeMillis();
            System.out.println(new StringBuilder().append("MOVES: guestId=").append(updateInfo.getGuestId()).append(", minuteRem=").append(minuteRemValue).append(", hourRem=").append(hourRemValue).append(", nextQuotaMillis=").append(retMillis).append(" (now=").append(now).append(", delta=").append(retMillis - now).append(")").toString());
        }

        return retMillis;
    }


    // getDatesSince takes argument and returns a list of dates in storage format (yyyy-mm-dd)
    private static List<String> getDatesSince(String fromDate) {
        List<String> dates = new ArrayList<String>();
        DateTime then = TimeUtils.dateFormatterUTC.parseDateTime(fromDate);
        // TODO: Today should be relative to the timezone the user is in rather than UTC
        // We could either use the Moves profile TZ or the metadata TZ.  It's not clear which
        // would be better.
        String today = TimeUtils.dateFormatterUTC.print(System.currentTimeMillis());
        DateTime todaysTime = TimeUtils.dateFormatterUTC.parseDateTime(today);
        if (then.isAfter(todaysTime))
            throw new IllegalArgumentException("fromDate is after today");
        while (!today.equals(fromDate)) {
            dates.add(fromDate);
            then = TimeUtils.dateFormatterUTC.parseDateTime(fromDate);
            String date = TimeUtils.dateFormatterUTC.print(then.plusDays(1));
            fromDate = date;
        }
        dates.add(today);
        return dates;
    }

    private static String toStorageFormat(String date) {
        DateTime then = compactDateFormat.withZoneUTC().parseDateTime(date);
        String storageDate = TimeUtils.dateFormatterUTC.print(then);
        return storageDate;
    }

    private String toCompactDateFormat(final String date) {
        DateTime then = TimeUtils.dateFormatterUTC.parseDateTime(date);
        String compactDate = compactDateFormat.withZoneUTC().print(then);
        return compactDate;
    }

    private String getMaxDateWithDataInDB(UpdateInfo updateInfo) {
        final String entityName = JPAUtils.getEntityName(MovesPlaceFacet.class);
        final List<MovesPlaceFacet> newest = jpaDaoService.executeQueryWithLimit(
                        "SELECT facet from " + entityName + " facet WHERE facet.apiKeyId=? ORDER BY facet.end DESC,facet.date DESC",
                        1,
                        MovesPlaceFacet.class, updateInfo.apiKey.getId());

        // If there are existing moves place facets, return the date of the most recent one.
        // If there are no existing moves place facets, return null
        String ret = null;
        if (newest.size()>0) {
            ret = newest.get(0).date;
            System.out.println("Moves: guestId=" + updateInfo.getGuestId() + ", maxDateInDB=" + ret);
        }
        else {
            System.out.println("Moves: guestId=" + updateInfo.getGuestId() + ", maxDateInDB=null");
        }
        return ret;
    }
    private boolean createOrUpdateDataForDate(final UpdateInfo updateInfo, final JSONArray segments,
                                           final String date) throws UpdateFailedException {
        // For a given date, iterate over the JSON array of segments returned by a call to the Moves API and
        // reconcile them with any previously persisted move and place facets for that date.
        // A given segment may be either of type move or place.  Either type has an overall
        // start and end time and may contain a list of activities.  A given segment is considered to match
        // a stored facet if the type matches (move vs place) and the start time and date match.  If a match is
        // found, the activities associated with that segment are reconciled.
        // Returns true if the date has a non empty set of data, false otherwise
        boolean dateHasData=false;

        // Check that segments and date are both non-null.  If so, continue, otherwise return false
        if(date==null || segments==null)
            return false;

        for (int j=0; j<segments.size(); j++) {
            JSONObject segment;
            try {
                segment = segments.getJSONObject(j);
            } catch (Throwable t) {
                logger.warn("null segment, apiKeyId: " + updateInfo.apiKey.getId() + ", date: " + date);
                continue;
            }
            if (segment.getString("type").equals("move")) {
                if(createOrUpdateMovesMoveFacet(date, segment, updateInfo)!=null)
                    dateHasData=true;
            } else if (segment.getString("type").equals("place")) {
                if(createOrUpdateMovesPlaceFacet(date, segment, updateInfo)!=null)
                    dateHasData=true;
            }
        }
        return dateHasData;
    }

    // For a given date and move segment JSON, either create or update the data for a move corresponding to that segment
    private MovesMoveFacet createOrUpdateMovesMoveFacet(final String date,final JSONObject segment, final UpdateInfo updateInfo)
        throws UpdateFailedException {
        try {
            final String startTimeString = segment.getString("startTime");
            final DateTime startTime = localTimeStorageFormat.parseDateTime(startTimeString);
            long start = startTime.getMillis();

            MovesMoveFacet ret =
                    apiDataService.createOrReadModifyWrite(MovesMoveFacet.class,
                                                           new ApiDataService.FacetQuery(
                                                                   "e.apiKeyId = ? AND e.date = ? AND e.start = ?",
                                                                   updateInfo.apiKey.getId(),
                                                                   date,
                                                                   start),
                                                           new ApiDataService.FacetModifier<MovesMoveFacet>() {
                                                               // Throw exception if it turns out we can't make sense of the observation's JSON
                                                               // This will abort the transaction
                                                               @Override
                                                               public MovesMoveFacet createOrModify(MovesMoveFacet facet, Long apiKeyId) {
                                                                   boolean needsUpdate = false;
                                                                   // Don't already have a MovesMoveFacet with this apiKeyId, start, and date.  Create one
                                                                   if (facet == null) {
                                                                       facet = new MovesMoveFacet(apiKeyId);
                                                                       facet.guestId = updateInfo.apiKey.getGuestId();
                                                                       facet.api = updateInfo.apiKey.getConnector().value();

                                                                       // Set the fields based on the JSON and create activities from scratch
                                                                       extractMoveData(date, segment, facet, updateInfo);
                                                                       needsUpdate=true;
                                                                   }
                                                                   else {
                                                                       // Already have a MovesMoveFacet with this apiKeyId, start, and date.
                                                                       // Just update from the segment info
                                                                       needsUpdate = tidyUpMoveFacet(segment, facet);
                                                                       needsUpdate |= tidyUpActivities(updateInfo, segment, facet);
                                                                       needsUpdate |= replaceManualActivities(updateInfo, segment, facet);
                                                                   }


                                                                   // If the facet changed set timeUpdated
                                                                   if(needsUpdate) {
                                                                       facet.timeUpdated = System.currentTimeMillis();
                                                                   }

                                                                   return facet;
                                                               }
                                                           }, updateInfo.apiKey.getId());
            return ret;

        }
        catch (Throwable e) {
            // Couldn't makes sense of the move's JSON
            StringBuilder sb = new StringBuilder("module=updateQueue component=updater action=MovesUpdater.createOrUpdateMovesMoveFacet")
                    .append(" message=\"exception while processing move segment\" connector=")
                    .append(updateInfo.apiKey.getConnector().toString()).append(" guestId=")
                    .append(updateInfo.apiKey.getGuestId())
                    .append(" stackTrace=<![CDATA[").append(Utils.stackTrace(e)).append("]]>");;
            logger.info(sb.toString());

            // The update failed.  We don't know if this is permanent or temporary.
            // Throw the appropriate exception.
            throw new UpdateFailedException(e);
        }
    }

    // For a given date and place segment JSON, either create or update the data for a place corresponding to that segment
    private MovesPlaceFacet createOrUpdateMovesPlaceFacet(final String date,final JSONObject segment, final UpdateInfo updateInfo)
        throws UpdateFailedException{
        try {
            final DateTime startTime = localTimeStorageFormat.parseDateTime(segment.getString("startTime"));
            final long start = startTime.getMillis();

            MovesPlaceFacet ret =
                    apiDataService.createOrReadModifyWrite(MovesPlaceFacet.class,
                                                           new ApiDataService.FacetQuery(
                                                                   "e.apiKeyId = ? AND e.date = ? AND e.start = ?",
                                                                   updateInfo.apiKey.getId(),
                                                                   date,
                                                                   start),
                                                           new ApiDataService.FacetModifier<MovesPlaceFacet>() {
                                                               // Throw exception if it turns out we can't make sense of the observation's JSON
                                                               // This will abort the transaction
                                                               @Override
                                                               public MovesPlaceFacet createOrModify(MovesPlaceFacet facet, Long apiKeyId) {
                                                                   boolean needsUpdate;
                                                                   if (facet == null) {
                                                                       facet = new MovesPlaceFacet(apiKeyId);
                                                                       facet.guestId = updateInfo.apiKey.getGuestId();
                                                                       facet.api = updateInfo.apiKey.getConnector().value();
                                                                       // Set the fields based on the JSON and create activities from scratch
                                                                       extractMoveData(date, segment, facet, updateInfo);
                                                                       extractPlaceData(segment, facet);
                                                                       needsUpdate=true;
                                                                   }
                                                                   else {
                                                                       // Already have a MovesMoveFacet with this apiKeyId, start, and date.
                                                                       // Just update from the segment info
                                                                       needsUpdate = tidyUpPlaceFacet(segment, facet);
                                                                       needsUpdate |= tidyUpActivities(updateInfo, segment, facet);
                                                                       needsUpdate |= replaceManualActivities(updateInfo, segment, facet);
                                                                   }

                                                                   // If the facet changed set timeUpdated
                                                                   if(needsUpdate) {
                                                                       facet.timeUpdated = System.currentTimeMillis();
                                                                   }
                                                                   return facet;
                                                               }
                                                           }, updateInfo.apiKey.getId());
            return ret;

        } catch (Throwable e) {
            // Couldn't makes sense of the move's JSON
            StringBuilder sb = new StringBuilder("module=updateQueue component=updater action=MovesUpdater.createOrUpdateMovesPlaceFacet")
                    .append(" message=\"exception while processing place segment\" connector=")
                    .append(updateInfo.apiKey.getConnector().toString()).append(" guestId=")
                    .append(updateInfo.apiKey.getGuestId())
                    .append(" stackTrace=<![CDATA[").append(Utils.stackTrace(e)).append("]]>");;
            logger.info(sb.toString());

            // The update failed.  We don't know if this is permanent or temporary.
            // Throw the appropriate exception.
            throw new UpdateFailedException(e);
        }
    }

    /**
     * Since there is no way to uniquely identify manual activities, here we bluntly replace all possibly existing such
     * activities with the new ones, if any. If none pre-existed and no new manual activities were created, this method
     * is a no-op and return false.
     * @param updateInfo
     * @param segment
     * @param parentFacet
     * @return
     */
    private boolean replaceManualActivities(final UpdateInfo updateInfo, final JSONObject segment, final MovesFacet parentFacet) {
        final List<MovesActivity> movesActivities = parentFacet.getActivities();
        if (!segment.has("activities"))
            return false;
        List<MovesActivity> oldManualActivities = new ArrayList<MovesActivity>();
        for (MovesActivity movesActivity : movesActivities) {
            if (movesActivity.manual) {
                oldManualActivities.add(movesActivity);
            }
        }
        boolean hasOldManualActivities = oldManualActivities.size()>0;
        for (MovesActivity oldManualActivity : oldManualActivities)
            movesActivities.remove(oldManualActivity);

        final JSONArray activities = segment.getJSONArray("activities");
        boolean hasNewManualActivities = false;
        for (int i=0; i<activities.size(); i++) {
            JSONObject jsonActivity = activities.getJSONObject(i);
            if (jsonActivity.getBoolean("manual")) {
                hasNewManualActivities = true;
                final MovesActivity activity = extractActivity(parentFacet.date,updateInfo, jsonActivity);
                parentFacet.addActivity(activity);
            }
        }
        return hasOldManualActivities||hasNewManualActivities;
    }

    private boolean tidyUpActivities(final UpdateInfo updateInfo, final JSONObject segment, final MovesFacet parentFacet) {
        // Reconcile the stored activities associated with a given parent facet with the contents of a corresponding json
        // object returned by the Moves API.  Returns true if the facet needs update, and false otherwise.
        final List<MovesActivity> movesActivities = parentFacet.getActivities();
        if (!segment.has("activities"))
            return false;
        final JSONArray activities = segment.getJSONArray("activities");
        boolean needsUpdate = false;
        // identify missing activities and add them to the facet's activities
        needsUpdate |= addMissingActivities(updateInfo, movesActivities, activities, parentFacet);
        // identify activities that have been removed
        needsUpdate |= removeActivities(movesActivities, activities, parentFacet);
        // finally, update activities that need it
        needsUpdate|=updateActivities(updateInfo, movesActivities, activities);
        return(needsUpdate);
    }

    // This function compares start times between a list of stored moves activity facets and entries in a JSON array
    // of activity objects returned by the Moves API.  Any items in the list of stored facets which do not have a
    // corresponding item in the JSON array with a matching start time are removed.
    // 2014/03/13: Candide modified so it only affects non-manual entries + return true if modifications occured
    private boolean removeActivities(final List<MovesActivity> movesActivities, final JSONArray activities, final MovesFacet facet) {
        boolean needsUpdate = false;
        withMovesActivities:for (int i=0; i<movesActivities.size(); i++) {
            final MovesActivity movesActivity = movesActivities.get(i);
            for (int j=0; j<activities.size(); j++) {
                JSONObject activityData = activities.getJSONObject(j);
                if (activityData.getBoolean("manual"))
                    continue;
                final long start = localTimeStorageFormat.parseDateTime(activityData.getString("startTime")).getMillis();
                if (movesActivity.start==start) {
                    continue withMovesActivities;
                }
            }
            facet.removeActivity(movesActivity);
            needsUpdate = true;
        }
        return needsUpdate;
    }

    // This function reconciles a list of moves activity facets with a JSON array of activity objects returned by the
    // Moves API.  This assumes that the lists are of the same length and have matching startTimes.
    // Returns true if any modifications are made and false otherwise.
    private boolean updateActivities(final UpdateInfo updateInfo, final List<MovesActivity> movesActivities, final JSONArray activities) {
        boolean needsUpdate = false;
        // Loop over the activities JSON array returned by a recent API call to check if each has a corresponding
        // stored activity facet.  Consider a given stored activity facet and JSON item to match if their
        // start times are the same.
        for (int i=0; i<activities.size(); i++) {
            // Loop over the stored facets in movesActivities to make sure that they take into account
            // jsonActivity.  Consider a given stored activity facet and JSON item to match if
            // their start times are the same.
            JSONObject jsonActivity = activities.getJSONObject(i);
            for (int j=0; j<movesActivities.size(); j++) {
                if (jsonActivity.getBoolean("manual"))
                    continue;
                final long start = localTimeStorageFormat.parseDateTime(jsonActivity.getString("startTime")).getMillis();
                final MovesActivity storedActivityFacet = movesActivities.get(j);
                if (storedActivityFacet.start==start) {
                    // Here we know that the storedActivityFacet and jsonActivity started at the same time.
                    // Check that they end at the same time and have the same type and auxilliary data.
                    needsUpdate|=updateActivity(updateInfo, storedActivityFacet, jsonActivity);
                    continue;
                }

            }
        }
        return needsUpdate;
    }

    // This function reconciles a given moves activity facet with a JSON activity objects returned by the
    // Moves API.  This assumes that the args have already been confirmed to have matching startTimes.
    // Returns true if any modifications are made and false otherwise.
    private boolean updateActivity(final UpdateInfo updateInfo,
                                   final MovesActivity movesActivity,
                                   final JSONObject activityData) {
        boolean needsUpdate = false;
        final long end = localTimeStorageFormat.parseDateTime(activityData.getString("endTime")).getMillis();
        if (movesActivity.end!=end) {
            needsUpdate = true;
            movesActivity.endTimeStorage = AbstractLocalTimeFacet.timeStorageFormat.print(end);
            movesActivity.end = end;
        }

        final String activity = activityData.getString("activity");
        if (!movesActivity.activity.equals(activity)) {
            needsUpdate = true;
            movesActivity.activity = activity;
        }

        if (activityData.has("group")) {
            final String activityGroup = activityData.getString("group");
            if (movesActivity.activityGroup==null||
                (!movesActivity.activityGroup.equals(activityGroup))) {
                needsUpdate = true;
                movesActivity.activityGroup = activityGroup;
            }
        } else if (movesActivity.activityGroup!=null) {
            movesActivity.activityGroup = null;
            needsUpdate = true;
        }

        final String manual = activityData.getString("manual");
        if (!movesActivity.manual.equals(manual)) {
            needsUpdate = true;
            movesActivity.manual = new Boolean(manual);
        }

        if ((activityData.has("steps")&&movesActivity.steps==null)||
            (activityData.has("steps")&&movesActivity.steps!=activityData.getInt("steps"))) {
            needsUpdate = true;
            movesActivity.steps = activityData.getInt("steps");
        }
        if (activityData.has("distance")&&
            activityData.getInt("distance")!=movesActivity.distance) {
            needsUpdate = true;
            movesActivity.distance = activityData.getInt("distance");
        }
        if (activityData.has("trackPoints")) {
            if (movesActivity.activityURI!=null) {
                // Anne: I removed the check since there should be no problem with inserting
                // location points which already exist.  TODO: see if we can do better here

                // note: we don't needs to set needsUpdate to true here as the location data is
                // not stored with the facet per se, it is stored separately in the LocationFacets table
                //final long stored = jpaDaoService.executeCount("SELECT count(facet) FROM " +
                //                                          JPAUtils.getEntityName(LocationFacet.class) +
                //                                          " facet WHERE facet.source=" + LocationFacet.Source.MOVES.ordinal() +
                //                                          " AND facet.uri='" + movesActivity.activityURI + "'");
                //final JSONArray trackPoints = activityData.getJSONArray("trackPoints");
                //if (stored!=trackPoints.size()) {
                //    jpaDaoService.execute("DELETE facet FROM " +
                //                          JPAUtils.getEntityName(LocationFacet.class) +
                //                          " facet WHERE facet.source=" + LocationFacet.Source.MOVES +
                //                          " AND facet.uri='" + movesActivity.activityURI + "'");
                    extractTrackPoints(movesActivity.activityURI, activityData, updateInfo);
                //}
            } else {
                needsUpdate = true; // adding an activityURI means an update is needed
                // Generate a URI of the form '{wlk,cyc,trp}/UUID'.  The activity field must be set before calling createActivityURI
                movesActivity.activityURI = createActivityURI(movesActivity);
                extractTrackPoints(movesActivity.activityURI, activityData, updateInfo);
            }
        }
        return needsUpdate;
    }

    private String createActivityURI(final MovesActivity movesActivity) {
        // Generate a URI of the form '{wlk,cyc,trp}/UUID'.  The activity field must be set before calling createActivityURI
        if(movesActivity.activity!=null) {
            return(movesActivity.activity + "/" + UUID.randomUUID().toString());
        }
        else {
            return null;
        }
    }

    // 2014/03/13: Candide modified so it only affects non-manual entries + return true if modifications occured
    private boolean addMissingActivities(final UpdateInfo updateInfo,
                                      final List<MovesActivity> movesActivities,
                                      final JSONArray activities,
                                      final MovesFacet parentFacet) {
        // Loop over the activities JSON array returned by a recent API call to check if each has a corresponding
        // stored activity facet.  Consider a given stored activity facet and JSON item to match if their
        // start times are the same.
        boolean needsUpdate = false;
        withApiActivities:for (int i=0; i<activities.size(); i++) {
            JSONObject jsonActivity = activities.getJSONObject(i);

            // Loop over the stored facets in movesActivities to make sure that they take into account
            // jsonActivity.  Consider a given stored activity facet and JSON item to match if
            // their start times are the same.
            for (int j=0; j<movesActivities.size(); j++) {
                if (jsonActivity.getBoolean("manual"))
                    continue;
                final long start = localTimeStorageFormat.parseDateTime(jsonActivity.getString("startTime")).getMillis();
                MovesActivity storedActivityFacet = movesActivities.get(j);
                if (storedActivityFacet.start==start) {
                    // Here we know that storedActivityFacet and jsonActivity started at the same time.
                    // A later call to updateActivities will check that they end at the same time,
                    // have the same type and auxilliary data.  Don't worry about that here.
                    continue withApiActivities;
                }
            }

            // There was no stored activity facet matching the same startTime as jsonActivity.  Extract
            // the fields from jsonActivityfrom into a new facet and add it to our parent facet.
            // Use the same date for the activities as is stored with the parent.  This is the date
            // used to make the request to the Moves API.
            final MovesActivity activity = extractActivity(parentFacet.date,updateInfo, jsonActivity);
            parentFacet.addActivity(activity);
            needsUpdate = true;
        }
        return needsUpdate;
    }

    @Transactional(readOnly=false)
    private boolean tidyUpPlaceFacet(final JSONObject segment, final MovesPlaceFacet place) {
        boolean needsUpdating = false;

        // Check for change in the end time
        final DateTime endTime = localTimeStorageFormat.parseDateTime(segment.getString("endTime"));
        if(place.end != endTime.getMillis()) {
            //System.out.println(place.start + ": endTime changed");
            needsUpdating = true;
            place.end = endTime.getMillis();
        }

        // Check for change in the place data
        JSONObject placeData = segment.getJSONObject("place");
        if (placeData.has("id")&&place.placeId==null) {
            //System.out.println(place.start + ": now the place has an id");
            needsUpdating = true;
            place.placeId = placeData.getLong("id");
        }
        // update the place type
        String previousPlaceType = place.type;
        if (!placeData.getString("type").equals(place.type)) {
            //System.out.println(place.start + ": place type has changed");
            needsUpdating = true;
            place.type = placeData.getString("type");
        }

        if (placeData.has("name")&&
            (place.name==null || !place.name.equals(placeData.getString("name")))) {
            //System.out.println(place.start + ": place name has changed");
            needsUpdating = true;
            place.name = placeData.getString("name");
        }

        if (placeData.has("foursquareId")&&place.foursquareId!=null&&
            !placeData.getString("foursquareId").equals(place.foursquareId)) {
            place.foursquareId = placeData.getString("foursquareId");
            needsUpdating = true;
        }

        // if the place wasn't identified previously, store its fourquare info now
        if (!previousPlaceType.equals("foursquare")&&
            placeData.getString("type").equals("foursquare")){
            //System.out.println(place.start + ": storing foursquare info");
            needsUpdating = true;
            place.foursquareId = placeData.getString("foursquareId");
        }

        // if the place had wrongly been identified previously and was manually
        // set to be home/work, set its foursquare id to null
        if (previousPlaceType.equals("foursquare")&&
            !placeData.getString("type").equals("foursquare")){
            needsUpdating = true;
            place.foursquareId = null;
        }

        JSONObject locationData = placeData.getJSONObject("location");
        float lat = (float) locationData.getDouble("lat");
        float lon = (float) locationData.getDouble("lon");
        if (Math.abs(lat-place.latitude)>0.0001 || Math.abs(lon-place.longitude)>0.0001) {
            //System.out.println(place.start + ": lat/lon have changed");
            needsUpdating = true;
            place.latitude = lat;
            place.longitude = lon;
        }
        return (needsUpdating);
    }

    @Transactional(readOnly=false)
    private boolean tidyUpMoveFacet(final JSONObject segment, final MovesFacet moveFacet) {
        boolean needsUpdating = false;

        // Check for change in the end time
        final DateTime endTime = localTimeStorageFormat.parseDateTime(segment.getString("endTime"));
        if(moveFacet.end != endTime.getMillis()) {
            needsUpdating = true;
            moveFacet.end = endTime.getMillis();
        }

        return(needsUpdating);
    }

    private void extractPlaceData(final JSONObject segment, final MovesPlaceFacet facet) {
        JSONObject placeData = segment.getJSONObject("place");
        if (placeData.has("id"))
            facet.placeId = placeData.getLong("id");
        facet.type = placeData.getString("type");
        if (placeData.has("name"))
            facet.name = placeData.getString("name");
        else {
            // ask google
        }
        if (facet.type.equals("foursquare"))
            facet.foursquareId = placeData.getString("foursquareId");
        JSONObject locationData = placeData.getJSONObject("location");
        facet.latitude = (float) locationData.getDouble("lat");
        facet.longitude = (float) locationData.getDouble("lon");
    }

    private void extractMoveData(final String date, final JSONObject segment, final MovesFacet facet, UpdateInfo updateInfo) {
        facet.date = date;
        // The times given by Moves are absolute GMT, not local time
        final DateTime startTime = localTimeStorageFormat.parseDateTime(segment.getString("startTime"));
        final DateTime endTime = localTimeStorageFormat.parseDateTime(segment.getString("endTime"));
        facet.start = startTime.getMillis();
        facet.end = endTime.getMillis();
        facet.date=date;
        extractActivities(date, segment, facet, updateInfo);
    }

    private void extractActivities(final String date, final JSONObject segment, final MovesFacet facet, UpdateInfo updateInfo) {
        if (!segment.has("activities"))
            return;
        final JSONArray activities = segment.getJSONArray("activities");
        for (int i=0; i<activities.size(); i++) {
            JSONObject activityData = activities.getJSONObject(i);
            MovesActivity activity = extractActivity(date, updateInfo, activityData);
            facet.addActivity(activity);
        }
    }

    private MovesActivity extractActivity(final String date, final UpdateInfo updateInfo, final JSONObject activityData) {
        MovesActivity activity = new MovesActivity();
        activity.activity = activityData.getString("activity");
        // Generate a URI of the form '{wlk,cyc,trp}/UUID'.  The activity field must be set before calling createActivityURI
        activity.activityURI = createActivityURI(activity);

        if (activityData.has("startTime") && activityData.has("endTime")) {
            final DateTime startTime = localTimeStorageFormat.parseDateTime(activityData.getString("startTime"));
            final DateTime endTime = localTimeStorageFormat.parseDateTime(activityData.getString("endTime"));

            // Note that unlike everywhere else in the sysetm, startTimeStorage and endTimeStorage here are NOT local times.
            // They are in GMT.
            activity.startTimeStorage = AbstractLocalTimeFacet.timeStorageFormat.print(startTime);
            activity.endTimeStorage = AbstractLocalTimeFacet.timeStorageFormat.print(endTime);

            activity.start = startTime.getMillis();
            activity.end = endTime.getMillis();
        }

        if (activityData.has("group"))
            activity.activityGroup = activityData.getString("group");

        if (activityData.has("manual"))
            activity.manual = activityData.getBoolean("manual");

        if (activityData.has("duration"))
            activity.duration = activityData.getInt("duration");

        // The date we use here is the date which we used to request this activity from the Moves API
        activity.date = date;

        if (activityData.has("steps"))
            activity.steps = activityData.getInt("steps");
        if (activityData.has("distance"))
            activity.distance = activityData.getInt("distance");

        extractTrackPoints(activity.activityURI, activityData, updateInfo);
        return activity;
    }

    private void extractTrackPoints(final String activityId, final JSONObject activityData, UpdateInfo updateInfo) {
        // Check if we actually have trackPoints in this activity data.  If not, return now.
        if(!activityData.has("trackPoints")) {
            return;
        }
        final JSONArray trackPoints = activityData.getJSONArray("trackPoints");
        List<LocationFacet> locationFacets = new ArrayList<LocationFacet>();
        // timeZone is computed based on first location for each batch of trackPoints
        Connector connector = Connector.getConnector("moves");
        for (int i=0; i<trackPoints.size(); i++) {
            JSONObject trackPoint = trackPoints.getJSONObject(i);
            LocationFacet locationFacet = new LocationFacet(updateInfo.apiKey.getId());
            locationFacet.latitude = (float) trackPoint.getDouble("lat");
            locationFacet.longitude = (float) trackPoint.getDouble("lon");
            // The two lines below would calculate the timezone if we cared, but the
            // timestamps from Moves are already in GMT, so don't mess with the timezone
            //if (timeZone==null)
            //    timeZone = metadataService.getTimeZone(locationFacet.latitude, locationFacet.longitude);
            final DateTime time = localTimeStorageFormat.parseDateTime(trackPoint.getString("time"));
            locationFacet.timestampMs = time.getMillis();
            locationFacet.api = connector.value();
            locationFacet.start = locationFacet.timestampMs;
            locationFacet.end = locationFacet.timestampMs;
            locationFacet.source = LocationFacet.Source.MOVES;
            locationFacet.apiKeyId = updateInfo.apiKey.getId();
            locationFacet.uri = activityId;
            locationFacets.add(locationFacet);
        }
        apiDataService.addGuestLocations(updateInfo.getGuestId(), locationFacets);
    }


}

