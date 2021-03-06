/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.ballerinalang.net.websub;

import io.ballerina.runtime.api.creators.ValueCreator;
import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BArray;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BObject;
import io.ballerina.runtime.api.values.BString;
import org.ballerinalang.net.http.HttpConstants;
import org.ballerinalang.net.http.HttpResource;
import org.ballerinalang.net.http.HttpService;
import org.ballerinalang.net.transport.contract.exceptions.ServerConnectorException;
import org.ballerinalang.net.transport.message.HttpCarbonMessage;
import org.ballerinalang.net.uri.URIUtil;

import java.io.UnsupportedEncodingException;

import static org.ballerinalang.net.http.HttpConstants.HTTP_METHOD_GET;
import static org.ballerinalang.net.http.HttpConstants.HTTP_METHOD_POST;
import static org.ballerinalang.net.transport.contract.Constants.HTTP_RESOURCE;
import static org.ballerinalang.net.websub.WebSubSubscriberConstants.ANNOTATED_TOPIC;
import static org.ballerinalang.net.websub.WebSubSubscriberConstants.ANN_NAME_WEBSUB_SUBSCRIBER_SERVICE_CONFIG;
import static org.ballerinalang.net.websub.WebSubSubscriberConstants.ANN_WEBSUB_ATTR_TARGET;
import static org.ballerinalang.net.websub.WebSubSubscriberConstants.DEFERRED_FOR_PAYLOAD_BASED_DISPATCHING;
import static org.ballerinalang.net.websub.WebSubSubscriberConstants.ENTITY_ACCESSED_REQUEST;
import static org.ballerinalang.net.websub.WebSubSubscriberConstants.RESOURCE_NAME_ON_INTENT_VERIFICATION;
import static org.ballerinalang.net.websub.WebSubSubscriberConstants.RESOURCE_NAME_ON_NOTIFICATION;
import static org.ballerinalang.net.websub.WebSubSubscriberConstants.RESOURCE_NAME_ON_SUBSCRIPTION_DENIED;
import static org.ballerinalang.net.websub.WebSubSubscriberConstants.TOPIC_ID_HEADER;
import static org.ballerinalang.net.websub.WebSubSubscriberConstants.TOPIC_ID_PAYLOAD_KEY;
import static org.ballerinalang.net.websub.WebSubSubscriberConstants.WEBSUB_PACKAGE_FULL_QUALIFIED_NAME;
import static org.ballerinalang.net.websub.WebSubUtils.getHttpRequest;
import static org.ballerinalang.net.websub.WebSubUtils.getJsonBody;

/**
 * Resource dispatcher specific for WebSub subscriber services.
 *
 * @since 0.965.0
 */
class WebSubResourceDispatcher {

    static HttpResource findResource(HttpService service, HttpCarbonMessage inboundRequest,
                                     WebSubServicesRegistry servicesRegistry)
            throws BallerinaConnectorException, ServerConnectorException {

        String method = inboundRequest.getHttpMethod();
        HttpResource httpResource = null;
        String resourceName;

        // TODO: 8/1/18 refactor this block to reduce checks
        String topicIdentifier = servicesRegistry.getTopicIdentifier();
        if (TOPIC_ID_HEADER.equals(topicIdentifier) && HTTP_METHOD_POST.equals(method)) {
            String topic = inboundRequest.getHeader(servicesRegistry.getTopicHeader());
            resourceName = retrieveResourceNameFromTopic(StringUtils.fromString(topic), servicesRegistry.getHeaderResourceMap());
        } else if (topicIdentifier != null && HTTP_METHOD_POST.equals(method)) {
            if (inboundRequest.getProperty(HTTP_RESOURCE) == null) {
                inboundRequest.setProperty(HTTP_RESOURCE, DEFERRED_FOR_PAYLOAD_BASED_DISPATCHING);
                return null;
            }
            if (topicIdentifier.equals(TOPIC_ID_PAYLOAD_KEY)) {
                resourceName = retrieveResourceName(inboundRequest, servicesRegistry.getPayloadKeyResourceMap());
            } else {
                resourceName = retrieveResourceName(inboundRequest, servicesRegistry);
            }
        } else {
            resourceName = retrieveResourceName(method, inboundRequest);
        }

        for (HttpResource resource : service.getResources()) {
            if (resource.getName().equals(resourceName)) {
                httpResource = resource;
                break;
            }
        }

        if (httpResource == null) {
            if (RESOURCE_NAME_ON_INTENT_VERIFICATION.equals(resourceName)) {
                //if the request is a GET request indicating an intent verification request, and the user has not
                //specified an onIntentVerification resource, assume auto intent verification
                Object target = ((BMap) (service.getBalService().getType()).getAnnotation(StringUtils.fromString(
                        WEBSUB_PACKAGE_FULL_QUALIFIED_NAME + ":" + ANN_NAME_WEBSUB_SUBSCRIBER_SERVICE_CONFIG)))
                        .get(ANN_WEBSUB_ATTR_TARGET);
                String annotatedTopic = "";

                if (target instanceof BArray) {
                    annotatedTopic = ((BArray) target).getString(1);
                }

                if (annotatedTopic.isEmpty() && service instanceof WebSubHttpService) {
                    annotatedTopic = ((WebSubHttpService) service).getTopic();
                }
                inboundRequest.setProperty(ANNOTATED_TOPIC, annotatedTopic);
                inboundRequest.setProperty(HTTP_RESOURCE, ANNOTATED_TOPIC);
            } else if (RESOURCE_NAME_ON_SUBSCRIPTION_DENIED.equals(resourceName)) {
                inboundRequest.setHttpStatusCode(200);
                throw new BallerinaConnectorException("On subscription denied request is not handled in the service");
            } else {
                inboundRequest.setHttpStatusCode(404);
                throw new BallerinaConnectorException("no matching WebSub Subscriber service  resource " + resourceName
                                                              + " found for method : " + method);
            }
        }
        return httpResource;
    }

    /**
     * Method to retrieve resource names for default WebSub subscriber services.
     *
     * @param method    the method of the received request
     * @return          {@link WebSubSubscriberConstants#RESOURCE_NAME_ON_INTENT_VERIFICATION} if the method is GET,
     *                  {@link WebSubSubscriberConstants#RESOURCE_NAME_ON_NOTIFICATION} if the method is POST
     * @throws BallerinaConnectorException for any method other than GET or POST
     */
    private static String retrieveResourceName(String method, HttpCarbonMessage inboundRequest) {
        switch (method) {
            case HTTP_METHOD_POST:
                return RESOURCE_NAME_ON_NOTIFICATION;
            case HTTP_METHOD_GET:
                String queryString = (String) inboundRequest.getProperty(HttpConstants.QUERY_STR);
                BMap<BString, Object> params = ValueCreator.createMapValue();
                String hubMode = "";
                try {
                    URIUtil.populateQueryParamMap(queryString, params);
                    hubMode = params.getArrayValue(StringUtils.fromString("hub.mode")).getBString(0).getValue();
                } catch (UnsupportedEncodingException e) {
                    inboundRequest.setHttpStatusCode(404);
                    throw new BallerinaConnectorException("Bad Request. No query params found");
                }
                if (hubMode.equalsIgnoreCase("denied")) {
                    return RESOURCE_NAME_ON_SUBSCRIPTION_DENIED;
                } else if (hubMode.equalsIgnoreCase("accepted")) {
                    return RESOURCE_NAME_ON_INTENT_VERIFICATION;
                }
            default:
                throw new BallerinaConnectorException("method not allowed for WebSub Subscriber Services : " + method);
        }
    }

    /**
     * Method to retrieve the resource name when the mapping between topic and resource for custom subscriber services
     * is specified as a combination of a header and a key of the JSON payload.
     *
     * @param inboundRequest                 the request received
     * @param servicesRegistry               the service registry instance
     * @return                               the name of the resource as identified based on the topic
     * @throws BallerinaConnectorException   if a resource could not be mapped to the topic identified
     */
    private static String retrieveResourceName(HttpCarbonMessage inboundRequest,
                                               WebSubServicesRegistry servicesRegistry) {
        BMap<BString, BMap<BString, BMap<BString, Object>>> headerAndPayloadKeyResourceMap =
                servicesRegistry.getHeaderAndPayloadKeyResourceMap();
        BString topic = StringUtils.fromString(inboundRequest.getHeader(servicesRegistry.getTopicHeader()));
        BObject httpRequest = getHttpRequest(inboundRequest);
        BMap<BString, ?> jsonBody = getJsonBody(httpRequest);
        inboundRequest.setProperty(ENTITY_ACCESSED_REQUEST, httpRequest);

        if (headerAndPayloadKeyResourceMap.containsKey(topic)) {
            BMap<BString, BMap<BString, Object>> topicResourceMapForHeader =
                    headerAndPayloadKeyResourceMap.get(topic);
            for (BString key : topicResourceMapForHeader.getKeys()) {
                if (jsonBody.containsKey(key)) {
                    BMap<BString, Object> topicResourceMapForValue = topicResourceMapForHeader.get(key);
                    BString valueForKey = (BString) jsonBody.get(key);
                    if (topicResourceMapForValue.containsKey(valueForKey)) {
                        return retrieveResourceNameFromTopic(valueForKey, topicResourceMapForValue);
                    }
                }
            }
        }

        if (servicesRegistry.getHeaderResourceMap() != null) {
            BMap<BString, Object> headerResourceMap = servicesRegistry.getHeaderResourceMap();
            if (headerResourceMap.containsKey(topic)) {
                return retrieveResourceNameFromTopic(topic, headerResourceMap);
            }
        }

        if (servicesRegistry.getPayloadKeyResourceMap() != null) {
            BMap<BString, BMap<BString, Object>> payloadKeyResourceMap =
                    servicesRegistry.getPayloadKeyResourceMap();
            String resourceName = retrieveResourceNameForKey(jsonBody, payloadKeyResourceMap);
            if (resourceName != null) {
                return resourceName;
            }
        }
        throw new BallerinaConnectorException("Matching resource not found for dispatching based on Header and "
                                                      + "Payload Key");
    }

    /**
     * Method to retrieve the resource name when the mapping between topic and resource for custom subscriber services
     * is specified as a key of the JSON payload.
     *
     * @param inboundRequest         the request received
     * @param payloadKeyResourceMap  the mapping between the topics defined as a value of a payload key and resources
     * @return                       the name of the resource as identified based on the topic
     * @throws BallerinaConnectorException if a resource could not be mapped to the topic identified
     */
    private static String retrieveResourceName(HttpCarbonMessage inboundRequest,
                                               BMap<BString, BMap<BString, Object>> payloadKeyResourceMap) {
        BObject httpRequest = getHttpRequest(inboundRequest);
        BMap<BString, ?> jsonBody = getJsonBody(httpRequest);
        inboundRequest.setProperty(ENTITY_ACCESSED_REQUEST, httpRequest);
        String resourceName = retrieveResourceNameForKey(jsonBody, payloadKeyResourceMap);
        if (resourceName != null) {
            return resourceName;
        }
        throw new BallerinaConnectorException("Matching resource not found for dispatching based on Payload Key");
    }

    private static String retrieveResourceNameForKey(BMap<BString, ?> jsonBody,
                                                     BMap<BString, BMap<BString, Object>>
                                                             payloadKeyResourceMap) {
        for (BString key : payloadKeyResourceMap.getKeys()) {
            if (jsonBody.containsKey(key)) {
                BMap<BString, Object> topicResourceMapForValue = payloadKeyResourceMap.get(key);
                BString valueForKey = (BString) jsonBody.get(key);
                if (topicResourceMapForValue.containsKey(valueForKey)) {
                    return retrieveResourceNameFromTopic(valueForKey, topicResourceMapForValue);
                }
            }
        }
        return null;
    }


    /**
     * Method to retrieve the resource name from the topic -- resource map for a topic.
     *
     * @param topic             the topic for which the resource needs to be identified
     * @param topicResourceMap  the mapping between the topics and resources
     * @return                  the name of the resource as identified based on the topic
     * @throws BallerinaConnectorException if a resource could not be mapped to the topic
     */
    private static String retrieveResourceNameFromTopic(BString topic, BMap<BString, Object> topicResourceMap) {
        if (topicResourceMap.containsKey(topic)) {
            return ((BArray) topicResourceMap.get(topic)).getRefValue(0).toString();
        } else {
            throw new BallerinaConnectorException("resource not specified for topic : " + topic);
        }
    }

}
