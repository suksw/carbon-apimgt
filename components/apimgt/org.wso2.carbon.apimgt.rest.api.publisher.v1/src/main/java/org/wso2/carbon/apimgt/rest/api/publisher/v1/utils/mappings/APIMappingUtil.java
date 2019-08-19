/*
 *
 *  Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.wso2.carbon.apimgt.rest.api.publisher.v1.utils.mappings;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.wso2.carbon.apimgt.api.APIDefinitionValidationResponse;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.APIProvider;
import org.wso2.carbon.apimgt.api.ErrorHandler;
import org.wso2.carbon.apimgt.api.WorkflowStatus;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.APIProduct;
import org.wso2.carbon.apimgt.api.model.APIProductIdentifier;
import org.wso2.carbon.apimgt.api.model.APIProductResource;
import org.wso2.carbon.apimgt.api.model.APIStatus;
import org.wso2.carbon.apimgt.api.model.APIStateChangeResponse;
import org.wso2.carbon.apimgt.api.model.CORSConfiguration;
import org.wso2.carbon.apimgt.api.model.Label;
import org.wso2.carbon.apimgt.api.model.LifeCycleEvent;
import org.wso2.carbon.apimgt.api.model.ResourcePath;
import org.wso2.carbon.apimgt.api.model.Scope;
import org.wso2.carbon.apimgt.api.model.Tier;
import org.wso2.carbon.apimgt.api.model.URITemplate;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIMRegistryServiceImpl;
import org.wso2.carbon.apimgt.impl.definitions.APIDefinitionFromOpenAPISpec;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.rest.api.publisher.v1.dto.*;
import org.wso2.carbon.apimgt.rest.api.publisher.v1.dto.APIProductDTO.StateEnum;
import org.wso2.carbon.apimgt.rest.api.publisher.v1.dto.ScopeBindingsDTO;
import org.wso2.carbon.apimgt.rest.api.util.RestApiConstants;
import org.wso2.carbon.apimgt.rest.api.util.dto.ErrorDTO;
import org.wso2.carbon.apimgt.rest.api.util.utils.RestApiUtil;
import org.wso2.carbon.governance.custom.lifecycles.checklist.util.CheckListItem;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.sql.Timestamp;

import static org.wso2.carbon.apimgt.impl.utils.APIUtil.handleException;

public class APIMappingUtil {

    private static final Log log = LogFactory.getLog(APIMappingUtil.class);

    public static API fromDTOtoAPI(APIDTO dto, String provider) throws APIManagementException {

        String providerEmailDomainReplaced = APIUtil.replaceEmailDomain(provider);

        // The provider name that is coming from the body is not honored for now.
        // Later we can use it by checking admin privileges of the user.
        APIIdentifier apiId = new APIIdentifier(providerEmailDomainReplaced, dto.getName(), dto.getVersion());
        API model = new API(apiId);

        String context = dto.getContext();
        final String originalContext = context;

        if (context.endsWith("/" + RestApiConstants.API_VERSION_PARAM)) {
            context = context.replace("/" + RestApiConstants.API_VERSION_PARAM, "");
        }

        context = context.startsWith("/") ? context : ("/" + context);
        String providerDomain = MultitenantUtils.getTenantDomain(provider);
        if (!MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equalsIgnoreCase(providerDomain)) {
            //Create tenant aware context for API
            context = "/t/" + providerDomain + context;
        }

        // This is to support the pluggable version strategy
        // if the context does not contain any {version} segment, we use the default version strategy.
        context = checkAndSetVersionParam(context);
        model.setContextTemplate(context);

        context = updateContextWithVersion(dto.getVersion(), originalContext, context);
        model.setContext(context);
        model.setDescription(dto.getDescription());

        ObjectMapper mapper = new ObjectMapper();
        try {
            model.setEndpointConfig(mapper.writeValueAsString(dto.getEndpointConfig()));
        } catch (IOException e) {
            handleException("Error while converting endpointConfig to json", e);
        }

        model.setImplementation(dto.getEndpointImplementationType().toString());
        model.setWsdlUrl(dto.getWsdlUri());
        model.setType(dto.getType().toString());
        if (dto.getLifeCycleStatus() != null) {
            model.setStatus((dto.getLifeCycleStatus() != null) ? dto.getLifeCycleStatus().toUpperCase() : null);
        }
        if (dto.isIsDefaultVersion() != null) {
            model.setAsDefaultVersion(dto.isIsDefaultVersion());
        }
        if (dto.isEnableSchemaValidation() != null) {
            model.setEnableSchemaValidation(dto.isEnableSchemaValidation());
        }
        model.setResponseCache(dto.getResponseCaching());
        if (dto.getCacheTimeout() != null) {
            model.setCacheTimeout(dto.getCacheTimeout());
        } else {
            model.setCacheTimeout(APIConstants.API_RESPONSE_CACHE_TIMEOUT);
        }

/*        if (dto.getSequences() != null) { todo
            List<SequenceDTO> sequences = dto.getSequences();

            //validate whether provided sequences are available
            for (SequenceDTO sequence : sequences) {
                if (APIConstants.API_CUSTOM_SEQUENCE_TYPE_IN.equalsIgnoreCase(sequence.getType())) {
                    model.setInSequence(sequence.getName());
                } else if (APIConstants.API_CUSTOM_SEQUENCE_TYPE_OUT.equalsIgnoreCase(sequence.getType())) {
                    model.setOutSequence(sequence.getName());
                } else {
                    model.setFaultSequence(sequence.getName());
                }
            }
        }*/

        if (dto.getSubscriptionAvailability() != null) {
            model.setSubscriptionAvailability(
                    mapSubscriptionAvailabilityFromDTOtoAPI(dto.getSubscriptionAvailability()));
        }

        if (dto.getSubscriptionAvailableTenants() != null) {
            model.setSubscriptionAvailableTenants(StringUtils.join(dto.getSubscriptionAvailableTenants(), ","));
        }
        // scopes
        for (ScopeDTO scope : dto.getScopes()) {
            for (String aRole : scope.getBindings().getValues()) {
                boolean isValidRole = APIUtil.isRoleNameExist(provider, aRole);
                if (!isValidRole) {
                    String error = "Role '" + aRole + "' Does not exist.";
                    RestApiUtil.handleBadRequest(error, log);
                }
            }
        }
        Set<Scope> scopes = getScopes(dto);
        model.setScopes(scopes);

        //URI Templates
        Set<URITemplate> uriTemplates = getURITemplates(model, dto.getOperations());
        model.setUriTemplates(uriTemplates);

        if (dto.getTags() != null) {
            Set<String> apiTags = new HashSet<>(dto.getTags());
            model.addTags(apiTags);
        }

        Set<Tier> apiTiers = new HashSet<>();
        List<String> tiersFromDTO = dto.getPolicies();
        for (String tier : tiersFromDTO) {
            apiTiers.add(new Tier(tier));
        }
        model.addAvailableTiers(apiTiers);
        model.setApiLevelPolicy(dto.getApiThrottlingPolicy());

        String transports = StringUtils.join(dto.getTransport(), ',');
        model.setTransports(transports);
        if (dto.getVisibility() != null) {
            model.setVisibility(mapVisibilityFromDTOtoAPI(dto.getVisibility()));
        }
        if (dto.getVisibleRoles() != null) {
            String visibleRoles = StringUtils.join(dto.getVisibleRoles(), ',');
            model.setVisibleRoles(visibleRoles);
        }

        if (dto.getVisibleTenants() != null) {
            String visibleTenants = StringUtils.join(dto.getVisibleTenants(), ',');
            model.setVisibleTenants(visibleTenants);
        }

        List<String> accessControlRoles = dto.getAccessControlRoles();
        if (accessControlRoles == null || accessControlRoles.isEmpty()) {
            model.setAccessControl(APIConstants.NO_ACCESS_CONTROL);
            model.setAccessControlRoles("null");
        } else {
            model.setAccessControlRoles(StringUtils.join(accessControlRoles, ',').toLowerCase());
            model.setAccessControl(APIConstants.API_RESTRICTED_VISIBILITY);
        }

        Map<String, String> additionalProperties = dto.getAdditionalProperties();
        if (additionalProperties != null) {
            for (Map.Entry<String, String> entry : additionalProperties.entrySet()) {
                model.addProperty(entry.getKey(), entry.getValue());
            }
        }
        APIBusinessInformationDTO apiBusinessInformationDTO = dto.getBusinessInformation();
        if (apiBusinessInformationDTO != null) {
            model.setBusinessOwner(apiBusinessInformationDTO.getBusinessOwner());
            model.setBusinessOwnerEmail(apiBusinessInformationDTO.getBusinessOwnerEmail());
            model.setTechnicalOwner(apiBusinessInformationDTO.getTechnicalOwner());
            model.setTechnicalOwnerEmail(apiBusinessInformationDTO.getTechnicalOwnerEmail());
        }
        if (dto.getGatewayEnvironments().size() > 0) {
            List<String> gatewaysList = dto.getGatewayEnvironments();
            model.setEnvironments(APIUtil.extractEnvironmentsForAPI(gatewaysList));
        } else if (dto.getGatewayEnvironments() != null) {
            //this means the provided gatewayEnvironments is "" (empty)
            model.setEnvironments(APIUtil.extractEnvironmentsForAPI(APIConstants.API_GATEWAY_NONE));
        }
        APICorsConfigurationDTO apiCorsConfigurationDTO = dto.getCorsConfiguration();
        CORSConfiguration corsConfiguration;
        if (apiCorsConfigurationDTO != null) {
            corsConfiguration =
                    new CORSConfiguration(apiCorsConfigurationDTO.isCorsConfigurationEnabled(),
                            apiCorsConfigurationDTO.getAccessControlAllowOrigins(),
                            apiCorsConfigurationDTO.isAccessControlAllowCredentials(),
                            apiCorsConfigurationDTO.getAccessControlAllowHeaders(),
                            apiCorsConfigurationDTO.getAccessControlAllowMethods());

        } else {
            corsConfiguration = APIUtil.getDefaultCorsConfiguration();
        }
        model.setCorsConfiguration(corsConfiguration);
        setEndpointSecurityFromApiDTOToModel(dto, model);
        setMaxTpsFromApiDTOToModel(dto, model);
        model.setAuthorizationHeader(dto.getAuthorizationHeader());
        model.setApiSecurity(getSecurityScheme(dto.getSecurityScheme()));
        return model;
    }

    /**
     * This method creates the API monetization information DTO
     *
     * @param apiIdentifier API identifier
     * @return monetization information DTO
     * @throws APIManagementException if failed to construct the DTO
     */
    public static APIMonetizationInfoDTO getMonetizationInfoDTO(APIIdentifier apiIdentifier)
            throws APIManagementException {

        APIProvider apiProvider = RestApiUtil.getLoggedInUserProvider();
        API api = apiProvider.getAPI(apiIdentifier);
        APIMonetizationInfoDTO apiMonetizationInfoDTO = new APIMonetizationInfoDTO();
        //set the information relatated to monetization to the DTO
        apiMonetizationInfoDTO.setEnabled(api.getMonetizationStatus());
        Map<String, String> monetizationPropertiesMap = new HashMap<>();

        if (api.getMonetizationProperties() != null) {
            JSONObject monetizationProperties = api.getMonetizationProperties();
            for (Object propertyKey : monetizationProperties.keySet()) {
                String key = (String) propertyKey;
                monetizationPropertiesMap.put(key, (String) monetizationProperties.get(key));
            }
        }
        apiMonetizationInfoDTO.setProperties(monetizationPropertiesMap);
        return apiMonetizationInfoDTO;
    }

    /**
     * Get map of monetized policies to plan mapping
     *
     * @param apiIdentifier API identifier
     * @param monetizedPoliciesToPlanMapping map of monetized policies to plan mapping
     * @return DTO of map of monetized policies to plan mapping
     * @throws APIManagementException if failed to construct the DTO
     */
    public static APIMonetizationInfoDTO getMonetizedTiersDTO(APIIdentifier apiIdentifier,
                                                              Map<String, String> monetizedPoliciesToPlanMapping)
            throws APIManagementException {

        APIProvider apiProvider = RestApiUtil.getLoggedInUserProvider();
        API api = apiProvider.getAPI(apiIdentifier);
        APIMonetizationInfoDTO apiMonetizationInfoDTO = new APIMonetizationInfoDTO();
        apiMonetizationInfoDTO.setEnabled(api.getMonetizationStatus());
        apiMonetizationInfoDTO.setProperties(monetizedPoliciesToPlanMapping);
        return apiMonetizationInfoDTO;
    }

    /**
     * Returns the APIIdentifier given the uuid
     *
     * @param apiId                 API uuid
     * @param requestedTenantDomain tenant domain of the API
     * @return APIIdentifier which represents the given id
     * @throws APIManagementException
     */
    public static APIIdentifier getAPIIdentifierFromUUID(String apiId, String requestedTenantDomain)
            throws APIManagementException {

        return getAPIInfoFromUUID(apiId, requestedTenantDomain).getId();
    }

    /**
     * Returns an API with minimal info given the uuid.
     *
     * @param apiUUID               API uuid
     * @param requestedTenantDomain tenant domain of the API
     * @return API which represents the given id
     * @throws APIManagementException
     */
    public static API getAPIInfoFromUUID(String apiUUID, String requestedTenantDomain)
            throws APIManagementException {

        API api;
        APIProvider apiProvider = RestApiUtil.getLoggedInUserProvider();
        api = apiProvider.getLightweightAPIByUUID(apiUUID, requestedTenantDomain);
        return api;
    }

    /**
     * Converts a List object of APIs into a DTO
     *
     * @param apiList List of APIs
     * @param expand  defines whether APIListDTO should contain APIINFODTOs or APIDTOs
     * @return APIListDTO object containing APIDTOs
     */
    public static APIListDTO fromAPIListToDTO(List<API> apiList, boolean expand) throws APIManagementException {

        APIListDTO apiListDTO = new APIListDTO();
        List<APIInfoDTO> apiInfoDTOs = apiListDTO.getList();
        if (apiList != null && !expand) {
            for (API api : apiList) {
                apiInfoDTOs.add(fromAPIToInfoDTO(api));
            }
        }
        //todo: support expand
//        else if (apiList != null && expand) {
//            for (API api : apiList) {
//                apiInfoDTOs.add(fromAPItoDTO(api));
//            }
//        }
        apiListDTO.setCount(apiInfoDTOs.size());
        return apiListDTO;
    }

    /**
     * Creates a minimal DTO representation of an API object
     *
     * @param api API object
     * @return a minimal representation DTO
     */
    public static APIInfoDTO fromAPIToInfoDTO(API api) {

        APIInfoDTO apiInfoDTO = new APIInfoDTO();
        apiInfoDTO.setDescription(api.getDescription());
        String context = api.getContextTemplate();
        if (context.endsWith("/" + RestApiConstants.API_VERSION_PARAM)) {
            context = context.replace("/" + RestApiConstants.API_VERSION_PARAM, "");
        }
        apiInfoDTO.setContext(context);
        apiInfoDTO.setId(api.getUUID());
        APIIdentifier apiId = api.getId();
        apiInfoDTO.setName(apiId.getApiName());
        apiInfoDTO.setVersion(apiId.getVersion());
        apiInfoDTO.setType(api.getType());
        String providerName = api.getId().getProviderName();
        apiInfoDTO.setProvider(APIUtil.replaceEmailDomainBack(providerName));
        apiInfoDTO.setLifeCycleStatus(api.getStatus());
        if (!StringUtils.isBlank(api.getThumbnailUrl())) {
            apiInfoDTO.setHasThumbnail(true);
        } else {
            apiInfoDTO.setHasThumbnail(false);
        }
        return apiInfoDTO;
    }

    /**
     * Creates  a list of conversion policies into a DTO
     *
     * @param conversionPolicyStr conversion policies
     * @return ConversionPolicyListDTO object containing ConversionPolicyInfoDTOs
     * @throws APIManagementException
     */
    public static ResourcePolicyListDTO fromResourcePolicyStrToDTO(String conversionPolicyStr)
            throws APIManagementException {
        ResourcePolicyListDTO policyListDTO = new ResourcePolicyListDTO();
        List<ResourcePolicyInfoDTO> policyInfoDTOs = policyListDTO.getList();
        if (StringUtils.isNotEmpty(conversionPolicyStr)) {
            try {
                JSONObject conversionPolicyObj = (JSONObject) new JSONParser().parse(conversionPolicyStr);
                for (Object key : conversionPolicyObj.keySet()) {
                    JSONObject policyInfo = (JSONObject) conversionPolicyObj.get(key);
                    String keyStr = ((String) key);
                    ResourcePolicyInfoDTO policyInfoDTO = new ResourcePolicyInfoDTO();
                    policyInfoDTO.setId(policyInfo.get(RestApiConstants.SEQUENCE_ARTIFACT_ID).toString());
                    policyInfoDTO.setHttpVerb(policyInfo.get(RestApiConstants.HTTP_METHOD).toString());
                    policyInfoDTO.setResourcePath(keyStr.substring(0, keyStr.lastIndexOf("_")));
                    policyInfoDTO.setContent(policyInfo.get(RestApiConstants.SEQUENCE_CONTENT).toString());
                    policyInfoDTOs.add(policyInfoDTO);
                }
            } catch (ParseException e) {
                throw new APIManagementException("Couldn't parse the conversion policy string.", e);
            }
        }
        policyListDTO.setCount(policyInfoDTOs.size());
        return policyListDTO;
    }

    /**
     * Creates a DTO consisting a single conversion policy
     *
     * @param conversionPolicyStr conversion policy string
     * @return ConversionPolicyInfoDTO consisting given conversion policy string
     * @throws APIManagementException
     */
    public static ResourcePolicyInfoDTO fromResourcePolicyStrToInfoDTO(String conversionPolicyStr)
            throws APIManagementException {
        ResourcePolicyInfoDTO policyInfoDTO = new ResourcePolicyInfoDTO();
        if (StringUtils.isNotEmpty(conversionPolicyStr)) {
            try {
                JSONObject conversionPolicyObj = (JSONObject) new JSONParser().parse(conversionPolicyStr);
                for (Object key : conversionPolicyObj.keySet()) {
                    JSONObject policyInfo = (JSONObject) conversionPolicyObj.get(key);
                    String keyStr = ((String) key);
                    policyInfoDTO.setId(policyInfo.get(RestApiConstants.SEQUENCE_ARTIFACT_ID).toString());
                    policyInfoDTO.setHttpVerb(policyInfo.get(RestApiConstants.HTTP_METHOD).toString());
                    policyInfoDTO.setResourcePath(keyStr.substring(0, keyStr.lastIndexOf("_")));
                    policyInfoDTO.setContent(policyInfo.get(RestApiConstants.SEQUENCE_CONTENT).toString());
                }
            } catch (ParseException e) {
                throw new APIManagementException("Couldn't parse the conversion policy string.", e);
            }
        }
        return policyInfoDTO;
    }

    /**
     * Sets pagination urls for a APIListDTO object given pagination parameters and url parameters
     *
     * @param apiListDTO a APIListDTO object
     * @param query      search condition
     * @param limit      max number of objects returned
     * @param offset     starting index
     * @param size       max offset
     */
    public static void setPaginationParams(APIListDTO apiListDTO, String query, int offset, int limit, int size) {

        //acquiring pagination parameters and setting pagination urls
        Map<String, Integer> paginatedParams = RestApiUtil.getPaginationParams(offset, limit, size);
        String paginatedPrevious = "";
        String paginatedNext = "";

        if (paginatedParams.get(RestApiConstants.PAGINATION_PREVIOUS_OFFSET) != null) {
            paginatedPrevious = RestApiUtil
                    .getAPIPaginatedURL(paginatedParams.get(RestApiConstants.PAGINATION_PREVIOUS_OFFSET),
                            paginatedParams.get(RestApiConstants.PAGINATION_PREVIOUS_LIMIT), query);
        }

        if (paginatedParams.get(RestApiConstants.PAGINATION_NEXT_OFFSET) != null) {
            paginatedNext = RestApiUtil
                    .getAPIPaginatedURL(paginatedParams.get(RestApiConstants.PAGINATION_NEXT_OFFSET),
                            paginatedParams.get(RestApiConstants.PAGINATION_NEXT_LIMIT), query);
        }

        PaginationDTO paginationDTO = CommonMappingUtil
                .getPaginationDTO(limit, offset, size, paginatedNext, paginatedPrevious);
        apiListDTO.setPagination(paginationDTO);
    }

    private static String checkAndSetVersionParam(String context) {
        // This is to support the new Pluggable version strategy
        // if the context does not contain any {version} segment, we use the default version strategy.
        if (!context.contains(RestApiConstants.API_VERSION_PARAM)) {
            if (!context.endsWith("/")) {
                context = context + "/";
            }
            context = context + RestApiConstants.API_VERSION_PARAM;
        }
        return context;
    }

    private static String getThumbnailUri(String uuid) {

        return RestApiConstants.RESOURCE_PATH_THUMBNAIL.replace(RestApiConstants.APIID_PARAM, uuid);
    }

    private static String mapVisibilityFromDTOtoAPI(APIDTO.VisibilityEnum visibility) {

        switch (visibility) {
            case PUBLIC:
                return APIConstants.API_GLOBAL_VISIBILITY;
            case PRIVATE:
                return APIConstants.API_PRIVATE_VISIBILITY;
            case RESTRICTED:
                return APIConstants.API_RESTRICTED_VISIBILITY;
//            case CONTROLLED: todo add to swagger
//                return APIConstants.API_CONTROLLED_VISIBILITY;
            default:
                return null; // how to handle this?
        }
    }

    private static String mapSubscriptionAvailabilityFromDTOtoAPI(
            APIDTO.SubscriptionAvailabilityEnum subscriptionAvailability) {

        switch (subscriptionAvailability) {
        case CURRENT_TENANT:
            return APIConstants.SUBSCRIPTION_TO_CURRENT_TENANT;
        case ALL_TENANTS:
            return APIConstants.SUBSCRIPTION_TO_ALL_TENANTS;
        case SPECIFIC_TENANTS:
            return APIConstants.SUBSCRIPTION_TO_SPECIFIC_TENANTS;
        default:
            return null; // how to handle this? 500 or 400
        }

    }

    private static String updateContextWithVersion(String version, String contextVal, String context) {
        // This condition should not be true for any occasion but we keep it so that there are no loopholes in
        // the flow.
        if (version == null) {
            // context template patterns - /{version}/foo or /foo/{version}
            // if the version is null, then we remove the /{version} part from the context
            context = contextVal.replace("/" + RestApiConstants.API_VERSION_PARAM, "");
        } else {
            context = context.replace(RestApiConstants.API_VERSION_PARAM, version);
        }
        return context;
    }

    private static void setEndpointSecurityFromApiDTOToModel(APIDTO dto, API api) {

        APIEndpointSecurityDTO securityDTO = dto.getEndpointSecurity();
        if (dto.getEndpointSecurity() != null && securityDTO.getType() != null) {
            api.setEndpointSecured(true);
            api.setEndpointUTUsername(securityDTO.getUsername());
            api.setEndpointUTPassword(securityDTO.getPassword());
            if (APIEndpointSecurityDTO.TypeEnum.DIGEST.equals(securityDTO.getType())) {
                api.setEndpointAuthDigest(true);
            }
        }
    }

    private static void setMaxTpsFromApiDTOToModel(APIDTO dto, API api) {

        APIMaxTpsDTO maxTpsDTO = dto.getMaxTps();
        if (maxTpsDTO != null) {
            if (maxTpsDTO.getProduction() != null) {
                api.setProductionMaxTps(maxTpsDTO.getProduction().toString());
            }
            if (maxTpsDTO.getSandbox() != null) {
                api.setSandboxMaxTps(maxTpsDTO.getSandbox().toString());
            }
        }
    }

    public static APIDTO fromAPItoDTO(API model) throws APIManagementException {

        APIProvider apiProvider = RestApiUtil.getLoggedInUserProvider();

        APIDTO dto = new APIDTO();
        dto.setName(model.getId().getApiName());
        dto.setVersion(model.getId().getVersion());
        String providerName = model.getId().getProviderName();
        dto.setProvider(APIUtil.replaceEmailDomainBack(providerName));
        dto.setId(model.getUUID());
        String context = model.getContextTemplate();
        if (context.endsWith("/" + RestApiConstants.API_VERSION_PARAM)) {
            context = context.replace("/" + RestApiConstants.API_VERSION_PARAM, "");
        }
        dto.setContext(context);
        dto.setCreatedTime(model.getCreatedTime());
        dto.setLastUpdatedTime(Long.toString(model.getLastUpdated().getTime()));
        dto.setDescription(model.getDescription());

        dto.setIsDefaultVersion(model.isDefaultVersion());
        dto.setEnableSchemaValidation(model.isEnabledSchemaValidation());
        dto.setResponseCaching(model.getResponseCache());
        dto.setCacheTimeout(model.getCacheTimeout());
        try {
            JSONParser parser = new JSONParser();
            JSONObject endpointConfigJson = (JSONObject) parser.parse(model.getEndpointConfig());
            dto.setEndpointConfig(endpointConfigJson);
        } catch (ParseException e) {
            //logs the error and continues as this is not a blocker
            log.error("Cannot convert endpoint configurations when setting endpoint for API +" +
                    "API ID = " + model.getId(), e);
        }
      /*  if (!StringUtils.isBlank(model.getThumbnailUrl())) {todo
            dto.setThumbnailUri(getThumbnailUri(model.getUUID()));
        }*/
/*        List<SequenceDTO> sequences = new ArrayList<>();todo

        String inSequenceName = model.getInSequence();
        if (inSequenceName != null && !inSequenceName.isEmpty()) {
            String type = APIConstants.API_CUSTOM_SEQUENCE_TYPE_IN;
            boolean sharedStatus = getSharedStatus(inSequenceName,type,dto);
            String uuid = getSequenceId(inSequenceName,type,dto);
            SequenceDTO inSequence = new SequenceDTO();
            inSequence.setName(inSequenceName);
            inSequence.setType(type);
            inSequence.setShared(sharedStatus);
            inSequence.setId(uuid);
            sequences.add(inSequence);
        }

        String outSequenceName = model.getOutSequence();
        if (outSequenceName != null && !outSequenceName.isEmpty()) {
            String type = APIConstants.API_CUSTOM_SEQUENCE_TYPE_OUT;
            boolean sharedStatus = getSharedStatus(outSequenceName,type,dto);
            String uuid = getSequenceId(outSequenceName,type,dto);
            SequenceDTO outSequence = new SequenceDTO();
            outSequence.setName(outSequenceName);
            outSequence.setType(type);
            outSequence.setShared(sharedStatus);
            outSequence.setId(uuid);
            sequences.add(outSequence);
        }

        String faultSequenceName = model.getFaultSequence();
        if (faultSequenceName != null && !faultSequenceName.isEmpty()) {
            String type = APIConstants.API_CUSTOM_SEQUENCE_TYPE_FAULT;
            boolean sharedStatus = getSharedStatus(faultSequenceName,type,dto);
            String uuid = getSequenceId(faultSequenceName,type,dto);
            SequenceDTO faultSequence = new SequenceDTO();
            faultSequence.setName(faultSequenceName);
            faultSequence.setType(type);
            faultSequence.setShared(sharedStatus);
            faultSequence.setId(uuid);
            sequences.add(faultSequence);
        }

        dto.setSequences(sequences);*/

        dto.setLifeCycleStatus(model.getStatus());

        String subscriptionAvailability = model.getSubscriptionAvailability();
        if (subscriptionAvailability != null) {
            dto.setSubscriptionAvailability(mapSubscriptionAvailabilityFromAPItoDTO(subscriptionAvailability));
        }

        if (model.getSubscriptionAvailableTenants() != null) {
            dto.setSubscriptionAvailableTenants(Arrays.asList(model.getSubscriptionAvailableTenants().split(",")));
        }

        //Get Swagger definition which has URL templates, scopes and resource details
        if (!APIDTO.TypeEnum.WS.toString().equals(model.getType())) {
            List<APIOperationsDTO> apiOperationsDTO;
            String apiSwaggerDefinition = apiProvider.getOpenAPIDefinition(model.getId());
            apiOperationsDTO = getOperationsFromSwaggerDef(model, apiSwaggerDefinition);
            dto.setOperations(apiOperationsDTO);
            List<ScopeDTO> scopeDTOS = getScopesFromSwagger(apiSwaggerDefinition);
            dto.setScopes(scopeDTOS);
        }
        Set<String> apiTags = model.getTags();
        List<String> tagsToReturn = new ArrayList<>();
        tagsToReturn.addAll(apiTags);
        dto.setTags(tagsToReturn);

        Set<org.wso2.carbon.apimgt.api.model.Tier> apiTiers = model.getAvailableTiers();
        List<String> tiersToReturn = new ArrayList<>();
        for (org.wso2.carbon.apimgt.api.model.Tier tier : apiTiers) {
            tiersToReturn.add(tier.getName());
        }
        dto.setPolicies(tiersToReturn);
        dto.setApiThrottlingPolicy(model.getApiLevelPolicy());

        //APIs created with type set to "NULL" will be considered as "HTTP"
        if (model.getType() == null || model.getType().toLowerCase().equals("null")) {
            dto.setType(APIDTO.TypeEnum.HTTP);
        } else {
            dto.setType(APIDTO.TypeEnum.fromValue(model.getType()));
        }

        if (!APIConstants.APITransportType.WS.toString().equals(model.getType())) {
            if (StringUtils.isEmpty(model.getTransports())) {
                List<String> transports = new ArrayList<>();
                transports.add(APIConstants.HTTPS_PROTOCOL);

                dto.setTransport(transports);
            }
            dto.setTransport(Arrays.asList(model.getTransports().split(",")));
        }
        if (StringUtils.isEmpty(model.getTransports())) {
            dto.setVisibility(APIDTO.VisibilityEnum.PUBLIC);
        }
        dto.setVisibility(mapVisibilityFromAPItoDTO(model.getVisibility()));

        if (model.getVisibleRoles() != null) {
            dto.setVisibleRoles(Arrays.asList(model.getVisibleRoles().split(",")));
        }

        if (model.getVisibleTenants() != null) {
            dto.setVisibleRoles(Arrays.asList(model.getVisibleTenants().split(",")));
        }

        if (model.getAdditionalProperties() != null) {
            JSONObject additionalProperties = model.getAdditionalProperties();
            Map<String, String> additionalPropertiesMap = new HashMap<>();
            for (Object propertyKey : additionalProperties.keySet()) {
                String key = (String) propertyKey;
                additionalPropertiesMap.put(key, (String) additionalProperties.get(key));
            }
            dto.setAdditionalProperties(additionalPropertiesMap);
        }

        dto.setAccessControl(APIConstants.API_RESTRICTED_VISIBILITY.equals(model.getAccessControl()) ?
                APIDTO.AccessControlEnum.RESTRICTED :
                APIDTO.AccessControlEnum.NONE);
        if (model.getAccessControlRoles() != null) {
            dto.setAccessControlRoles(Arrays.asList(model.getAccessControlRoles().split(",")));
        }
        APIBusinessInformationDTO apiBusinessInformationDTO = new APIBusinessInformationDTO();
        apiBusinessInformationDTO.setBusinessOwner(model.getBusinessOwner());
        apiBusinessInformationDTO.setBusinessOwnerEmail(model.getBusinessOwnerEmail());
        apiBusinessInformationDTO.setTechnicalOwner(model.getTechnicalOwner());
        apiBusinessInformationDTO.setTechnicalOwnerEmail(model.getTechnicalOwnerEmail());
        dto.setBusinessInformation(apiBusinessInformationDTO);
        List<String> environmentsList = new ArrayList<String>();
        environmentsList.addAll(model.getEnvironments());
        dto.setGatewayEnvironments(environmentsList);
        APICorsConfigurationDTO apiCorsConfigurationDTO = new APICorsConfigurationDTO();
        CORSConfiguration corsConfiguration = model.getCorsConfiguration();
        if (corsConfiguration == null) {
            corsConfiguration = APIUtil.getDefaultCorsConfiguration();
        }
        apiCorsConfigurationDTO
                .setAccessControlAllowOrigins(corsConfiguration.getAccessControlAllowOrigins());
        apiCorsConfigurationDTO
                .setAccessControlAllowHeaders(corsConfiguration.getAccessControlAllowHeaders());
        apiCorsConfigurationDTO
                .setAccessControlAllowMethods(corsConfiguration.getAccessControlAllowMethods());
        apiCorsConfigurationDTO.setCorsConfigurationEnabled(corsConfiguration.isCorsConfigurationEnabled());
        apiCorsConfigurationDTO.setAccessControlAllowCredentials(corsConfiguration.isAccessControlAllowCredentials());
        dto.setCorsConfiguration(apiCorsConfigurationDTO);
        dto.setWsdlUri(model.getWsdlUrl());
        setEndpointSecurityFromModelToApiDTO(model, dto);
        setMaxTpsFromModelToApiDTO(model, dto);

        //setting micro-gateway labels if there are any
        if (model.getGatewayLabels() != null) {
            List<LabelDTO> labels = new ArrayList<>();
            List<Label> gatewayLabels = model.getGatewayLabels();
            for (Label label : gatewayLabels) {
                LabelDTO labelDTO = new LabelDTO();
                labelDTO.setName(label.getName());
                labelDTO.setAccessUrls(label.getAccessUrls());
                labelDTO.setDescription(label.getDescription());
                labels.add(labelDTO);
            }
            dto.setLabels(labels);
        }
        dto.setAuthorizationHeader(model.getAuthorizationHeader());
        if (model.getApiSecurity() != null) {
            dto.setSecurityScheme(Arrays.asList(model.getApiSecurity().split(",")));
        }
        if (null != model.getLastUpdated()) {
            Date lastUpdateDate = model.getLastUpdated();
            Timestamp timeStamp = new Timestamp(lastUpdateDate.getTime());
            dto.setLastUpdatedTime(String.valueOf(timeStamp));
        }
        if (null != model.getCreatedTime()) {
            Date createdTime = model.getLastUpdated();
            Timestamp timeStamp = new Timestamp(createdTime.getTime());
            dto.setCreatedTime(String.valueOf(timeStamp));
        }
        return dto;
    }

    private static APIDTO.VisibilityEnum mapVisibilityFromAPItoDTO(String visibility) {

        switch (visibility) { //public, private,controlled, restricted
            case APIConstants.API_GLOBAL_VISIBILITY:
                return APIDTO.VisibilityEnum.PUBLIC;
            case APIConstants.API_PRIVATE_VISIBILITY:
                return APIDTO.VisibilityEnum.PRIVATE;
            case APIConstants.API_RESTRICTED_VISIBILITY:
                return APIDTO.VisibilityEnum.RESTRICTED;
//            case APIConstants.API_CONTROLLED_VISIBILITY : todo add this to swagger
//                return APIDTO.VisibilityEnum.CONTROLLED;
            default:
                return null; // how to handle this?
        }
    }

    private static APIDTO.SubscriptionAvailabilityEnum mapSubscriptionAvailabilityFromAPItoDTO(
            String subscriptionAvailability) {

        switch (subscriptionAvailability) {
            case APIConstants.SUBSCRIPTION_TO_CURRENT_TENANT:
                return APIDTO.SubscriptionAvailabilityEnum.CURRENT_TENANT;
            case APIConstants.SUBSCRIPTION_TO_ALL_TENANTS:
                return APIDTO.SubscriptionAvailabilityEnum.ALL_TENANTS;
            case APIConstants.SUBSCRIPTION_TO_SPECIFIC_TENANTS:
                return APIDTO.SubscriptionAvailabilityEnum.SPECIFIC_TENANTS;
            default:
                return null; // how to handle this?
        }

    }

    private static void setEndpointSecurityFromModelToApiDTO(API api, APIDTO dto) throws APIManagementException {

        if (api.isEndpointSecured()) {
            APIEndpointSecurityDTO securityDTO = new APIEndpointSecurityDTO();
            securityDTO.setType(APIEndpointSecurityDTO.TypeEnum.BASIC); //set default as basic
            securityDTO.setUsername(api.getEndpointUTUsername());
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(api.getId()
                    .getProviderName()));
            if (checkEndpointSecurityPasswordEnabled(tenantDomain)) {
                securityDTO.setPassword(api.getEndpointUTPassword());
            } else {
                securityDTO.setPassword(""); //Do not expose password
            }
            if (api.isEndpointAuthDigest()) {
                securityDTO.setType(APIEndpointSecurityDTO.TypeEnum.DIGEST);
            }
            dto.setEndpointSecurity(securityDTO);
        }
    }

    /**
     * This method used to check whether the config for exposing endpoint security password when getting API is enabled
     * or not in tenant-config.json in registry.
     *
     * @return boolean as config enabled or not
     * @throws APIManagementException
     */
    private static boolean checkEndpointSecurityPasswordEnabled(String tenantDomainName) throws APIManagementException {

        JSONObject apiTenantConfig;
        try {
            APIMRegistryServiceImpl apimRegistryService = new APIMRegistryServiceImpl();
            String content = apimRegistryService.getConfigRegistryResourceContent(tenantDomainName,
                    APIConstants.API_TENANT_CONF_LOCATION);
            if (content != null) {
                JSONParser parser = new JSONParser();
                apiTenantConfig = (JSONObject) parser.parse(content);
                if (apiTenantConfig != null) {
                    Object value = apiTenantConfig.get(APIConstants.API_TENANT_CONF_EXPOSE_ENDPOINT_PASSWORD);
                    if (value != null) {
                        return Boolean.parseBoolean(value.toString());
                    }
                }
            }
        } catch (UserStoreException e) {
            String msg = "UserStoreException thrown when getting API tenant config from registry while reading "
                    + "ExposeEndpointPassword config";
            throw new APIManagementException(msg, e);
        } catch (RegistryException e) {
            String msg = "RegistryException thrown when getting API tenant config from registry while reading "
                    + "ExposeEndpointPassword config";
            throw new APIManagementException(msg, e);
        } catch (ParseException e) {
            String msg = "ParseException thrown when parsing API tenant config from registry while reading "
                    + "ExposeEndpointPassword config";
            throw new APIManagementException(msg, e);
        }
        return false;
    }

    private static void setMaxTpsFromModelToApiDTO(API api, APIDTO dto) {

        if (StringUtils.isBlank(api.getProductionMaxTps()) && StringUtils.isBlank(api.getSandboxMaxTps())) {
            return;
        }
        APIMaxTpsDTO maxTpsDTO = new APIMaxTpsDTO();
        try {
            if (!StringUtils.isBlank(api.getProductionMaxTps())) {
                maxTpsDTO.setProduction(Long.parseLong(api.getProductionMaxTps()));
            }
            if (!StringUtils.isBlank(api.getSandboxMaxTps())) {
                maxTpsDTO.setSandbox(Long.parseLong(api.getSandboxMaxTps()));
            }
            dto.setMaxTps(maxTpsDTO);
        } catch (NumberFormatException e) {
            //logs the error and continues as this is not a blocker
            log.error("Cannot convert to Long format when setting maxTps for API", e);
        }
    }

    /**
     * Return the REST API DTO representation of API Lifecycle state information
     *
     * @param apiLCData API lifecycle state information
     * @return REST API DTO representation of API Lifecycle state information
     */
    public static LifecycleStateDTO fromLifecycleModelToDTO(Map<String, Object> apiLCData) {

        LifecycleStateDTO lifecycleStateDTO = new LifecycleStateDTO();

        String currentState = (String) apiLCData.get(APIConstants.LC_STATUS);
        lifecycleStateDTO.setState(currentState);

        String[] nextStates = (String[]) apiLCData.get(APIConstants.LC_NEXT_STATES);
        if (nextStates != null) {
            List<LifecycleStateAvailableTransitionsDTO> transitionDTOList = new ArrayList<>();
            for (String state : nextStates) {
                LifecycleStateAvailableTransitionsDTO transitionDTO = new LifecycleStateAvailableTransitionsDTO();
                transitionDTO.setEvent(state);
                //todo: Set target state properly
                transitionDTO.setTargetState("");
                transitionDTOList.add(transitionDTO);
            }
            lifecycleStateDTO.setAvailableTransitions(transitionDTOList);
        }

        List checkListItems = (List) apiLCData.get(APIConstants.LC_CHECK_ITEMS);
        if (checkListItems != null) {
            List<LifecycleStateCheckItemsDTO> checkItemsDTOList = new ArrayList<>();
            for (Object checkListItemObj : checkListItems) {
                CheckListItem checkListItem = (CheckListItem) checkListItemObj;
                LifecycleStateCheckItemsDTO checkItemsDTO = new LifecycleStateCheckItemsDTO();
                checkItemsDTO.setName(checkListItem.getName());
                checkItemsDTO.setValue(Boolean.getBoolean(checkListItem.getValue()));
                //todo: Set targets properly
                checkItemsDTO.setRequiredStates(new ArrayList<>());

                checkItemsDTOList.add(checkItemsDTO);
            }
            lifecycleStateDTO.setCheckItems(checkItemsDTOList);
        }
        return lifecycleStateDTO;
    }

    /**
     * Return the REST API DTO representation of API Lifecycle history information
     *
     * @param lifeCycleEvents API lifecycle history information
     * @return REST API DTO representation of API Lifecycle history information
     */
    public static LifecycleHistoryDTO fromLifecycleHistoryModelToDTO(List<LifeCycleEvent> lifeCycleEvents) {

        LifecycleHistoryDTO historyDTO = new LifecycleHistoryDTO();
        historyDTO.setCount(lifeCycleEvents.size());
        for (LifeCycleEvent event : lifeCycleEvents) {
            LifecycleHistoryItemDTO historyItemDTO = new LifecycleHistoryItemDTO();
            historyItemDTO.setPostState(event.getNewStatus());
            historyItemDTO.setPreviousState(event.getOldStatus());
            historyItemDTO.setUser(event.getUserId());

            String updatedTime = RestApiUtil.getRFC3339Date(event.getDate());
            historyItemDTO.setUpdatedTime(updatedTime);
            historyDTO.getList().add(historyItemDTO);
        }
        return historyDTO;
    }

    /**
     * This method returns URI templates according to the given list of operations
     *
     * @param operations List operations
     * @return URI Templates
     * @throws APIManagementException
     */
    public static Set<URITemplate> getURITemplates(API model, List<APIOperationsDTO> operations)
            throws APIManagementException {

        boolean isHttpVerbDefined = false;
        Set<URITemplate> uriTemplates = new LinkedHashSet<>();

        if (operations == null || operations.isEmpty()) {
            operations = getDefaultOperationsList(model.getType());
        }

        for (APIOperationsDTO operation : operations) {
            URITemplate template = new URITemplate();

            String uriTempVal = operation.getTarget();

            String httpVerb = operation.getVerb();
            List<String> scopeList = operation.getScopes();
            if (scopeList != null) {
                for (String scopeKey : scopeList) {
                    for (Scope definedScope : model.getScopes()) {
                        if (definedScope.getKey().equalsIgnoreCase(scopeKey)) {
                            Scope scope = new Scope();
                            scope.setKey(scopeKey);
                            scope.setName(definedScope.getName());
                            scope.setDescription(definedScope.getDescription());
                            scope.setRoles(definedScope.getRoles());
                            template.setScopes(scope);
                            template.setScope(scope);
                        }
                    }
                }

            }
            //Only continue for supported operations
            if (APIConstants.SUPPORTED_METHODS.contains(httpVerb.toLowerCase()) ||
                    (APIConstants.GRAPHQL_SUPPORTED_METHOD_LIST.contains(httpVerb.toUpperCase()))) {
                isHttpVerbDefined = true;
                String authType = operation.getAuthType();
                if (APIConstants.OASResourceAuthTypes.APPLICATION_OR_APPLICATION_USER.equals(authType)) {
                    authType = APIConstants.AUTH_APPLICATION_OR_USER_LEVEL_TOKEN;
                } else if (APIConstants.OASResourceAuthTypes.APPLICATION_USER.equals(authType)) {
                    authType = APIConstants.AUTH_APPLICATION_USER_LEVEL_TOKEN;
                } else if (APIConstants.OASResourceAuthTypes.NONE.equals(authType)) {
                    authType = APIConstants.AUTH_NO_AUTHENTICATION;
                } else if (APIConstants.OASResourceAuthTypes.APPLICATION.equals(authType)) {
                    authType = APIConstants.AUTH_APPLICATION_LEVEL_TOKEN;
                } else {
                    authType = APIConstants.AUTH_APPLICATION_OR_USER_LEVEL_TOKEN;
                }
                template.setThrottlingTier(operation.getThrottlingPolicy());
                template.setThrottlingTiers(operation.getThrottlingPolicy());
                template.setUriTemplate(uriTempVal);
                template.setHTTPVerb(httpVerb.toUpperCase());
                template.setHttpVerbs(httpVerb.toUpperCase());
                template.setAuthType(authType);
                template.setAuthTypes(authType);

                uriTemplates.add(template);
            } else {
                if(APIConstants.GRAPHQL_API.equals(model.getType())){
                    handleException("The GRAPHQL operation Type '" + httpVerb + "' provided for operation '" + uriTempVal
                            + "' is invalid");
                } else {
                    handleException("The HTTP method '" + httpVerb + "' provided for resource '" + uriTempVal
                            + "' is invalid");
                }
            }

            if (!isHttpVerbDefined) {
                if(APIConstants.GRAPHQL_API.equals(model.getType())) {
                    handleException("Operation '" + uriTempVal + "' has global parameters without " +
                            "Operation Type");
                } else {
                    handleException("Resource '" + uriTempVal + "' has global parameters without " +
                            "HTTP methods");
                }
            }
        }

        return uriTemplates;
    }

    /**
     * This method returns the oauth scopes according to the given list of scopes
     *
     * @param apiDTO list of scopes
     * @return scope set
     */
    public static Set<Scope> getScopes(APIDTO apiDTO) {

        Set<Scope> scopeSet = new LinkedHashSet<>();
        for (ScopeDTO scopeDTO : apiDTO.getScopes()) {
            Scope scope = new Scope();
            scope.setKey(scopeDTO.getName());
            scope.setName(scopeDTO.getName());
            scope.setDescription(scopeDTO.getDescription());
            scope.setRoles(String.join(",", scopeDTO.getBindings().getValues()));
            scopeSet.add(scope);
        }
        return scopeSet;
    }

    /**
     * This method returns the oauth scopes according to the given list of scopes
     *
     * @param apiProductDTO list of scopes
     * @return scope set
     */
    private static Set<Scope> getScopes(APIProductDTO apiProductDTO) {

        Set<Scope> scopeSet = new LinkedHashSet<>();
        for (ScopeDTO scopeDTO : apiProductDTO.getScopes()) {
            Scope scope = new Scope();
            scope.setKey(scopeDTO.getName());
            scope.setName(scopeDTO.getName());
            scope.setDescription(scopeDTO.getDescription());
            scope.setRoles(String.join(",", scopeDTO.getBindings().getValues()));
            scopeSet.add(scope);
        }
        return scopeSet;
    }
//
//    /**
//     * This method returns endpoints according to the given endpoint config
//     *
//     * @param endpoints endpoints given
//     * @return String endpoint config
//     */
//    public static String getEndpointConfigString(List<APIEndpointDTO> endpoints) {
//        //todo improve this logic to support multiple endpoints such as failorver and load balance
//        StringBuilder sb = new StringBuilder();
//        if (endpoints != null && endpoints.size() > 0) {
//            sb.append("{");
//            for (APIEndpointDTO endpoint : endpoints) {
//                sb.append("\"")
//                        .append(endpoint.getType())
//                        .append("\": {\"url\":\"")
//                        .append(endpoint.getInline().getEndpointConfig().getList().get(0).getUrl())
//                        .append("\",\"timeout\":\"")
//                        .append(endpoint.getInline().getEndpointConfig().getList().get(0).getTimeout())
//                        .append("\"},");
//            }
//            sb.append("\"endpoint_type\" : \"")
//                    .append(endpoints.get(0).getInline().getType())//assuming all the endpoints are same type
//                    .append("\"}\n");
//        }
//        return sb.toString();
//    }

//    private static EndpointEndpointConfigDTO getEndpointEndpointConfigDTO(EndpointEndpointConfig endpointEndpointConfig) {
//
//        //map to EndpointEndpointConfig model to EndpointEndpointConfigDTO
//        EndpointEndpointConfigDTO endpointEndpointConfigDTO = new EndpointEndpointConfigDTO();
//        switch (endpointEndpointConfig.getEndpointType()) {
//            case SINGLE:
//                endpointEndpointConfigDTO.setEndpointType(EndpointEndpointConfigDTO.EndpointTypeEnum.SINGLE);
//            case LOAD_BALANCED:
//                endpointEndpointConfigDTO.setEndpointType(EndpointEndpointConfigDTO.EndpointTypeEnum.LOAD_BALANCED);
//            case FAIL_OVER:
//                endpointEndpointConfigDTO.setEndpointType(EndpointEndpointConfigDTO.EndpointTypeEnum.FAIL_OVER);
//        }
//        List<EndpointConfigDTO> endpointConfigDTOList = new ArrayList<>();
//        for (EndpointConfig endpointConfig : endpointEndpointConfig.getList()) {
//            EndpointConfigDTO endpointConfigDTO = new EndpointConfigDTO();
//            endpointConfigDTO.setUrl(endpointConfig.getUrl());
//            endpointConfigDTO.setTimeout(endpointConfig.getTimeout());
//
//            //map EndpointConfigAttributes model to EndpointConfigAttributesDTO
//            List<EndpointConfigAttributesDTO> endpointConfigAttributesList = new ArrayList<>();
//            for (EndpointConfigAttributes endpointConfigAttributes : endpointConfig.getAttributes()) {
//                EndpointConfigAttributesDTO endpointConfigAttributeDTO = new EndpointConfigAttributesDTO();
//                endpointConfigAttributeDTO.setName(endpointConfigAttributes.getName());
//                endpointConfigAttributeDTO.setValue(endpointConfigAttributes.getValue());
//                endpointConfigAttributesList.add(endpointConfigAttributeDTO);
//            }
//            endpointConfigDTO.setAttributes(endpointConfigAttributesList);
//            endpointConfigDTOList.add(endpointConfigDTO);
//        }
//        endpointEndpointConfigDTO.setList(endpointConfigDTOList);
//        return endpointEndpointConfigDTO;
//    }
//
//    /**
//     * This method converts Endpoint:EndpontConfig DTO to corresponding model
//     *
//     * @param apiEndpointDTO1 egiven endpoint config
//     * @param type            given endpoint type  SINGLE,  LOAD_BALANCED,  FAIL_OVER
//     * @return EndpointConfig model
//     */
//    private EndpointEndpointConfig getEndpointEndpointConfigModel(EndpointEndpointConfigDTO apiEndpointDTO1,
//                                                                  EndpointEndpointConfig.EndpointTypeEnum type) {
//
//        //mapping properties in EndpointConfigDTO to EndpointConfig model
//        List<EndpointConfig> configList = new ArrayList<>();
//        for (EndpointConfigDTO endpointConfigDTO : apiEndpointDTO1.getList()) {
//            EndpointConfig endpointConfig1 = new EndpointConfig();
//            endpointConfig1.setUrl(endpointConfigDTO.getUrl());
//            endpointConfig1.setTimeout(endpointConfigDTO.getTimeout());
//
//            //mapping attributes in EndpointConfigAttributesDTO to EndpointConfigAttributes model
//            List<EndpointConfigAttributes> endpointConfigAttributesList = new ArrayList<>();
//            for (EndpointConfigAttributesDTO endpointConfigAttributesDTO : endpointConfigDTO.getAttributes()) {
//                EndpointConfigAttributes endpointConfigAttribute = new EndpointConfigAttributes();
//                endpointConfigAttribute.setName(endpointConfigAttributesDTO.getName());
//                endpointConfigAttribute.setValue(endpointConfigAttributesDTO.getValue());
//
//                endpointConfigAttributesList.add(endpointConfigAttribute);
//            }
//
//            endpointConfig1.setAttributes(endpointConfigAttributesList);
//            configList.add(endpointConfig1);
//        }
//
//        //mapping properties in EndpointEndpointConfigDTO to EndpointEndpointConfig model
//        EndpointEndpointConfig endpointConfig = new EndpointEndpointConfig();
//        endpointConfig.setEndpointType(type);
//        endpointConfig.setList(configList);
//
//        return endpointConfig;
//
//    }

    /**
     * This method returns api security scheme as a comma seperated string
     *
     * @param securitySchemes api security scheme
     * @return comma seperated string of api security schemes
     */
    public static String getSecurityScheme(List<String> securitySchemes) {

        if (securitySchemes == null || securitySchemes.size() <= 0) {
            return "";
        }
        StringBuilder apiSecurityScheme = new StringBuilder();
        if (securitySchemes != null) {
            for (String scheme : securitySchemes) {
                apiSecurityScheme.append(scheme).append(",");
            }
            apiSecurityScheme.deleteCharAt(apiSecurityScheme.length() - 1);
        }
        return apiSecurityScheme.toString();
    }

    public static OpenAPIDefinitionValidationResponseDTO getOpenAPIDefinitionValidationResponseFromModel(
            APIDefinitionValidationResponse model, boolean returnContent) {

        OpenAPIDefinitionValidationResponseDTO responseDTO = new OpenAPIDefinitionValidationResponseDTO();
        responseDTO.setIsValid(model.isValid());

        if (model.isValid()) {
            APIDefinitionValidationResponse.Info modelInfo = model.getInfo();
            if (modelInfo != null) {
                OpenAPIDefinitionValidationResponseInfoDTO infoDTO =
                        new OpenAPIDefinitionValidationResponseInfoDTO();
                infoDTO.setOpenAPIVersion(modelInfo.getOpenAPIVersion());
                infoDTO.setName(modelInfo.getName());
                infoDTO.setVersion(modelInfo.getVersion());
                infoDTO.setContext(modelInfo.getContext());
                infoDTO.setDescription(modelInfo.getDescription());
                responseDTO.setInfo(infoDTO);
            }
            if (returnContent) {
                responseDTO.setContent(model.getContent());
            }
        } else {
            responseDTO.setErrors(getErrorListItemsDTOsFromErrorHandlers(model.getErrorItems()));
        }
        return responseDTO;
    }

    public static List<ErrorListItemDTO> getErrorListItemsDTOsFromErrorHandlers(List<ErrorHandler> errorHandlers) {
        List<ErrorListItemDTO> errorListItemDTOs = new ArrayList<>();
        for (ErrorHandler handler: errorHandlers) {
            ErrorListItemDTO dto = new ErrorListItemDTO();
            dto.setCode(handler.getErrorCode() + "");
            dto.setMessage(handler.getErrorMessage());
            dto.setDescription(handler.getErrorDescription());
            errorListItemDTOs.add(dto);
        }
        return errorListItemDTOs;
    }

    /**
     * Get the ErrorDTO from a list of ErrorListItemDTOs. The first item in the list is set as the main error.
     *
     * @param errorListItemDTOs A list of ErrorListItemDTO objects
     * @return ErrorDTO from a list of ErrorListItemDTOs
     */
    public static ErrorDTO getErrorDTOFromErrorListItems(List<ErrorListItemDTO> errorListItemDTOs) {
        ErrorDTO errorDTO = new ErrorDTO();
        for (int i = 0; i < errorListItemDTOs.size(); i++) {
            if (i == 0) {
                ErrorListItemDTO elementAt0 = errorListItemDTOs.get(0);
                errorDTO.setCode(Long.parseLong(elementAt0.getCode()));
                errorDTO.setMoreInfo("");
                errorDTO.setMessage(elementAt0.getMessage());
                errorDTO.setDescription(elementAt0.getDescription());
            } else {
                org.wso2.carbon.apimgt.rest.api.util.dto.ErrorListItemDTO errorListItemDTO
                        = new org.wso2.carbon.apimgt.rest.api.util.dto.ErrorListItemDTO();
                errorListItemDTO.setCode(errorListItemDTOs.get(i).getCode() + "");
                errorListItemDTO.setMessage(errorListItemDTOs.get(i).getMessage());
                errorListItemDTO.setDescription(errorListItemDTOs.get(i).getDescription());
                errorDTO.getError().add(errorListItemDTO);
            }
        }
        return errorDTO;
    }

//    /**
//     * This method converts APIEndpoint model to corresponding APIEndpointDTO object
//     *
//     * @param model api model
//     * @return APIEndpointDTO List of apiEndpointDTO
//     */
//    public static List<APIEndpointDTO> getAPIEndpointDTO(API model) throws ParseException {
//
//        List<APIEndpoint> apiEndpointsList = model.getEndpoint();
//        if (apiEndpointsList == null || apiEndpointsList.size() <= 0) {
//            return getAPIEndpointDTOFromEndpointConfig(model.getEndpointConfig());
//        }
//        List<APIEndpointDTO> apiEndpointDTOList = new ArrayList<>(apiEndpointsList.size());
//
//        for (APIEndpoint apiEndpoint : apiEndpointsList) {
//            APIEndpointDTO apiEndpointDTO = new APIEndpointDTO();
//            Endpoint endpoint = apiEndpoint.getInline();
//            EndpointSecurity endpointSecurity = endpoint.getEndpointSecurity();
//            EndpointDTO endpointDTO = new EndpointDTO();
//
//            EndpointEndpointSecurityDTO endpointEndpointSecurityDTO = new EndpointEndpointSecurityDTO();
//
//            endpointEndpointSecurityDTO.setEnabled(endpointSecurity.getEnabled());
//            endpointEndpointSecurityDTO.setPassword(endpointSecurity.getPassword());
//            endpointEndpointSecurityDTO.setUsername(endpointSecurity.getUsername());
//            endpointEndpointSecurityDTO.setType(endpointSecurity.getType());
//
//            endpointDTO.setEndpointSecurity(endpointEndpointSecurityDTO);
//            endpointDTO.setEndpointConfig(getEndpointEndpointConfigDTO(endpoint.getEndpointConfig()));
//            endpointDTO.setId(endpoint.getId());
//            endpointDTO.setMaxTps(endpoint.getMaxTps());
//            endpointDTO.setName(endpoint.getName());
//            endpointDTO.setType(endpoint.getType());
//
//            apiEndpointDTO.setInline(endpointDTO);
//            apiEndpointDTO.setType(apiEndpoint.getType());
//
//            apiEndpointDTOList.add(apiEndpointDTO);
//        }
//
//        return apiEndpointDTOList;
//    }
//
//    /**
//     * This method converts endpointconfig json to corresponding APIEndpointDTO object
//     *
//     * @param type           production_endpoints, sandbox_endpoints
//     * @param endpointConfig endpoint config
//     * @param endpointProtocolType endpoint protocol type; eg: http
//     * @return APIEndpointDTO apiEndpointDTO
//     */
//    public static APIEndpointDTO convertToAPIEndpointDTO(String type, JSONObject endpointConfig,
//            String endpointProtocolType) {
//
//        APIEndpointDTO apiEndpointDTO = new APIEndpointDTO();
//        apiEndpointDTO.setType(type);
//        if (endpointConfig.containsKey(APIConstants.API_DATA_URL)) {
//            String url = endpointConfig.get(APIConstants.API_DATA_URL).toString();
//            EndpointDTO endpointDTO = new EndpointDTO();
//            EndpointEndpointConfigDTO endpointEndpointConfigDTO = new EndpointEndpointConfigDTO();
//            List<EndpointConfigDTO> list = new ArrayList<>();
//            EndpointConfigDTO endpointConfigDTO = new EndpointConfigDTO();
//            endpointConfigDTO.setUrl(url);
//            if (endpointConfig.containsKey(APIConstants.API_ENDPOINT_CONFIG_TIMEOUT)) {
//                endpointConfigDTO.setTimeout(endpointConfig.get(APIConstants.API_ENDPOINT_CONFIG_TIMEOUT).toString());
//            }
//            list.add(endpointConfigDTO);
//            endpointEndpointConfigDTO.setList(list);
//
//            //todo: fix for other types of endpoints eg: load balanced, failover
//            endpointEndpointConfigDTO.setEndpointType(EndpointEndpointConfigDTO.EndpointTypeEnum.SINGLE);
//
//            endpointDTO.setEndpointConfig(endpointEndpointConfigDTO);
//            endpointDTO.setType(endpointProtocolType);
//            apiEndpointDTO.setInline(endpointDTO);
//        }
//        return apiEndpointDTO;
//    }
//
//    /**
//     * This method converts endpointconfig json string to corresponding APIEndpointDTO objects
//     *
//     * @param endpointConfig string
//     * @return APIEndpointDTO List of apiEndpointDTO
//     */
//    public static List<APIEndpointDTO> getAPIEndpointDTOFromEndpointConfig(String endpointConfig) throws ParseException {
//        //todo improve to support multiple endpoints.
//        List<APIEndpointDTO> apiEndpointDTOList = new ArrayList<>();
//        if (endpointConfig != null) {
//            JSONParser parser = new JSONParser();
//            JSONObject endpointConfigJson = (JSONObject) parser.parse(endpointConfig);
//            String endpointProtocolType = (String) endpointConfigJson
//                    .get(APIConstants.API_ENDPOINT_CONFIG_PROTOCOL_TYPE);
//
//            if (endpointConfigJson.containsKey(APIConstants.API_DATA_PRODUCTION_ENDPOINTS) &&
//                    isEndpointURLNonEmpty(endpointConfigJson.get(APIConstants.API_DATA_PRODUCTION_ENDPOINTS))) {
//                JSONObject prodEPConfig = (JSONObject) endpointConfigJson
//                        .get(APIConstants.API_DATA_PRODUCTION_ENDPOINTS);
//                APIEndpointDTO apiEndpointDTO = convertToAPIEndpointDTO(APIConstants.API_DATA_PRODUCTION_ENDPOINTS,
//                        prodEPConfig, endpointProtocolType);
//                apiEndpointDTOList.add(apiEndpointDTO);
//            }
//            if (endpointConfigJson.containsKey(APIConstants.API_DATA_SANDBOX_ENDPOINTS) &&
//                    isEndpointURLNonEmpty(endpointConfigJson.get(APIConstants.API_DATA_SANDBOX_ENDPOINTS))) {
//                JSONObject sandboxEPConfig = (JSONObject) endpointConfigJson
//                        .get(APIConstants.API_DATA_SANDBOX_ENDPOINTS);
//                APIEndpointDTO apiEndpointDTO = convertToAPIEndpointDTO(APIConstants.API_DATA_SANDBOX_ENDPOINTS,
//                        sandboxEPConfig, endpointProtocolType);
//                apiEndpointDTOList.add(apiEndpointDTO);
//            }
//
//        }
//        return apiEndpointDTOList;
//    }

    /**
     * Returns workflow state DTO from the provided information
     *
     * @param lifecycleStateDTO Lifecycle state DTO
     * @param stateChangeResponse workflow response from API lifecycle change
     * @return workflow state DTO
     */
    public static WorkflowResponseDTO toWorkflowResponseDTO(LifecycleStateDTO lifecycleStateDTO,
            APIStateChangeResponse stateChangeResponse) {
        WorkflowResponseDTO workflowResponseDTO = new WorkflowResponseDTO();

        if (WorkflowStatus.APPROVED.toString().equals(stateChangeResponse.getStateChangeStatus())) {
            workflowResponseDTO.setWorkflowStatus(WorkflowResponseDTO.WorkflowStatusEnum.APPROVED);
        } else if (WorkflowStatus.CREATED.toString().equals(stateChangeResponse.getStateChangeStatus())) {
            workflowResponseDTO.setWorkflowStatus(WorkflowResponseDTO.WorkflowStatusEnum.CREATED);
        } else if ((WorkflowStatus.REGISTERED.toString().equals(stateChangeResponse.getStateChangeStatus()))) {
            workflowResponseDTO.setWorkflowStatus(WorkflowResponseDTO.WorkflowStatusEnum.REGISTERED);
        } else if ((WorkflowStatus.REJECTED.toString().equals(stateChangeResponse.getStateChangeStatus()))) {
            workflowResponseDTO.setWorkflowStatus(WorkflowResponseDTO.WorkflowStatusEnum.REJECTED);
        } else {
            log.error("Unrecognized state : " + stateChangeResponse.getStateChangeStatus());
            workflowResponseDTO.setWorkflowStatus(WorkflowResponseDTO.WorkflowStatusEnum.CREATED);
        }

        workflowResponseDTO.setLifecycleState(lifecycleStateDTO);
        return workflowResponseDTO;
    }

    /**
     * Returns a set of operations from a given swagger definition
     *
     * @param api               API object
     * @param swaggerDefinition Swagger definition
     * @return a set of operations from a given swagger definition
     * @throws APIManagementException error while trying to retrieve URI templates of the given API
     */
    private static List<APIOperationsDTO> getOperationsFromSwaggerDef(API api, String swaggerDefinition)
            throws APIManagementException {

        APIDefinitionFromOpenAPISpec definitionFromOpenAPISpec = new APIDefinitionFromOpenAPISpec();
        Set<URITemplate> uriTemplates = api.getUriTemplates();

        if (!APIConstants.GRAPHQL_API.equals(api.getType())) {
            uriTemplates = definitionFromOpenAPISpec.getURITemplates(api, swaggerDefinition);
        }

        List<APIOperationsDTO> operationsDTOList = new ArrayList<>();
        if (!StringUtils.isEmpty(swaggerDefinition)) {
            for (URITemplate uriTemplate : uriTemplates) {
                APIOperationsDTO operationsDTO = getOperationFromURITemplate(uriTemplate);
                operationsDTOList.add(operationsDTO);
            }
        }

        return operationsDTOList;
    }

    /**
     * Converts a URI template object to a REST API DTO
     *
     * @param uriTemplate URI Template object
     * @return REST API DTO representing URI template object
     */
    private static APIOperationsDTO getOperationFromURITemplate(URITemplate uriTemplate) {

        APIOperationsDTO operationsDTO = new APIOperationsDTO();
        operationsDTO.setId(""); //todo: Set ID properly
        if (APIConstants.AUTH_APPLICATION_OR_USER_LEVEL_TOKEN.equals(uriTemplate.getAuthType())) {
            operationsDTO.setAuthType(APIConstants.OASResourceAuthTypes.APPLICATION_OR_APPLICATION_USER);
        } else if (APIConstants.AUTH_APPLICATION_USER_LEVEL_TOKEN.equals(uriTemplate.getAuthType())) {
            operationsDTO.setAuthType(APIConstants.OASResourceAuthTypes.APPLICATION_USER);
        } else if (APIConstants.AUTH_NO_AUTHENTICATION.equals(uriTemplate.getAuthType())) {
            operationsDTO.setAuthType(APIConstants.OASResourceAuthTypes.NONE);
        } else if (APIConstants.AUTH_APPLICATION_LEVEL_TOKEN.equals(uriTemplate.getAuthType())) {
            operationsDTO.setAuthType(APIConstants.OASResourceAuthTypes.APPLICATION);
        } else {
            operationsDTO.setAuthType(APIConstants.OASResourceAuthTypes.APPLICATION_OR_APPLICATION_USER);
        }
        operationsDTO.setVerb(uriTemplate.getHTTPVerb());
        operationsDTO.setTarget(uriTemplate.getUriTemplate());
        if (uriTemplate.getScope() != null) {
            operationsDTO.setScopes(new ArrayList<String>() {{
                add(uriTemplate.getScope().getName());
            }});
        }
        operationsDTO.setThrottlingPolicy(uriTemplate.getThrottlingTier());
        return operationsDTO;
    }

    /**
     * Returns a default operations list with wildcard resources and http verbs
     *
     * @return a default operations list
     */
    private static List<APIOperationsDTO> getDefaultOperationsList(String apiType) {

        List<APIOperationsDTO> operationsDTOs = new ArrayList<>();
        String[] suportMethods = null;

        if (apiType.equals(APIConstants.GRAPHQL_API)) {
            suportMethods = APIConstants.GRAPHQL_SUPPORTED_METHODS;
        } else {
            suportMethods = RestApiConstants.SUPPORTED_METHODS;
        }

        for (String verb : suportMethods) {
            APIOperationsDTO operationsDTO = new APIOperationsDTO();
            operationsDTO.setTarget("/*");
            operationsDTO.setVerb(verb);
            operationsDTO.setThrottlingPolicy(APIConstants.UNLIMITED_TIER);
            operationsDTO.setAuthType(APIConstants.AUTH_APPLICATION_OR_USER_LEVEL_TOKEN);
            operationsDTOs.add(operationsDTO);
        }
        return operationsDTOs;
    }

    public static APIProductListDTO fromAPIProductListtoDTO(List<APIProduct> productList) {
        APIProductListDTO listDto = new APIProductListDTO();
        List<APIProductInfoDTO> list = new ArrayList<APIProductInfoDTO>();
        for (APIProduct apiProduct : productList) {
            APIProductInfoDTO productDto = new APIProductInfoDTO();
            productDto.setName(apiProduct.getId().getName());
            productDto.setProvider(apiProduct.getId().getProviderName());
            productDto.setContext(apiProduct.getContext());
            productDto.setDescription(apiProduct.getDescription());
            productDto.setState(org.wso2.carbon.apimgt.rest.api.publisher.v1.dto.APIProductInfoDTO.StateEnum
                    .valueOf(apiProduct.getState()));
            productDto.setId(apiProduct.getUuid());
            productDto.setThumbnailUri(RestApiConstants.RESOURCE_PATH_THUMBNAIL_API_PRODUCT
                    .replace(RestApiConstants.APIPRODUCTID_PARAM, apiProduct.getUuid()));
            list.add(productDto);
        }

        listDto.setList(list);
        listDto.setCount(list.size());
        return listDto;
    }

    public static APIProductDTO fromAPIProducttoDTO(APIProduct product) {
        APIProductDTO productDto = new APIProductDTO();
        productDto.setName(product.getId().getName());
        productDto.setProvider(product.getId().getProviderName());
        productDto.setId(product.getUuid());
        productDto.setContext(product.getContext());
        productDto.setDescription(product.getDescription());
        APIProductBusinessInformationDTO businessInformation = new APIProductBusinessInformationDTO();
        businessInformation.setBusinessOwner(product.getBusinessOwner());
        businessInformation.setBusinessOwnerEmail(product.getBusinessOwnerEmail());
        productDto.setBusinessInformation(businessInformation );

        productDto.setState(StateEnum.valueOf(product.getState()));
        productDto.setThumbnailUri(RestApiConstants.RESOURCE_PATH_THUMBNAIL_API_PRODUCT
                .replace(RestApiConstants.APIPRODUCTID_PARAM, product.getUuid()));
        List<ProductAPIDTO> apis = new ArrayList<ProductAPIDTO>();
        //Aggregate API resources to each relevant API.
        Map<String, ProductAPIDTO> aggregatedAPIs = new HashMap<String, ProductAPIDTO>();
        List<APIProductResource> resources = product.getProductResources();
        for (APIProductResource apiProductResource : resources) {
            String uuid = apiProductResource.getApiId();
            if(aggregatedAPIs.containsKey(uuid)) {
                ProductAPIDTO productAPI = aggregatedAPIs.get(uuid);
                URITemplate template = apiProductResource.getUriTemplate();
                List<ProductAPIOperationsDTO> operations = productAPI.getOperations();
                ProductAPIOperationsDTO operation = new ProductAPIOperationsDTO();
                operation.setHttpVerb(template.getHTTPVerb());
                operation.setUritemplate(template.getResourceURI());
                operations.add(operation);

            } else {
                ProductAPIDTO productAPI = new ProductAPIDTO();
                productAPI.setApiId(uuid);
                productAPI.setName(apiProductResource.getApiName());
                List<ProductAPIOperationsDTO> operations = new ArrayList<ProductAPIOperationsDTO>();
                URITemplate template = apiProductResource.getUriTemplate();

                ProductAPIOperationsDTO operation = new ProductAPIOperationsDTO();
                operation.setHttpVerb(template.getHTTPVerb());
                operation.setUritemplate(template.getResourceURI());
                operations.add(operation);

                productAPI.setOperations(operations);
                aggregatedAPIs.put(uuid, productAPI);
            }
        }
        apis = new ArrayList<ProductAPIDTO>(aggregatedAPIs.values());
        productDto.setApis(apis);

        String subscriptionAvailability = product.getSubscriptionAvailability();
        if (subscriptionAvailability != null) {
            productDto.setSubscriptionAvailability(
                    mapSubscriptionAvailabilityFromAPIProducttoDTO(subscriptionAvailability));
        }

        if (product.getSubscriptionAvailableTenants() != null) {
            productDto.setSubscriptionAvailableTenants(Arrays.asList(product.getSubscriptionAvailableTenants().split(",")));
        }

        Set<org.wso2.carbon.apimgt.api.model.Tier> apiTiers = product.getAvailableTiers();
        List<String> tiersToReturn = new ArrayList<>();
        for (org.wso2.carbon.apimgt.api.model.Tier tier : apiTiers) {
            tiersToReturn.add(tier.getName());
        }
        productDto.setPolicies(tiersToReturn);
        if (product.getVisibility() != null) {
            productDto.setVisibility(mapVisibilityFromAPIProducttoDTO(product.getVisibility()));
        }

        if (product.getVisibleRoles() != null) {
            productDto.setVisibleRoles(Arrays.asList(product.getVisibleRoles().split(",")));
        }

        if (product.getVisibleTenants() != null) {
            productDto.setVisibleTenants(Arrays.asList(product.getVisibleTenants().split(",")));
        }

        productDto.setAccessControl(APIConstants.API_RESTRICTED_VISIBILITY.equals(product.getAccessControl()) ?
                APIProductDTO.AccessControlEnum.RESTRICTED :
                APIProductDTO.AccessControlEnum.NONE);
        if (product.getAccessControlRoles() != null) {
            productDto.setAccessControlRoles(Arrays.asList(product.getAccessControlRoles().split(",")));
        }

        if (StringUtils.isEmpty(product.getTransports())) {
            List<String> transports = new ArrayList<>();
            transports.add(APIConstants.HTTPS_PROTOCOL);

            productDto.setTransport(transports);
        } else {
            productDto.setTransport(Arrays.asList(product.getTransports().split(",")));
        }

        List<String> environmentsList = new ArrayList<String>();
        environmentsList.addAll(product.getEnvironments());
        productDto.setGatewayEnvironments(environmentsList);
        if (product.getAdditionalProperties() != null) {
            JSONObject additionalProperties = product.getAdditionalProperties();
            Map<String, String> additionalPropertiesMap = new HashMap<>();
            for (Object propertyKey : additionalProperties.keySet()) {
                String key = (String) propertyKey;
                additionalPropertiesMap.put(key, (String) additionalProperties.get(key));
            }
            productDto.setAdditionalProperties(additionalPropertiesMap);
        }

        if (product.getApiSecurity() != null) {
            productDto.setSecurityScheme(Arrays.asList(product.getApiSecurity().split(",")));
        }

        if (null != product.getLastUpdated()) {
            Date lastUpdateDate = product.getLastUpdated();
            Timestamp timeStamp = new Timestamp(lastUpdateDate.getTime());
            productDto.setLastUpdatedTime(String.valueOf(timeStamp));
        }
        if (null != product.getCreatedTime()) {
            Date createdTime = product.getLastUpdated();
            Timestamp timeStamp = new Timestamp(createdTime.getTime());
            productDto.setCreatedTime(String.valueOf(timeStamp));
        }

        return productDto;
    }

    private static APIProductDTO.SubscriptionAvailabilityEnum mapSubscriptionAvailabilityFromAPIProducttoDTO(
            String subscriptionAvailability) {

        switch (subscriptionAvailability) {
            case APIConstants.SUBSCRIPTION_TO_CURRENT_TENANT :
                return APIProductDTO.SubscriptionAvailabilityEnum.CURRENT_TENANT;
            case APIConstants.SUBSCRIPTION_TO_ALL_TENANTS :
                return APIProductDTO.SubscriptionAvailabilityEnum.ALL_TENANTS;
            case APIConstants.SUBSCRIPTION_TO_SPECIFIC_TENANTS :
                return APIProductDTO.SubscriptionAvailabilityEnum.SPECIFIC_TENANTS;
            default:
                return null; // how to handle this?
        }

    }

    private static APIProductDTO.VisibilityEnum mapVisibilityFromAPIProducttoDTO(String visibility) {
        switch (visibility) { //public, private,controlled, restricted
            case APIConstants.API_GLOBAL_VISIBILITY :
                return APIProductDTO.VisibilityEnum.PUBLIC;
            case APIConstants.API_PRIVATE_VISIBILITY :
                return APIProductDTO.VisibilityEnum.PRIVATE;
            case APIConstants.API_RESTRICTED_VISIBILITY :
                return APIProductDTO.VisibilityEnum.RESTRICTED;
            default:
                return null; // how to handle this?
        }
    }

    public static APIProduct fromDTOtoAPIProduct(APIProductDTO dto, String provider)
            throws APIManagementException {
        APIProduct product = new APIProduct();
        APIProductIdentifier id = new APIProductIdentifier(APIUtil.replaceEmailDomain(provider), dto.getName(), APIConstants.API_PRODUCT_VERSION); //todo: replace this with dto.getVersion
        product.setID(id);
        product.setUuid(dto.getId());
        product.setDescription(dto.getDescription());

        String context = dto.getContext();

        if (context.endsWith("/" + RestApiConstants.API_VERSION_PARAM)) {
            context = context.replace("/" + RestApiConstants.API_VERSION_PARAM, "");
        }

        context = context.startsWith("/") ? context : ("/" + context);
        String providerDomain = MultitenantUtils.getTenantDomain(provider);
        if (!MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equalsIgnoreCase(providerDomain)) {
            //Create tenant aware context for API
            context = "/t/" + providerDomain + context;
        }

        product.setContext(context);

        if(dto.getBusinessInformation() != null) {
            product.setBusinessOwner(dto.getBusinessInformation().getBusinessOwner());
            product.setBusinessOwnerEmail(dto.getBusinessInformation().getBusinessOwnerEmail());
        }

        String state = dto.getState() == null ? APIStatus.CREATED.toString() :dto.getState().toString() ;
        product.setState(state);
        Set<Tier> apiTiers = new HashSet<>();
        List<String> tiersFromDTO = dto.getPolicies();

        if (dto.getVisibility() != null) {
            product.setVisibility(mapVisibilityFromDTOtoAPIProduct(dto.getVisibility()));
        }
        if (dto.getVisibleRoles() != null) {
            String visibleRoles = StringUtils.join(dto.getVisibleRoles(), ',');
            product.setVisibleRoles(visibleRoles);
        }
        if (dto.getVisibleTenants() != null) {
            String visibleTenants = StringUtils.join(dto.getVisibleTenants(), ',');
            product.setVisibleTenants(visibleTenants);
        }

        List<String> accessControlRoles = dto.getAccessControlRoles();
        if (accessControlRoles == null || accessControlRoles.isEmpty()) {
            product.setAccessControl(APIConstants.NO_ACCESS_CONTROL);
            product.setAccessControlRoles("null");
        } else {
            product.setAccessControlRoles(StringUtils.join(accessControlRoles, ',').toLowerCase());
            product.setAccessControl(APIConstants.API_RESTRICTED_VISIBILITY);
        }

        for (String tier : tiersFromDTO) {
            apiTiers.add(new Tier(tier));
        }
        product.setAvailableTiers(apiTiers);
        if (dto.getSubscriptionAvailability() != null) {
            product.setSubscriptionAvailability(
                    mapSubscriptionAvailabilityFromDTOtoAPIProduct(dto.getSubscriptionAvailability()));
        }

        Map<String, String> additionalProperties = dto.getAdditionalProperties();
        if (additionalProperties != null) {
            for (Map.Entry<String, String> entry : additionalProperties.entrySet()) {
                product.addProperty(entry.getKey(), entry.getValue());
            }
        }
        if (dto.getSubscriptionAvailableTenants() != null) {
            product.setSubscriptionAvailableTenants(StringUtils.join(dto.getSubscriptionAvailableTenants(), ","));
        }

        String transports = StringUtils.join(dto.getTransport(), ',');
        product.setTransports(transports);

        if (dto.getGatewayEnvironments().size() > 0) {
            List<String> gatewaysList = dto.getGatewayEnvironments();
            product.setEnvironments(APIUtil.extractEnvironmentsForAPI(gatewaysList));
        } else if (dto.getGatewayEnvironments() != null) {
            //this means the provided gatewayEnvironments is "" (empty)
            product.setEnvironments(APIUtil.extractEnvironmentsForAPI(APIConstants.API_GATEWAY_NONE));
        }

        List<APIProductResource> productResources = new ArrayList<APIProductResource>();

        for (int i = 0; i < dto.getApis().size(); i++) {
            ProductAPIDTO res = dto.getApis().get(i);
            List<ProductAPIOperationsDTO> productAPIOperationsDTO = res.getOperations();
            for (ProductAPIOperationsDTO resourceItem : productAPIOperationsDTO) {

                URITemplate template = new URITemplate();
                template.setHTTPVerb(resourceItem.getHttpVerb());
                template.setResourceURI(resourceItem.getUritemplate());
                template.setUriTemplate(resourceItem.getUritemplate());

                APIProductResource resource = new APIProductResource();
                resource.setApiId(res.getApiId());
                resource.setUriTemplate(template);
                productResources.add(resource);
            }

        }

        Set<Scope> scopes = getScopes(dto);
        product.setScopes(scopes);

        APICorsConfigurationDTO apiCorsConfigurationDTO = dto.getCorsConfiguration();
        CORSConfiguration corsConfiguration;
        if (apiCorsConfigurationDTO != null) {
            corsConfiguration =
                    new CORSConfiguration(apiCorsConfigurationDTO.isCorsConfigurationEnabled(),
                            apiCorsConfigurationDTO.getAccessControlAllowOrigins(),
                            apiCorsConfigurationDTO.isAccessControlAllowCredentials(),
                            apiCorsConfigurationDTO.getAccessControlAllowHeaders(),
                            apiCorsConfigurationDTO.getAccessControlAllowMethods());

        } else {
            corsConfiguration = APIUtil.getDefaultCorsConfiguration();
        }
        product.setCorsConfiguration(corsConfiguration);

        product.setProductResources(productResources);
        product.setApiSecurity(getSecurityScheme(dto.getSecurityScheme()));
        product.setAuthorizationHeader(dto.getAuthorizationHeader());
        return product;
    }

    private static String mapVisibilityFromDTOtoAPIProduct(APIProductDTO.VisibilityEnum visibility) {
        switch (visibility) {
            case PUBLIC:
                return APIConstants.API_GLOBAL_VISIBILITY;
            case PRIVATE:
                return APIConstants.API_PRIVATE_VISIBILITY;
            case RESTRICTED:
                return APIConstants.API_RESTRICTED_VISIBILITY;
            default:
                return null; // how to handle this?
        }
    }

    private static String mapSubscriptionAvailabilityFromDTOtoAPIProduct(
            APIProductDTO.SubscriptionAvailabilityEnum subscriptionAvailability) {
        switch (subscriptionAvailability) {
        case CURRENT_TENANT:
                return APIConstants.SUBSCRIPTION_TO_CURRENT_TENANT;
        case ALL_TENANTS:
                return APIConstants.SUBSCRIPTION_TO_ALL_TENANTS;
        case SPECIFIC_TENANTS:
                return APIConstants.SUBSCRIPTION_TO_SPECIFIC_TENANTS;
            default:
                return APIConstants.SUBSCRIPTION_TO_CURRENT_TENANT; // default to current tenant
        }

    }

    /**
     * Converts a List object of API resource paths into a DTO
     *
     * @param resourcePathList List of API resource paths
     * @param limit   maximum number of API resource paths to be returned
     * @param offset  starting index
     * @return ResourcePathListDTO object containing ResourcePathDTOs
     */
    public static ResourcePathListDTO fromResourcePathListToDTO(List<ResourcePath> resourcePathList, int limit,
            int offset) {
        ResourcePathListDTO resourcePathListDTO = new ResourcePathListDTO();
        List<ResourcePathDTO> resourcePathDTOs = new ArrayList<ResourcePathDTO>();

        //identifying the proper start and end indexes
        int size =resourcePathList.size();
        int start = offset < size && offset >= 0 ? offset : Integer.MAX_VALUE;
        int end = offset + limit - 1 <= size - 1 ? offset + limit -1 : size - 1;

        for (int i = start; i <= end; i++) {
            ResourcePath path = resourcePathList.get(i);
            ResourcePathDTO dto = new ResourcePathDTO();
            dto.setId(path.getId());
            dto.setResourcePath(path.getResourcePath());
            dto.setHttpVerb(path.getHttpVerb());
            resourcePathDTOs.add(dto);
        }

        resourcePathListDTO.setCount(resourcePathDTOs.size());
        resourcePathListDTO.setList(resourcePathDTOs);
        return resourcePathListDTO;
    }

    /**
     * Sets pagination urls for a ResourcePathListDTO object
     *
     * @param resourcePathListDTO ResourcePathListDTO object to which pagination urls need to be set
     * @param offset     starting index
     * @param limit      max number of returned objects
     * @param size       max offset
     */
    public static void setPaginationParamsForAPIResourcePathList(ResourcePathListDTO resourcePathListDTO, int offset,
            int limit, int size) {
        //acquiring pagination parameters and setting pagination urls
        Map<String, Integer> paginatedParams = RestApiUtil.getPaginationParams(offset, limit, size);
        String paginatedPrevious = "";
        String paginatedNext = "";

        if (paginatedParams.get(RestApiConstants.PAGINATION_PREVIOUS_OFFSET) != null) {
            paginatedPrevious = RestApiUtil
                    .getResourcePathPaginatedURL(paginatedParams.get(RestApiConstants.PAGINATION_PREVIOUS_OFFSET),
                            paginatedParams.get(RestApiConstants.PAGINATION_PREVIOUS_LIMIT));
        }

        if (paginatedParams.get(RestApiConstants.PAGINATION_NEXT_OFFSET) != null) {
            paginatedNext = RestApiUtil
                    .getResourcePathPaginatedURL(paginatedParams.get(RestApiConstants.PAGINATION_NEXT_OFFSET),
                            paginatedParams.get(RestApiConstants.PAGINATION_NEXT_LIMIT));
        }

        PaginationDTO paginationDTO = CommonMappingUtil
                .getPaginationDTO(limit, offset, size, paginatedNext, paginatedPrevious);
        resourcePathListDTO.setPagination(paginationDTO);
    }

    /**
     * Sets pagination urls for a APIProductListDTO object given pagination parameters and url parameters
     *
     * @param apiProductListDTO a APIProductListDTO object
     * @param query      search condition
     * @param limit      max number of objects returned
     * @param offset     starting index
     * @param size       max offset
     */
    public static void setPaginationParams(APIProductListDTO apiProductListDTO, String query, int offset, int limit, int size) {

        //acquiring pagination parameters and setting pagination urls
        Map<String, Integer> paginatedParams = RestApiUtil.getPaginationParams(offset, limit, size);
        String paginatedPrevious = "";
        String paginatedNext = "";

        if (paginatedParams.get(RestApiConstants.PAGINATION_PREVIOUS_OFFSET) != null) {
            paginatedPrevious = RestApiUtil
                    .getAPIProductPaginatedURL(paginatedParams.get(RestApiConstants.PAGINATION_PREVIOUS_OFFSET),
                            paginatedParams.get(RestApiConstants.PAGINATION_PREVIOUS_LIMIT), query);
        }

        if (paginatedParams.get(RestApiConstants.PAGINATION_NEXT_OFFSET) != null) {
            paginatedNext = RestApiUtil
                    .getAPIProductPaginatedURL(paginatedParams.get(RestApiConstants.PAGINATION_NEXT_OFFSET),
                            paginatedParams.get(RestApiConstants.PAGINATION_NEXT_LIMIT), query);
        }

        PaginationDTO paginationDTO = CommonMappingUtil
                .getPaginationDTO(limit, offset, size, paginatedNext, paginatedPrevious);
        apiProductListDTO.setPagination(paginationDTO);
    }

    /**
     * Returns the APIProductIdentifier given the uuid
     *
     * @param productId                 API Product uuid
     * @param requestedTenantDomain tenant domain of the API
     * @return APIProductIdentifier which represents the given id
     * @throws APIManagementException
     */
    public static APIProductIdentifier getAPIProductIdentifierFromUUID(String productId, String requestedTenantDomain)
            throws APIManagementException {

        APIProvider apiProvider = RestApiUtil.getLoggedInUserProvider();
        APIProduct product = apiProvider.getAPIProductbyUUID(productId, requestedTenantDomain);
        return product.getId();
    }

        /**
     * Extract scopes from the swagger
     *
     * @param swagger swagger document
     * @return list of scopes
     * @throws APIManagementException throw if parsing exception occur
     */
    private static List<ScopeDTO> getScopesFromSwagger(String swagger) throws APIManagementException {

        JSONParser parser = new JSONParser();
        List<ScopeDTO> scopes = new ArrayList<>();
        try {
            JSONObject swaggerObj = (JSONObject) parser.parse(swagger);
            JSONObject securityObj = (JSONObject) swaggerObj.get(APIConstants.SWAGGER_X_WSO2_SECURITY);
            if (securityObj != null) {
                JSONObject apimSecurityObj = (JSONObject) securityObj.get(APIConstants.SWAGGER_OBJECT_NAME_APIM);
                JSONArray scopesList = (JSONArray) apimSecurityObj.get(APIConstants.SWAGGER_X_WSO2_SCOPES);
                scopesList.forEach((scope) -> {
                    ScopeDTO scopeDTO = new ScopeDTO();
                    JSONObject scopeObj = (JSONObject) scope;
                    scopeDTO.setName((String) scopeObj.get(APIConstants.SWAGGER_NAME));
                    scopeDTO.setDescription((String) scopeObj.get(APIConstants.SWAGGER_DESCRIPTION));
                    ScopeBindingsDTO bindingsDTO = new ScopeBindingsDTO();
                    String roles = (String) scopeObj.get(APIConstants.SWAGGER_ROLES);
                    if (roles.isEmpty()) {
                        bindingsDTO.setValues(Collections.emptyList());
                    } else {
                        bindingsDTO.setValues(Arrays.asList((roles).split(",")));
                    }
                    scopeDTO.setBindings(bindingsDTO);
                    scopes.add(scopeDTO);
                });
            }
        } catch (ParseException e) {
            throw new APIManagementException("Error occurred while parsing swagger.");
        }
        return scopes;
    }

}
