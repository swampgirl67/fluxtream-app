package org.fluxtream.connectors.up;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.fluxtream.core.domain.AbstractFacet;
import org.fluxtream.core.domain.ApiKey;
import org.fluxtream.core.domain.ChannelMapping;
import org.fluxtream.core.services.impl.BodyTrackHelper;
import org.fluxtream.core.services.impl.FieldHandler;
import net.sf.json.JSONArray;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * User: candide
 * Date: 12/02/14
 * Time: 10:25
 */
@Component("upMovesIntensity")
public class JawboneUpMovesIntensityFieldHandler implements FieldHandler {

    @Autowired
    BodyTrackHelper bodyTrackHelper;

    @Override
    public List<BodyTrackHelper.BodyTrackUploadResult> handleField(final ApiKey apiKey, final AbstractFacet facet) {
        JawboneUpMovesFacet movesFacet = (JawboneUpMovesFacet) facet;
        if (movesFacet.intensityStorage==null|| StringUtils.isEmpty(movesFacet.intensityStorage))
            return null;
        JSONArray intensityJson = JSONArray.fromObject(movesFacet.intensityStorage);
        List<List<Object>> data = new ArrayList<List<Object>>();
        for(int i=0; i<intensityJson.size(); i++) {
            JSONArray record = intensityJson.getJSONArray(i);
            final long when = record.getLong(0);
            final double intensity = record.getDouble(1);
            List<Object> intensityRecord = new ArrayList<Object>();
            intensityRecord.add(when);
            intensityRecord.add(intensity);
            data.add(intensityRecord);
        }
        final List<String> channelNames = Arrays.asList("intensity");

        // TODO: check the status code in the BodyTrackUploadResult
        return Arrays.asList(bodyTrackHelper.uploadToBodyTrack(apiKey, "Jawbone_UP", channelNames, data));
    }

    @Override
    public void addToDeclaredChannelMappings(final ApiKey apiKey, final List<ChannelMapping> channelMappings) {
        ChannelMapping.addToDeclaredMappings(apiKey, 2, apiKey.getConnector().getDeviceNickname(), "intensity", channelMappings);
    }

}
