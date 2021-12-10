
import java.util.Collections;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import com.api.clearance.model.Passenger;
import com.api.clearance.value.tac.DynamicData;
import com.api.clearance.value.tac.TACData;
import com.api.clearance.value.tac.TACResponse;
import com.api.clearance.commons.CustomRestTemplate;
import com.api.clearance.commons.NameMatchValidator;

import lombok.extern.slf4j.Slf4j;

/**
 * Client connects with TAC for verifying the health clearance certificate
 */
@Component
@Slf4j
public class TACClient {

    // Intentionally left empty to not expose TAC response details
    private static final String TAC_API_RESPONSE_STATUS_VALUE_VALID = ""; 
    private static final String TAC_API_DCC_TRACK_DATA_IDENTIFIER = "";

    private final CustomRestTemplate restTemplate;
    private final Environment environment;

    /**
     * Initializes TAC client
     *
     * @param customRestTemplate the custom rest template
     * @param environment the environment for getting property values
     */
    @Autowired
    public TACClient(CustomRestTemplate customRestTemplate, Environment environment) {
        // CustomRestTemplate is the extenstion of org.springframework.web.client.RestTemplate with required proxy configuration
        this.restTemplate = customRestTemplate;
        this.environment = environment;
    }

    /**
     * Verify Health Clearance for given passenger
     *
     * @param track the track
     * @param bookingPassenger the booking passenger
     * @param controlDate the date, set to first segment's departure date
     * @return true if health clearance is verified
     */
    public boolean verifyHealthClearance(String track, Passenger bookingPassenger, DateTime controlDate) {
        boolean validTrackDataForGivenPassenger;
        try {
            TACResponse tacResponse = restTemplate.postForObject(getUrl(track, controlDate), formHttpHeaders(), track, TACResponse.class);
            validTrackDataForGivenPassenger = isValidTrackDataForGivenPassenger(tacResponse, bookingPassenger);
        }
        catch (HttpClientErrorException exception) {
            log.error("TAC:: Exception occurred while verifying health clearance, status code is {}", exception.getStatusCode());
            throw exception;
        }
        return validTrackDataForGivenPassenger;
    }

    /**
     * Method to check whether track data is valid for given passenger
     *
     * @param tacResponse the tacResponse
     * @param passenger the passenger
     * @return true if track data is valid
     */
    private boolean isValidTrackDataForGivenPassenger(TACResponse tacResponse, Passenger passenger) {
        boolean valid = false;
        if (isValidResponse(tacResponse)) {
            valid = verifyName(tacResponse, passenger);
        }
        else {
            passenger.setError("HEALTH_CLEARANCE_CERTIFICATE_NOT_VALID");
        }
        return valid;
    }

    /**
     * Method to check whether tac response is valid or not
     *
     * @param tacResponse the tacResponse
     * @return true if tac response is valid
     */
    private static boolean isValidResponse(TACResponse tacResponse) {
        boolean valid = false;
        if (tacResponse != null && tacResponse.getData() != null && CollectionUtils.isNotEmpty(tacResponse.getData().getDynamicDataList())) {
            TACData tacResponseData = tacResponse.getData();
            DynamicData dynamicData = tacResponseData.getDynamicDataList().get(0);
            boolean dataStatusValid = false;
            if (dynamicData != null) {
                dataStatusValid = TAC_API_RESPONSE_STATUS_VALUE_VALID.equals(dynamicData.getLiteValidityStatus());
            }
            valid = dataStatusValid;
        }
        return valid;
    }

    /**
     * Call and verify name of the passenger against name matching validation
     *
     * @param tacResponse the tac response
     * @param passenger the domain passenger
     * @return valid the boolean returned based on the output of name matching validation
     */
    private boolean verifyName(TACResponse tacResponse, Passenger passenger) {
        boolean valid = false;
        try {
            DynamicData dynamicData = tacResponse.getData().getDynamicDataList().get(0);
            String bookingFirstName = passenger.getFirstName();
            String bookingLastName = passenger.getLastName();
            String tacFirstName = dynamicData.getLiteFirstName();
            String tacLastName = dynamicData.getLiteLastName();
            valid = NameMatchValidator.validate(tacFirstName, tacLastName, bookingFirstName, bookingLastName);
        }
        catch (Exception exception) {
            log.error("Name Matching validation - Exception occurred while verifying name of passenger with certificate name {}", exception);
        }
        if (!valid) {
            passenger.setError("NAME_MATCHING_VALIDATION_FAILED");
        }
        return valid;
    }

    /**
     * Gets the url based on the track
     *
     * @param track the track
     * @param controlDate the controlDate
     * @return url to access the TAC api
     */
    private String getUrl(String track, DateTime controlDate) {
        // TAC API endpoints are maintained in admin config, for 2 reasons 
        // 1) Configurable per environment. Different values from pre-prod & prod 
        // 2) If any changes there, that can be achived without any deployment
        String tacUrl = StringUtils.startsWithIgnoreCase(track, TAC_API_DCC_TRACK_DATA_IDENTIFIER) ? environment.getProperty("tac.dcc.endpoint") : environment.getProperty("tac.2ddoc.endpoint");
        return controlDate != null ? tacUrl + "?controlDate=" + controlDate.toString("yyyy-MM-dd'T'hh:mm:ss") : tacUrl;
    }

    /**
     * Prepare HTTP headers
     *
     * @return the HTTP headers
     */
    private HttpHeaders formHttpHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        // Auth token header is intentionally removed, to not expose these auth mechanism
        return headers;
    }
}
