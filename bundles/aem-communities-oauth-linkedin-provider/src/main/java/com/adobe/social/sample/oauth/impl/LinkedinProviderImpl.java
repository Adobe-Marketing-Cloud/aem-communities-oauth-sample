/*************************************************************************************************
 * 
 *  ADOBE SYSTEMS INCORPORATED
 *   Copyright 2016 Adobe Systems Incorporated
 *   All Rights Reserved.
 *
 *  NOTICE:  Adobe permits you to use, modify, and distribute this file in accordance with the 
 *  terms of the Adobe license agreement accompanying it.  If you have received this file from a 
 *  source other than Adobe, then your use, modification, or distribution of it requires the prior 
 *  written permission of Adobe.
 *
 ************************************************************************************************/

package com.adobe.social.sample.oauth.impl;

import java.io.IOException;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.PropertyOption;
import org.apache.felix.scr.annotations.PropertyUnbounded;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.sling.api.SlingConstants;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.JSONObject;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.osgi.framework.Constants;
import org.osgi.service.component.ComponentContext;
import org.scribe.builder.api.Api;
import org.scribe.builder.api.LinkedInApi;
import org.scribe.model.OAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.cq.social.connect.oauth.ProviderUtils;
import com.adobe.cq.social.serviceusers.internal.ServiceUserWrapper;
import com.adobe.granite.auth.oauth.Provider;
import com.adobe.granite.auth.oauth.ProviderType;
import com.adobe.granite.security.user.UserPropertiesService;
/**
 * Example OAuth Provider for basic Linkedin Authentication integration with AEM Communities.
 * 
 *
 */
@Component(metatype = true, label = "AEM Communities Sample Linkedin OAuth Provider", inherit = true,
        immediate = false)
@Service(value = Provider.class)
public class LinkedinProviderImpl implements Provider {
    private static final String DEFAULT_PATH = "community";
    private static final String USER_ADMIN = "communities-user-admin";
    public static final String LINKEDIN_DETAILS_URL = "https://api.linkedin.com/v1/people/~?format=json";

    private static final String PROFILE_RESOURCE_TYPE = "cq/security/components/profile";

    private ResourceResolver serviceUserResolver;

    @Reference
    private UserPropertiesService userPropertiesService;

    @Reference
    private ServiceUserWrapper serviceUserWrapper;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY, policy = ReferencePolicy.STATIC)
    protected ResourceResolverFactory resourceResolverFactory;

    @Property(value = "soco-linkedin", label = "OAuth Provider ID")
    protected static final String PROP_OAUTH_PROVIDER_ID = "oauth.provider.id";

    @Property(value = DEFAULT_PATH, label = "User Path",
            options = {@PropertyOption(name = DEFAULT_PATH, value = "/home/users/community")})
    private static final String PROP_PROVIDER_CONFIG_BASE_USER_FOLDER = "provider.config.user.folder";

    @Property(unbounded = PropertyUnbounded.ARRAY,
            value = {"givenName=firstName", "familyName=lastName", "jobTitle=headline"}, label = "Field Mappings",
            description = "profile-field=provider-field")
    private static final String PROP_PROVIDER_FIELDS_MAPPINGS = "provider.config.field.mappings";

    @Property(boolValue = false, label = "Update User")
    private static final String PROP_PROVIDER_REFRESH_USER_DATA = "provider.config.refresh.userdata.enabled";

    private static final String USER_ID = "user_id";
    private static final String TOKEN = "token";
    private final Api api = new LinkedInApi();
    private final Logger log = LoggerFactory.getLogger(getClass());

    private String id;
    private String name;
    private boolean refreshUserData;
    private String userBaseFolder;
    private String[] profileMappings;
    private Map<String, String> fieldMap;

    // create service user session during @activate, close during @deactivate
    private Session session;

    /**
     * Specifies an instance of scribe {@link Api} to use for this provider.
     * @return an instance of LinkedInApi
     */
    @Override
    public Api getApi() {
        return api;
    }

    /**
     * Return the property path where the access token will be stored (if ProviderConfig is has access token storage
     * enabled)
     * @param clientId
     * @return the property path where access token may be stored for a user e.g. profile/someapp-clientid/accesstoken
     */
    @Override
    public String getAccessTokenPropertyPath(final String clientId) {
        return "oauth/token-" + clientId;
    }

    /**
     * Use the request to get the User who has (or will have) oauth profile data attached
     * @param request
     * @return the User or null, if no User is associated with the request
     */
    @Override
    public User getCurrentUser(final SlingHttpServletRequest slingRequest) {
        try {
            final Authorizable authorizable = slingRequest.getResourceResolver().adaptTo(Authorizable.class);
            if (authorizable != null && !authorizable.isGroup() && !authorizable.getID().equals("anonymous")) {
                return (User) authorizable;
            }
        } catch (final RepositoryException e) {
            log.error("provider: disabled; failed identify user", e);
        }
        return null;
    }

    /**
     * OAuth provider's user details URL
     * @return url
     */
    @Override
    public String getDetailsURL() {
        return LINKEDIN_DETAILS_URL;
    }

    /**
     * OAuth provider's user extended details URLs, depending on the specific scope
     * @return url
     */
    @Override
    public String[] getExtendedDetailsURLs(final String scope) {
        return new String[0];
    }

    /**
     * Unique ID for this provider, used to match a ProviderConfig with this Provider
     * @return ID of this provider
     */
    @Override
    public String getId() {
        return id;
    }

    @Activate
    protected void activate(final ComponentContext componentContext) throws Exception {
        @SuppressWarnings("rawtypes")
        final Dictionary props = componentContext.getProperties();

        name = PropertiesUtil.toString(props.get(Constants.SERVICE_DESCRIPTION), getClass().getSimpleName());
        id = PropertiesUtil.toString(props.get(PROP_OAUTH_PROVIDER_ID), "");
        refreshUserData = PropertiesUtil.toBoolean(props.get(PROP_PROVIDER_REFRESH_USER_DATA), false);
        userBaseFolder = PropertiesUtil.toString(props.get(PROP_PROVIDER_CONFIG_BASE_USER_FOLDER), DEFAULT_PATH);
        profileMappings = PropertiesUtil.toStringArray(props.get(PROP_PROVIDER_FIELDS_MAPPINGS));
        fieldMap = new HashMap<String, String>();
        for (int i = 0; i < profileMappings.length; i++) {
            final String mapping = profileMappings[i];
            final String parts[] = mapping.split("=", 2);
            if (parts.length != 2) {
                log.warn("Invalid profile mapping \"{}\"", mapping);
            } else {
                // If the source (provider node) has a hierarchy specified, simply store it
                if (parts[1].contains("/")) {
                    fieldMap.put(parts[0], parts[1]);
                }
                // Map the profile attribute to the base path of the profile
                else {
                    fieldMap.put(parts[0], getPropertyPath(parts[1]));
                }
            }
        }

        serviceUserResolver = serviceUserWrapper.getServiceResourceResolver(this.resourceResolverFactory,
            Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) USER_ADMIN));
        session = serviceUserResolver.adaptTo(Session.class);

    }

    /**
     * Parse the response body and return the error description contained in the response
     * @param responseBody
     * @return the error description contained in the response or null if is not contained
     */
    @Override
    public String getErrorDescriptionFromValidateTokenResponseBody(final String responseBody) {
        return null;
    }

    /**
     * OAuth provider's user extended details URLs, depending on the specific scope and previously fetched data (e.g.
     * {@link #getDetailsURL()}, {@link #getExtendedDetailsURLs(String)}).
     * @param scope allows to specify a list of property names for each scope
     * @param userId the userId
     * @param props contains the data previously fetched.
     * @return the list of urls to fetch extended data from.
     */
    @Override
    public String[] getExtendedDetailsURLs(final String scope, final String userId, final Map<String, Object> props) {
        return new String[0];
    }

    /**
     * Readable name for this Provider
     * @return name of this Provider
     */
    @Override
    public String getName() {
        return name;
    }

    // TODO: Document need for attribute index

    /**
     * Return the property path where the oauth user id will be stored
     * @param clientId
     * @return
     */
    @Override
    public String getOAuthIdPropertyPath(final String clientId) {
        return "oauth/oauthid-" + clientId;
    }

    /**
     * Create an OAuthRequest to request protected data from the OAuth provider system.
     * @param url
     * @return the OAuthRequest
     */
    @Override
    public OAuthRequest getProtectedDataRequest(final String url) {
        return new OAuthRequest(Verb.GET, url);
    }

    /**
     * Currently only oauth 1a and oauth 2 are supported.
     * @see ProviderType
     * @return type
     */
    @Override
    public ProviderType getType() {
        return ProviderType.OAUTH1A;
    }

    /**
     * Return the node path where the user should be created
     * @param userId
     * @param clientId in use when creating this user
     * @param props map of all provider's properties for this user
     * @return relative path to store this user within /home/users (e.g. "communities/1234" might be appropriate for a
     *         user with id=12345678)
     */
    @Override
    public String getUserFolderPath(final String userId, final String clientId, final Map<String, Object> props) {
        return userBaseFolder + "/" + userId.substring(0, 4);
    }

    /**
     * Parse the response body and return the userId contained in the response
     * @param responseBody
     * @return the userId contained in the response or null if is not contained
     */
    @Override
    public String getUserIdFromValidateTokenResponseBody(final String responseBody) {
        String userId = null;
        JSONObject jsonBody;
        try {
            jsonBody = new JSONObject(responseBody);
        } catch (final JSONException e) {
            log.error("getUserIdFromValidateTokenResponseBody: error while parsing response body", e);
            return userId;
        }
        final JSONObject token = jsonBody.optJSONObject(TOKEN);
        if (token != null) {
            userId = token.optString(USER_ID);
            if ("".equals(userId)) {
                userId = null;
            }
        }
        return userId;
    }

    /**
     * What is the user data property that contains this OAuth provider's user id? (e.g. "id")
     * @return
     */
    @Override
    public String getUserIdProperty() {
        return "id";
    }

    /**
     * OAuth provider validate token URL
     * @param clientId
     * @param token
     * @return url or null if validate token is not supported
     */
    @Override
    public String getValidateTokenUrl(final String clientId, final String token) {
        return null;
    }

    /**
     * Check the validity of a token
     * @param responseBody
     * @param clientId
     * @return true if the response body contains the validity of the token, the token has been issued for the
     *         provided clientId and the token type matches with the one provided
     */
    @Override
    public boolean isValidToken(final String responseBody, final String clientId, final String tokenType) {
        return false;
    }

    protected String getPropertyPath(final String property) {
        return "profile/linkedin/" + property;
    }

    /**
     * Map the provider's user properties name to CQ user properties. This method will at least be called to map
     * properties fetched from {@link #getDetailsURL()}. If {@link #getExtendedDetailsURLs(String)} is not null, this
     * method will be called for the map of properties fetched from each url.
     * @param srcUrl
     * @param clientId in use to retrieve this set of properties
     * @param existing CQ properties that have already been mapped
     * @param newProperties addition provider properties that need to be mapped
     * @return the result of mapping the new properties, and combining with the existing
     */
    @Override
    public Map<String, Object> mapProperties(final String srcUrl, final String clientId,
        final Map<String, Object> existing, final Map<String, String> newProperties) {

        if (srcUrl.equals(getDetailsURL())) {
            final Map<String, Object> mapped = new HashMap<String, Object>();
            mapped.putAll(existing);

            for (final Map.Entry<String, String> prop : newProperties.entrySet()) {
                final String key = prop.getKey();

                final String mappedKey = getPropertyPath(key);
                final Object mappedValue = prop.getValue();
                if (mappedValue != null) {
                    mapped.put(mappedKey, mappedValue);
                }
            }
            return mapped;
        }

        return existing;
    }

    /**
     * Map the provider's userid to CRX user id; Note that usernames must be unique so the returned username should
     * always include some prefix specific to this provider (e.g. in case facebook, twitter, etc have a user with the
     * same username)
     * @param userId provider's userId
     * @param props map of all provider's properties for this userId
     * @return CQ user id
     */
    @Override
    public String mapUserId(final String userId, final Map<String, Object> props) {
        final String userName = (String) props.get(getPropertyPath("id"));
        if (userName != null && userName.length() > 0) {
            return "li-" + userName;
        } else {
            return "li-" + userId;
        }
    }

    /**
     * Called after a user is created by Granite
     * @param user
     */
    @Override
    public void onUserCreate(final User user) {
        log.debug("onUserCreate:" + user);
        try {
            session.refresh(true);
            final Node userNode = session.getNode(userPropertiesService.getAuthorizablePath(user.getID()));
            final Node profNode = userNode.getNode("profile");

            // Set the profile resource type to be a CQ profile
            profNode.setProperty(SlingConstants.NAMESPACE_PREFIX + ":" + SlingConstants.PROPERTY_RESOURCE_TYPE,
                PROFILE_RESOURCE_TYPE);

            processProfileMappings(userNode, profNode);

            session.save();
        } catch (final RepositoryException e) {
            log.error("onUserCreate: failed to copy profile properties to cq profile", e);
        }
    }

    private String getUserNodeAttributeString(final Node userNode, final String attributeName,
        final String defaultVal) {
        String retVal = defaultVal;
        try {
            final javax.jcr.Property prop = userNode.getProperty(attributeName);
            if (prop != null) {
                retVal = prop.getString() == null ? defaultVal : prop.getString();
            }
        } catch (final RepositoryException e) {
            log.warn("Couldn't get {} attribute value from user profile.", attributeName);
        }

        return retVal;
    }

    /**
     * Copy configured values from user's node (updated by Granite) to the profile node
     * @param userNode
     * @param profNode
     * @throws RepositoryException
     */
    protected void processProfileMappings(final Node userNode, final Node profNode) throws RepositoryException {
        for (final Entry<String, String> entry : fieldMap.entrySet()) {
            final String val = getUserNodeAttributeString(userNode, entry.getValue(), null);
            if (val != null) {
                profNode.setProperty(entry.getKey(), val);
            }
        }
    }

    /**
     * Called after a user is updated (i.e. profile data is mapped and applied to user that already exists);
     * @param user
     */
    @Override
    public void onUserUpdate(final User user) {
        if (refreshUserData) {
            try {
                session.refresh(true);
                final Node userNode = session.getNode(userPropertiesService.getAuthorizablePath(user.getID()));
                final Node profNode = userNode.getNode("profile");

                processProfileMappings(userNode, profNode);

                session.save();
            } catch (final RepositoryException e) {
                log.error("onUserUpdate: failed to update profile properties in cq profile", e);
            }
        }
    }

    /**
     * Parse the OAuth Response for protected profile data during profile import
     * @param response
     * @return Map of profile properties
     */
    @Override
    public Map<String, String> parseProfileDataResponse(final Response response) throws IOException {
        return ProviderUtils.parseProfileDataResponse(response);
    }

    @Deactivate
    protected void deactivate(final ComponentContext componentContext) throws Exception {
        log.debug("deactivating provider id {}", id);
        if (session != null && session.isLive()) {
            try {
                session.logout();
            } catch (final Exception e) {
                // ignore
            }
            session = null;
        }
        if (serviceUserResolver != null) {
            serviceUserResolver.close();
        }
    }

}
